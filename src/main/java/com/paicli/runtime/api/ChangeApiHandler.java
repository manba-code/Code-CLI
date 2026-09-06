package com.paicli.runtime.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.change.*;
import com.paicli.runtime.auth.Principal;
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
    private final ChangeAuthorizer authorizer;
    private final ToolApprovalCoordinator toolApprovals;
    private final WorkerIsolation.Capabilities isolationCapabilities;
    private final boolean evidenceIntegrity;

    public ChangeApiHandler(ChangeWorkflow workflow, ChangeStore queries, MockWorkItemAdapter workItems) {
        this(workflow, queries, workItems, null, false);
    }

    public ChangeApiHandler(ChangeWorkflow workflow, ChangeStore queries, MockWorkItemAdapter workItems,
                            ChangeArtifactReader artifacts, boolean offlineDemo) {
        this(workflow, queries, workItems, artifacts, offlineDemo,
                new ChangeAuthorizer(ProjectMembershipProvider.none()));
    }

    public ChangeApiHandler(ChangeWorkflow workflow, ChangeStore queries, MockWorkItemAdapter workItems,
                            ChangeArtifactReader artifacts, boolean offlineDemo, ChangeAuthorizer authorizer) {
        this(workflow, queries, workItems, artifacts, offlineDemo, authorizer, null);
    }

    public ChangeApiHandler(ChangeWorkflow workflow, ChangeStore queries, MockWorkItemAdapter workItems,
                            ChangeArtifactReader artifacts, boolean offlineDemo, ChangeAuthorizer authorizer,
                            ToolApprovalCoordinator toolApprovals) {
        this(workflow, queries, workItems, artifacts, offlineDemo, authorizer, toolApprovals,
                WorkerIsolation.Capabilities.disabled(), false);
    }

    public ChangeApiHandler(ChangeWorkflow workflow, ChangeStore queries, MockWorkItemAdapter workItems,
                            ChangeArtifactReader artifacts, boolean offlineDemo, ChangeAuthorizer authorizer,
                            ToolApprovalCoordinator toolApprovals,
                            WorkerIsolation.Capabilities isolationCapabilities, boolean evidenceIntegrity) {
        this.artifacts = artifacts;
        this.offlineDemo = offlineDemo;
        this.authorizer = java.util.Objects.requireNonNull(authorizer, "authorizer");
        this.toolApprovals = toolApprovals;
        this.isolationCapabilities = isolationCapabilities == null
                ? WorkerIsolation.Capabilities.disabled() : isolationCapabilities;
        this.evidenceIntegrity = evidenceIntegrity;
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
        Principal principal = principal(exchange);
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/v1/changes/capabilities") && method.equals("GET")) {
            var response = ChangeJson.MAPPER.createObjectNode();
            response.put("offlineDemo", offlineDemo).put("scm", "MOCK")
                    .put("toolPolicyEnforced", toolApprovals != null).put("workerHitl", toolApprovals != null)
                    .put("executionIsolation", isolationCapabilities.enabled())
                    .put("evidenceIntegrity", evidenceIntegrity)
                    .set("isolation", ChangeJson.MAPPER.valueToTree(isolationCapabilities));
            response
                    .put("authMode", String.valueOf(exchange.getAttribute(RuntimeApiServer.AUTH_MODE_ATTRIBUTE)))
                    .put("localTrustedMode", principal.localTrusted())
                    .put("deploymentBoundary", principal.localTrusted()
                            ? "Single-operator localhost compatibility mode; not a shared-deployment identity solution"
                            : "Server-verified principals with per-project membership");
            response.set("principal", ChangeJson.MAPPER.valueToTree(Map.of(
                    "subjectId", principal.subjectId(), "displayName", principal.displayName(),
                    "type", principal.type().name(), "issuer", principal.issuer())));
            response.set("memberships", ChangeJson.MAPPER.valueToTree(authorizer.memberships(principal)));
            write(exchange, 200, response);
            return;
        }
        String[] routeParts = path.split("/", -1);
        if (routeParts.length == 6 && routeParts[1].equals("v1") && routeParts[2].equals("changes")
                && routeParts[3].equals("projects") && routeParts[5].equals("tool-policy")) {
            if (toolApprovals == null) throw new ChangeValidationException("工具策略服务未装配");
            String projectId = routeParts[4];
            if (method.equals("GET")) {
                authorizer.require(principal, projectId, ChangePermission.READ_TASK);
                write(exchange, 200, toolApprovals.policy(projectId));
                return;
            }
            if (method.equals("PUT")) {
                JsonNode request = body(exchange);
                long expected = nonNegative(request, "expectedVersion");
                if (!request.path("rules").isArray()) throw new IllegalArgumentException("rules 必须是数组");
                java.util.List<ProjectToolPolicy.Rule> rules = new java.util.ArrayList<>();
                for (JsonNode rule : request.path("rules")) {
                    try { rules.add(ChangeJson.MAPPER.treeToValue(rule, ProjectToolPolicy.Rule.class)); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalArgumentException("tool policy rule 无效: " + e.getOriginalMessage(), e);
                    }
                }
                write(exchange, 200, toolApprovals.updatePolicy(projectId, expected, rules, principal));
                return;
            }
        }
        if (path.equals("/v1/changes")) {
            if (method.equals("POST")) {
                JsonNode body = body(exchange);
                if (offlineDemo && (body.size() != 1 || !body.path("fixture").asText().equals("offline-refund.json"))) {
                    throw new IllegalArgumentException("离线演示仅接受 offline-refund.json fixture");
                }
                ChangeTaskId id;
                if (body.has("fixture")) {
                    if (!principal.localTrusted()) throw new ChangeForbiddenException("fixture 仅允许本地可信模式");
                    id = workItems.submit(ChangeJson.text(body, "fixture"), principal.subjectId(), principal.actorType());
                } else {
                    requireCompatibleActor(body, principal);
                    ChangeRequest request = ChangeJson.request(body, principal.subjectId(), principal.actorType());
                    authorizer.require(principal, ChangeProject.id(request.repository()), ChangePermission.CREATE_TASK);
                    id = workflow.submit(request);
                }
                exchange.getResponseHeaders().set("Location", "/v1/changes/" + id.value());
                write(exchange, 201, view(workflow.get(id), principal));
                return;
            }
            if (method.equals("GET")) {
                var items = ChangeJson.MAPPER.createArrayNode();
                for (ChangeTask task : queries.list()) {
                    if (authorizer.permissions(principal, ChangeProject.id(task.repository()))
                            .contains(ChangePermission.READ_TASK)) {
                        items.add(view(workflow.get(task.id()), principal));
                    }
                }
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
        if (method.equals("GET") && parts.length == 5 && parts[4].equals("artifacts")) {
            authorizeTask(id, principal, ChangePermission.READ_ARTIFACTS);
            if (artifacts == null) throw new NoSuchFileException("Artifact reader unavailable");
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
            authorizeTask(id, principal, ChangePermission.READ_TASK);
            write(exchange, 200, view(workflow.get(id), principal));
            return;
        }
        if (method.equals("GET") && parts.length == 5 && parts[4].equals("events")) {
            authorizeTask(id, principal, ChangePermission.READ_EVENTS);
            long after = after(exchange.getRequestURI().getRawQuery());
            var events = workflow.get(id).events().stream().filter(e -> e.sequence() > after).toList();
            write(exchange, 200, Map.of("events", events));
            return;
        }
        if (method.equals("GET") && parts.length == 5 && parts[4].equals("tool-approvals")) {
            authorizeTask(id, principal, ChangePermission.READ_TASK);
            if (toolApprovals == null) throw new ChangeValidationException("工具审批服务未装配");
            write(exchange, 200, Map.of("toolApprovals", toolApprovals.approvals(id)));
            return;
        }
        if (method.equals("POST") && parts.length == 7 && parts[4].equals("tool-approvals")
                && parts[6].equals("decisions")) {
            if (toolApprovals == null) throw new ChangeValidationException("工具审批服务未装配");
            JsonNode request = body(exchange);
            String decision = ChangeJson.text(request, "decision");
            ToolApproval.Status status = switch (decision) {
                case "APPROVE" -> ToolApproval.Status.APPROVED;
                case "REJECT" -> ToolApproval.Status.REJECTED;
                default -> throw new IllegalArgumentException("未知工具审批 decision");
            };
            ToolApproval result = toolApprovals.decide(id, parts[5], status,
                    nonNegative(request, "expectedPolicyVersion"),
                    ChangeJson.text(request, "expectedArgumentsDigest"),
                    ChangeJson.text(request, "expectedCallId"),
                    ChangeJson.text(request, "expectedRunId"),
                    ChangeJson.text(request, "expectedSpecDigest"),
                    ChangeJson.optionalText(request, "reason"), principal);
            write(exchange, 200, result);
            return;
        }
        if (method.equals("POST") && parts.length == 5
                && (parts[4].equals("draft-cancel") || parts[4].equals("draft-retry"))) {
            JsonNode body = body(exchange);
            long version = expectedVersion(body);
            String generation = ChangeJson.text(body, "expectedGeneration");
            requireCompatibleActor(body, principal);
            authorizer.require(principal, project(id), parts[4].equals("draft-cancel")
                    ? ChangePermission.CANCEL_DRAFT : ChangePermission.RETRY_DRAFT);
            ChangeTaskView result = parts[4].equals("draft-cancel")
                    ? workflow.cancelDraft(id, version, generation, principal.subjectId(), principal.actorType())
                    : workflow.retryDraft(id, version, generation, principal.subjectId(), principal.actorType());
            write(exchange, 200, view(result, principal));
            return;
        }
        if (method.equals("POST") && parts.length == 5 && parts[4].equals("human-evidence")) {
            JsonNode body = body(exchange);
            java.util.Set<String> fields = java.util.Set.of("expectedVersion", "expectedSpecDigest", "expectedRunId",
                    "expectedHeadSha", "expectedJudgmentRevision", "criterionId", "decision", "reason", "artifactRefs", "actorId");
            body.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw new IllegalArgumentException("未知人工验收字段: " + field); });
            requireCompatibleActor(body, principal);
            authorizer.require(principal, project(id), ChangePermission.RECORD_HUMAN_EVIDENCE);
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
                    ChangeJson.text(body, "reason"), refs, principal.subjectId(), principal.actorType());
            write(exchange, 200, view(workflow.recordHumanEvidence(id, input), principal));
            return;
        }
        if (method.equals("POST") && parts.length == 5
                && (parts[4].equals("spec-decisions") || parts[4].equals("delivery-decisions"))) {
            JsonNode body = body(exchange);
            requireCompatibleActor(body, principal);
            boolean spec = parts[4].equals("spec-decisions");
            String decisionName = ChangeJson.text(body, "decision");
            ChangePermission permission = spec && decisionName.equals("SUPPLEMENT")
                    ? ChangePermission.SUPPLEMENT_SPEC
                    : spec ? ChangePermission.APPROVE_SPEC : ChangePermission.APPROVE_DELIVERY;
            authorizer.require(principal, project(id), permission);
            write(exchange, 200, view(workflow.decide(id, decision(body, spec, principal)), principal));
            return;
        }
        error(exchange, 404, "not_found", "端点不存在");
    }

    private static ChangeDecision decision(JsonNode body, boolean spec, Principal principal) {
        long expected = expectedVersion(body);
        String actor = principal.subjectId();
        String actorType = principal.actorType();
        String reason = ChangeJson.optionalText(body, "reason");
        String decision = ChangeJson.text(body, "decision");
        if (spec) {
            String digest = ChangeJson.text(body, "expectedDraftDigest");
            return switch (decision) {
                case "APPROVE" -> new ChangeDecision.ApproveSpec(expected, digest, actor, actorType, reason);
                case "REJECT" -> new ChangeDecision.RejectSpec(expected, digest, actor, actorType, reason);
                case "SUPPLEMENT" -> new ChangeDecision.SupplementSpec(expected, digest, actor, actorType,
                        ChangeJson.text(body, "supplement"));
                default -> throw new IllegalArgumentException("未知 Spec decision");
            };
        }
        String digest = ChangeJson.text(body, "expectedSpecDigest");
        String head = ChangeJson.text(body, "expectedHeadSha");
        String run = ChangeJson.text(body, "expectedRunId");
        long revision = nonNegative(body, "expectedJudgmentRevision");
        return switch (decision) {
            case "APPROVE" -> new ChangeDecision.ApproveDelivery(expected, digest, head, run, revision,
                    actor, actorType, reason);
            case "REJECT" -> new ChangeDecision.RejectDelivery(expected, digest, head, run, revision,
                    actor, actorType, reason);
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

    private ObjectNode view(ChangeTaskView view, Principal principal) {
        ObjectNode json = ChangeJson.MAPPER.valueToTree(view.task());
        json.remove("id");
        if (json.path("draftJob").isObject()) {
            ObjectNode job = (ObjectNode) json.get("draftJob");
            job.put("revision", view.task().draftJob().input().revision());
            job.remove(java.util.List.of("input", "lease"));
        }
        json.put("changeId", view.task().id().value());
        String projectId = ChangeProject.id(view.task().repository());
        json.put("projectId", projectId);
        json.set("permissions", ChangeJson.MAPPER.valueToTree(authorizer.permissions(principal, projectId)));
        json.put("judgmentRevision", view.task().judgmentRevision());
        json.set("deliveryVerdict", ChangeJson.MAPPER.valueToTree(view.task().deliveryVerdict()));
        json.put("deliveryApprovalValid", view.task().run() != null && view.task().spec() != null
                && view.task().deliveryApprovedFor(view.task().spec().digest(), view.task().run().headSha()));
        json.set("deliveryHistory", ChangeJson.MAPPER.valueToTree(view.deliveryHistory()));
        json.set("delivery", ChangeJson.MAPPER.valueToTree(view.delivery()));
        return json;
    }

    private ChangeTask authorizeTask(ChangeTaskId id, Principal principal, ChangePermission permission) {
        ChangeTask task = queries.find(id).orElseThrow(() -> new ChangeNotFoundException(id));
        authorizer.require(principal, ChangeProject.id(task.repository()), permission);
        return task;
    }

    private String project(ChangeTaskId id) {
        return ChangeProject.id(queries.find(id).orElseThrow(() -> new ChangeNotFoundException(id)).repository());
    }

    private static Principal principal(HttpExchange exchange) {
        Object value = exchange.getAttribute(RuntimeApiServer.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof Principal principal)) {
            throw new IllegalStateException("Runtime API 未建立可信 Principal");
        }
        return principal;
    }

    /** Legacy actorId is an assertion only; it never selects the actor. */
    private static void requireCompatibleActor(JsonNode body, Principal principal) {
        if (!body.has("actorId")) return;
        String supplied = ChangeJson.text(body, "actorId");
        if (!supplied.equals(principal.subjectId())) {
            throw new ChangeForbiddenException("actorId 与服务端认证主体不一致");
        }
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
