package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.runtime.api.ChangeApiHandler;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.auth.LocalPrincipalAdapter;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChangeRbacApiTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void trustedPrincipalDrivesActorProjectBoundaryAndEveryM1M2Action() throws Exception {
        Path repoA = Files.createDirectories(root.resolve("repo-a"));
        Path repoB = Files.createDirectories(root.resolve("repo-b"));
        String projectA = ChangeProject.id(new RepositoryRef(repoA.toString(), "main"));
        String projectB = ChangeProject.id(new RepositoryRef(repoB.toString(), "main"));
        var memberships = new InMemoryProjectMemberships();
        memberships.put(new ProjectMembership(projectA, "dev", Set.of(ProjectRole.DEVELOPER)));
        memberships.put(new ProjectMembership(projectA, "viewer", Set.of(ProjectRole.VIEWER)));
        memberships.put(new ProjectMembership(projectA, "approver", Set.of(ProjectRole.APPROVER)));
        memberships.put(new ProjectMembership(projectA, "admin", Set.of(ProjectRole.PROJECT_ADMIN)));
        memberships.put(new ProjectMembership(projectA, "bot", Set.of(ProjectRole.PROJECT_ADMIN)));
        memberships.put(new ProjectMembership(projectB, "outsider", Set.of(ProjectRole.VIEWER)));
        var identities = new LocalPrincipalAdapter(Map.of(
                "dev-token", human("dev"), "viewer-token", human("viewer"),
                "approver-token", human("approver"), "admin-token", human("admin"),
                "bot-token", service("bot"), "outside-token", human("outsider")));

        try (var store = new SqliteChangeStore(root.resolve("changes.db"));
             var threads = new RuntimeThreadStore(root.resolve("threads.db"));
             var toolApprovals = new ToolApprovalCoordinator(store, store, new ChangeAuthorizer(memberships))) {
            var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
            var handler = new ChangeApiHandler(workflow, store, new MockWorkItemAdapter(root, workflow),
                    new ChangeArtifactReader(root), false, new ChangeAuthorizer(memberships), toolApprovals);
            try (var server = new RuntimeApiServer(threads, value -> value, 0, identities, handler)) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                JsonNode capabilities = json(send(base, "GET", "/v1/changes/capabilities", null, "viewer-token"));
                assertTrue(capabilities.path("toolPolicyEnforced").asBoolean());
                assertTrue(capabilities.path("workerHitl").asBoolean());
                assertEquals(200, send(base, "GET", "/v1/changes/projects/" + projectA + "/tool-policy",
                        null, "viewer-token").statusCode());
                assertEquals(403, send(base, "PUT", "/v1/changes/projects/" + projectA + "/tool-policy",
                        "{\"expectedVersion\":1,\"rules\":[]}", "viewer-token").statusCode());
                assertEquals(200, send(base, "PUT", "/v1/changes/projects/" + projectA + "/tool-policy",
                        "{\"expectedVersion\":1,\"rules\":[]}", "admin-token").statusCode());
                assertEquals(401, send(base, "GET", "/v1/changes", null, null).statusCode());
                assertEquals(403, send(base, "POST", "/v1/changes", create(repoA, null), "viewer-token").statusCode());

                HttpResponse<String> created = send(base, "POST", "/v1/changes", create(repoA, null), "dev-token");
                assertEquals(201, created.statusCode(), created.body());
                JsonNode task = json(created);
                assertEquals("dev", task.path("requesterId").asText());
                assertEquals(projectA, task.path("projectId").asText());
                assertTrue(values(task.path("permissions")).contains("CANCEL_DRAFT"));
                assertFalse(values(task.path("permissions")).contains("APPROVE_SPEC"));
                assertEquals(403, send(base, "POST", "/v1/changes",
                        create(repoA, "admin"), "dev-token").statusCode());

                ChangeTaskId id = new ChangeTaskId(task.path("changeId").asText());
                ChangeTestSupport.draft(workflow, id);
                task = json(send(base, "GET", "/v1/changes/" + id.value(), null, "viewer-token"));
                String item = "/v1/changes/" + id.value();
                assertEquals(200, send(base, "GET", item + "/events", null, "viewer-token").statusCode());
                assertEquals(200, send(base, "GET", item + "/artifacts", null, "viewer-token").statusCode());
                assertEquals(0, json(send(base, "GET", "/v1/changes", null, "outside-token"))
                        .path("changes").size());
                assertEquals(403, send(base, "GET", item, null, "outside-token").statusCode());
                assertEquals(403, send(base, "GET", item + "/events", null, "outside-token").statusCode());
                assertEquals(403, send(base, "GET", item + "/artifacts", null, "outside-token").statusCode());

                String draftAction = ChangeJson.MAPPER.writeValueAsString(Map.of(
                        "expectedVersion", task.path("version").asLong(), "expectedGeneration",
                        task.path("draftJob").path("generation").asText()));
                assertEquals(403, send(base, "POST", item + "/draft-cancel", draftAction, "viewer-token").statusCode());
                String specDecision = ChangeJson.MAPPER.writeValueAsString(Map.of(
                        "decision", "APPROVE", "expectedVersion", task.path("version").asLong(),
                        "expectedDraftDigest", task.path("spec").path("digest").asText()));
                assertEquals(403, send(base, "POST", item + "/spec-decisions", specDecision, "bot-token").statusCode());
                assertEquals(200, send(base, "POST", item + "/spec-decisions", specDecision, "approver-token").statusCode());

                ChangeTask readyForTools = workflow.get(id).task();
                workflow.queueForExecution(id, readyForTools.version());
                ChangeExecutionControl.ExecutionLease execution = workflow.claimExecution(id);
                ChangeTask running = execution.task();
                PersistentToolApprovalHandler toolHandler = toolApprovals.handler(running, repoA);
                toolHandler.bindRunIdentity(new com.paicli.spec.SpecRunResult.RunIdentity(
                        "run-api", running.spec().specId(), running.spec().revision(), running.spec().digest(),
                        running.spec().lockedPath()));
                var waiting = java.util.concurrent.CompletableFuture.supplyAsync(() -> toolHandler.requestApproval(
                        com.paicli.hitl.ApprovalRequest.of("write_file",
                                "{\"path\":\"out.txt\",\"content\":\"token=do-not-leak\"}", "api test")));
                ToolApproval pending = awaitPending(store, id);
                assertFalse(pending.argumentsPreview().contains("do-not-leak"));
                assertEquals(200, send(base, "GET", item + "/tool-approvals", null, "viewer-token").statusCode());
                assertEquals(403, send(base, "GET", item + "/tool-approvals", null, "outside-token").statusCode());
                String toolDecision = ChangeJson.MAPPER.writeValueAsString(Map.of(
                        "decision", "APPROVE", "expectedPolicyVersion", pending.policyVersion(),
                        "expectedArgumentsDigest", pending.argumentsDigest(), "expectedCallId", pending.callId(),
                        "expectedRunId", pending.runId(), "expectedSpecDigest", pending.specDigest(),
                        "reason", "reviewed"));
                assertEquals(403, send(base, "POST", item + "/tool-approvals/" + pending.id() + "/decisions",
                        toolDecision, "bot-token").statusCode());
                assertEquals(200, send(base, "POST", item + "/tool-approvals/" + pending.id() + "/decisions",
                        toolDecision, "approver-token").statusCode());
                assertTrue(waiting.get(2, java.util.concurrent.TimeUnit.SECONDS).isApproved());

                String fakeHuman = "{\"expectedVersion\":0,\"expectedSpecDigest\":\"x\",\"expectedRunId\":\"x\","
                        + "\"expectedHeadSha\":\"x\",\"expectedJudgmentRevision\":0,\"criterionId\":\"x\","
                        + "\"decision\":\"PASS\",\"reason\":\"x\",\"artifactRefs\":[]}";
                assertEquals(403, send(base, "POST", item + "/human-evidence", fakeHuman, "bot-token").statusCode());
                String fakeDelivery = "{\"decision\":\"APPROVE\",\"expectedVersion\":0,"
                        + "\"expectedSpecDigest\":\"x\",\"expectedHeadSha\":\"x\",\"expectedRunId\":\"x\","
                        + "\"expectedJudgmentRevision\":0}";
                assertEquals(403, send(base, "POST", item + "/delivery-decisions", fakeDelivery, "bot-token").statusCode());

                ChangeEvent createdEvent = workflow.get(id).events().stream()
                        .filter(event -> event.type().equals("change.created")).findFirst().orElseThrow();
                assertEquals("dev", createdEvent.actorId());
                assertEquals("HUMAN", createdEvent.actorType());

                memberships.remove(projectA, "viewer");
                assertEquals(403, send(base, "GET", item, null, "viewer-token").statusCode());
                identities.revoke("dev-token");
                assertEquals(401, send(base, "GET", item, null, "dev-token").statusCode());

                HttpResponse<String> adminCreated = send(base, "POST", "/v1/changes", create(repoA, null), "admin-token");
                assertEquals(201, adminCreated.statusCode());
                JsonNode adminTask = json(adminCreated);
                ChangeTaskId adminId = new ChangeTaskId(adminTask.path("changeId").asText());
                ChangeTestSupport.draft(workflow, adminId);
                adminTask = json(send(base, "GET", "/v1/changes/" + adminId.value(), null, "admin-token"));
                String adminDecision = ChangeJson.MAPPER.writeValueAsString(Map.of(
                        "decision", "APPROVE", "expectedVersion", adminTask.path("version").asLong(),
                        "expectedDraftDigest", adminTask.path("spec").path("digest").asText()));
                assertEquals(403, send(base, "POST", "/v1/changes/" + adminId.value() + "/spec-decisions",
                        adminDecision, "admin-token").statusCode());
            }
        }
    }

    private String create(Path repository, String actorId) throws Exception {
        var body = ChangeJson.MAPPER.createObjectNode();
        body.put("idempotencyKey", java.util.UUID.randomUUID().toString()).put("title", "High change")
                .put("requirement", "Fix auth permissions");
        if (actorId != null) body.put("actorId", actorId);
        body.putObject("repository").put("path", repository.toString()).put("baseRef", "main");
        body.putObject("source").putArray("labels").add("high-risk");
        return body.toString();
    }

    private HttpResponse<String> send(String base, String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return ChangeJson.MAPPER.readTree(response.body());
    }

    private static Set<String> values(JsonNode array) {
        var values = new java.util.HashSet<String>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Principal human(String id) {
        return new Principal(id, id, PrincipalType.HUMAN, "local-test", Instant.now().plusSeconds(3600), false);
    }

    private static Principal service(String id) {
        return new Principal(id, id, PrincipalType.SERVICE, "local-test", Instant.now().plusSeconds(3600), false);
    }

    private static ToolApproval awaitPending(SqliteChangeStore store, ChangeTaskId id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var pending = store.approvals(id).stream()
                    .filter(value -> value.status() == ToolApproval.Status.PENDING).findFirst();
            if (pending.isPresent()) return pending.get();
            Thread.sleep(10);
        }
        throw new AssertionError("pending tool approval not created");
    }
}
