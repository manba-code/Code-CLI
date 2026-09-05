package com.paicli.runtime.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.change.*;
import com.paicli.spec.ChangeSpecValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Map;

/** HTTP 只做请求翻译和错误映射。认证由 RuntimeApiServer 统一执行。 */
public final class ChangeApiHandler implements HttpHandler {
    private static final int MAX_BODY = 1_048_576;
    private final ChangeWorkflow workflow;
    private final ChangeStore queries;
    private final MockWorkItemAdapter workItems;
    private final ChangeArtifactReader artifacts;
    private final boolean offlineDemo;

    public ChangeApiHandler(ChangeWorkflow workflow, ChangeStore queries, MockWorkItemAdapter workItems) {
        this(workflow, queries, workItems, null, false);
    }

    public ChangeApiHandler(ChangeWorkflow workflow, ChangeStore queries, MockWorkItemAdapter workItems,
                            ChangeArtifactReader artifacts, boolean offlineDemo) {
        this.artifacts = artifacts;
        this.offlineDemo = offlineDemo;
        if (artifacts != null && workflow instanceof DefaultChangeWorkflow concrete) concrete.connectArtifacts(artifacts);
        this.workflow = java.util.Objects.requireNonNull(workflow);
        this.queries = java.util.Objects.requireNonNull(queries);
        this.workItems = java.util.Objects.requireNonNull(workItems);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ChangeForbiddenException e) {
            error(exchange, 403, "forbidden", e.getMessage());
        } catch (ChangeNotFoundException | NoSuchFileException e) {
            error(exchange, 404, "not_found", "ChangeTask、fixture 或 Artifact 不存在");
        } catch (ChangeConflictException e) {
            error(exchange, 409, "conflict", e.getMessage());
        } catch (ChangeValidationException | ChangeSpecValidationException e) {
            error(exchange, 422, "unprocessable_decision", e.getMessage());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            error(exchange, 400, "invalid_request", "请求结构或字段错误: " + e.getMessage());
        } catch (Exception e) {
            error(exchange, 500, "internal_error", "内部处理失败；请查询任务与事件确认已持久化的进度");
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/v1/changes/capabilities") && method.equals("GET")) {
            write(exchange, 200, Map.of("offlineDemo", offlineDemo, "scm", "MOCK",
                    "toolPolicyEnforced", false, "workerHitl", false));
            return;
        }
        if (path.equals("/v1/changes")) {
            if (method.equals("POST")) {
                JsonNode body = body(exchange);
                if (offlineDemo && (body.size() != 1 || !body.path("fixture").asText().equals("offline-refund.json"))) {
                    throw new IllegalArgumentException("离线演示仅接受 offline-refund.json fixture");
                }
                ChangeTaskId id = body.has("fixture")
                        ? workItems.submit(ChangeJson.text(body, "fixture"))
                        : workflow.submit(ChangeJson.request(body));
                exchange.getResponseHeaders().set("Location", "/v1/changes/" + id.value());
                write(exchange, 201, view(workflow.get(id)));
                return;
            }
            if (method.equals("GET")) {
                var items = ChangeJson.MAPPER.createArrayNode();
                for (ChangeTask task : queries.list()) items.add(view(workflow.get(task.id())));
                write(exchange, 200, Map.of("changes", items));
                return;
            }
        }
        String[] parts = path.split("/", -1);
        if (parts.length < 4 || !parts[1].equals("v1") || !parts[2].equals("changes") || parts[3].isBlank()) {
            error(exchange, 404, "not_found", "端点不存在");
            return;
        }
        ChangeTaskId id = new ChangeTaskId(parts[3]);
        if (method.equals("GET") && parts.length == 5 && parts[4].equals("artifacts") && artifacts != null) {
            Integer from = null, to = null;
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                if (!query.matches("fromRevision=[1-9][0-9]*&toRevision=[1-9][0-9]*")) {
                    throw new IllegalArgumentException("仅支持 fromRevision 和 toRevision 正整数");
                }
                String[] values = query.split("&");
                from = Integer.valueOf(values[0].split("=")[1]);
                to = Integer.valueOf(values[1].split("=")[1]);
            }
            write(exchange, 200, artifacts.read(workflow.get(id), from, to));
            return;
        }
        if (method.equals("GET") && parts.length == 4) {
            write(exchange, 200, view(workflow.get(id)));
            return;
        }
        if (method.equals("GET") && parts.length == 5 && parts[4].equals("events")) {
            long after = after(exchange.getRequestURI().getRawQuery());
            var events = workflow.get(id).events().stream().filter(e -> e.sequence() > after).toList();
            write(exchange, 200, Map.of("events", events));
            return;
        }
        if (method.equals("POST") && parts.length == 5
                && (parts[4].equals("draft-cancel") || parts[4].equals("draft-retry"))) {
            JsonNode body = body(exchange);
            long version = expectedVersion(body);
            String generation = ChangeJson.text(body, "expectedGeneration");
            String actor = ChangeJson.text(body, "actorId");
            ChangeTaskView result = parts[4].equals("draft-cancel")
                    ? workflow.cancelDraft(id, version, generation, actor)
                    : workflow.retryDraft(id, version, generation, actor);
            write(exchange, 200, view(result));
            return;
        }
        if (method.equals("POST") && parts.length == 5 && parts[4].equals("human-evidence")) {
            JsonNode body = body(exchange);
            java.util.Set<String> fields = java.util.Set.of("expectedVersion", "expectedSpecDigest", "expectedRunId",
                    "expectedHeadSha", "expectedJudgmentRevision", "criterionId", "decision", "reason", "artifactRefs", "actorId");
            body.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw new IllegalArgumentException("未知人工验收字段: " + field); });
            java.util.List<String> refs = new java.util.ArrayList<>();
            if (body.has("artifactRefs")) {
                if (!body.path("artifactRefs").isArray()) throw new IllegalArgumentException("artifactRefs 必须是数组");
                for (JsonNode ref : body.path("artifactRefs")) {
                    if (!ref.isTextual()) throw new IllegalArgumentException("Artifact 引用必须是字符串 ID");
                    refs.add(ref.asText());
                }
            }
            var input = new HumanEvidenceSubmission(expectedVersion(body), ChangeJson.text(body, "expectedSpecDigest"),
                    ChangeJson.text(body, "expectedRunId"), ChangeJson.text(body, "expectedHeadSha"),
                    nonNegative(body, "expectedJudgmentRevision"), ChangeJson.text(body, "criterionId"),
                    com.paicli.spec.SpecRunResult.HumanDecision.valueOf(ChangeJson.text(body, "decision")),
                    ChangeJson.text(body, "reason"), refs, ChangeJson.text(body, "actorId"));
            write(exchange, 200, view(workflow.recordHumanEvidence(id, input)));
            return;
        }
        if (method.equals("POST") && parts.length == 5
                && (parts[4].equals("spec-decisions") || parts[4].equals("delivery-decisions"))) {
            JsonNode body = body(exchange);
            write(exchange, 200, view(workflow.decide(id, decision(body, parts[4].equals("spec-decisions")))));
            return;
        }
        error(exchange, 404, "not_found", "端点不存在");
    }

    private static ChangeDecision decision(JsonNode body, boolean spec) {
        long expected = expectedVersion(body);
        String actor = ChangeJson.text(body, "actorId");
        String reason = ChangeJson.optionalText(body, "reason");
        String decision = ChangeJson.text(body, "decision");
        if (spec) {
            String digest = ChangeJson.text(body, "expectedDraftDigest");
            return switch (decision) {
                case "APPROVE" -> new ChangeDecision.ApproveSpec(expected, digest, actor, reason);
                case "REJECT" -> new ChangeDecision.RejectSpec(expected, digest, actor, reason);
                case "SUPPLEMENT" -> new ChangeDecision.SupplementSpec(expected, digest, actor,
                        ChangeJson.text(body, "supplement"));
                default -> throw new IllegalArgumentException("未知 Spec decision");
            };
        }
        String digest = ChangeJson.text(body, "expectedSpecDigest");
        String head = ChangeJson.text(body, "expectedHeadSha");
        String run = ChangeJson.text(body, "expectedRunId");
        long revision = nonNegative(body, "expectedJudgmentRevision");
        return switch (decision) {
            case "APPROVE" -> new ChangeDecision.ApproveDelivery(expected, digest, head, run, revision, actor, reason);
            case "REJECT" -> new ChangeDecision.RejectDelivery(expected, digest, head, run, revision, actor, reason);
            default -> throw new IllegalArgumentException("未知 Delivery decision");
        };
    }

    private static long expectedVersion(JsonNode body) {
        return nonNegative(body, "expectedVersion");
    }

    private static long nonNegative(JsonNode body, String field) {
        JsonNode version = body.path(field);
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
        return version.longValue();
    }

    private static ObjectNode view(ChangeTaskView view) {
        ObjectNode json = ChangeJson.MAPPER.valueToTree(view.task());
        json.remove("id");
        if (json.path("draftJob").isObject()) {
            ObjectNode job = (ObjectNode) json.get("draftJob");
            job.put("revision", view.task().draftJob().input().revision());
            job.remove(java.util.List.of("input", "lease"));
        }
        json.put("changeId", view.task().id().value());
        json.put("judgmentRevision", view.task().judgmentRevision());
        json.set("deliveryVerdict", ChangeJson.MAPPER.valueToTree(view.task().deliveryVerdict()));
        json.put("deliveryApprovalValid", view.task().run() != null && view.task().spec() != null
                && view.task().deliveryApprovedFor(view.task().spec().digest(), view.task().run().headSha()));
        json.set("deliveryHistory", ChangeJson.MAPPER.valueToTree(view.deliveryHistory()));
        json.set("delivery", ChangeJson.MAPPER.valueToTree(view.delivery()));
        return json;
    }

    private static JsonNode body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new IllegalArgumentException("请求体超过 1 MiB");
        JsonNode body = ChangeJson.MAPPER.readTree(bytes);
        ChangeJson.object(body, "request");
        return body;
    }

    private static long after(String query) {
        if (query == null || query.isEmpty()) return 0;
        if (!query.matches("after=[0-9]+")) throw new IllegalArgumentException("events 仅支持非负 after 游标");
        return Long.parseLong(query.substring(6));
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        write(exchange, status, Map.of("error", code, "message", message));
    }

    private static void write(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = ChangeJson.MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
