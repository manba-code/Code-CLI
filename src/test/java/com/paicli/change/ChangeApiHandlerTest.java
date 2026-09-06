package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.runtime.api.ChangeApiHandler;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.auth.LocalPrincipalAdapter;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;
import com.paicli.spec.SpecRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChangeApiHandlerTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void mapsAllSevenErrorStatusesAndPreservesThreadEndpoints() throws Exception {
        Path db = root.resolve("changes.db");
        try (SqliteChangeStore store = new SqliteChangeStore(db);
             MockScmAdapter scm = new MockScmAdapter(db);
             RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"))) {
            DefaultChangeWorkflow workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
            workflow.connectArtifacts(new ChangeArtifactReader(root));
            workflow.connect(id -> { }, scm, task -> task.run().headSha());
            String project = ChangeProject.id(new RepositoryRef(root.toString(), "main"));
            var memberships = new InMemoryProjectMemberships();
            memberships.put(new ProjectMembership(project, "requester", Set.of(ProjectRole.DEVELOPER, ProjectRole.APPROVER)));
            memberships.put(new ProjectMembership(project, "lead", Set.of(ProjectRole.APPROVER)));
            ChangeApiHandler handler = new ChangeApiHandler(workflow, store, new MockWorkItemAdapter(root, workflow),
                    null, false, new ChangeAuthorizer(memberships));
            var identities = new LocalPrincipalAdapter(Map.of(
                    "secret", principal("requester"), "lead-secret", principal("lead")));
            try (RuntimeApiServer server = new RuntimeApiServer(threads, prompt -> "reply:" + prompt, 0, identities, handler)) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                HttpResponse<String> unauthorized = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/changes"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(401, unauthorized.statusCode());
                for (String bad : new String[]{"{", "null", "[]", "{}", "{} {}", "{\"title\":\"a\",\"title\":\"b\"}"}) {
                    assertEquals(400, send(base, "POST", "/v1/changes", bad).statusCode());
                }
                assertEquals(404, send(base, "GET", "/v1/changes/change_000000000000", "").statusCode());
                assertEquals(404, send(base, "GET", "/v1/changes-other", "").statusCode());
                JsonNode created = create(base, "api-high", true);
                ChangeTestSupport.draft(workflow, new ChangeTaskId(created.path("changeId").asText()));
                JsonNode review = json(send(base, "GET", "/v1/changes/" + created.path("changeId").asText(), ""));
                String id = review.path("changeId").asText();
                String path = "/v1/changes/" + id;
                assertEquals(403, send(base, "POST", path + "/spec-decisions", specDecision(review, "requester")).statusCode());
                var stale = ChangeJson.MAPPER.readTree(specDecision(review, "lead"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) stale).put("expectedVersion", 0);
                assertEquals(409, send(base, "POST", path + "/spec-decisions", stale.toString(), "lead-secret").statusCode());
                assertEquals(200, send(base, "POST", path + "/spec-decisions", specDecision(review, "lead"), "lead-secret").statusCode());
                assertEquals(409, send(base, "POST", path + "/spec-decisions", specDecision(review, "lead"), "lead-secret").statusCode());
                ChangeTask queued = workflow.get(new ChangeTaskId(id)).task();
                ChangeTask needsHuman = ChangeTestSupport.finish(workflow, queued, root.resolve("runs"), SpecRunResult.Verdict.NEEDS_HUMAN);
                assertEquals(422, send(base, "POST", path + "/delivery-decisions", deliveryDecision(needsHuman)).statusCode());
                workflow.advance(needsHuman.id());
                JsonNode result = json(send(base, "GET", path, ""));
                assertEquals("pending", result.path("delivery").path("conclusion").asText());
                var events = json(send(base, "GET", path + "/events?after=0", "")).path("events");
                long cursor = events.get(events.size() - 1).path("sequence").asLong();
                assertEquals(0, json(send(base, "GET", path + "/events?after=" + cursor, "")).path("events").size());
                assertEquals(400, send(base, "GET", path + "/events?after=invalid", "").statusCode());
                assertEquals(1, json(send(base, "GET", "/v1/changes", "")).path("changes").size());
                assertEquals(200, send(base, "POST", "/v1/threads", "").statusCode());

                store.close(); // Inject a persistence outage to exercise the public error mapping.
                assertEquals(500, send(base, "GET", path, "").statusCode());
            }
        }
    }

    @Test
    void localWorkItemFixtureIsIdempotentAndCannotEscapeItsRoot() throws Exception {
        try (SqliteChangeStore store = new SqliteChangeStore(root.resolve("changes.db"));
             RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"))) {
            DefaultChangeWorkflow workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
            Path fixtures = Files.createDirectories(root.resolve("fixtures"));
            Files.writeString(fixtures.resolve("issue.json"), ChangeJson.MAPPER.writeValueAsString(Map.of(
                    "idempotencyKey", "fixture-1", "sourceType", "mock_gitlab_issue", "externalId", "LOCAL-1",
                    "title", "Local issue", "description", "Fix output", "requester", "owner",
                    "labels", java.util.List.of("high-risk"), "repository", Map.of("path", root.toString(), "baseRef", "main"))));
            MockWorkItemAdapter workItems = new MockWorkItemAdapter(fixtures, workflow);
            try (RuntimeApiServer server = new RuntimeApiServer(threads, p -> p, 0, "secret",
                    new ChangeApiHandler(workflow, store, workItems))) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                JsonNode first = json(send(base, "POST", "/v1/changes", "{\"fixture\":\"issue.json\"}"));
                JsonNode repeat = json(send(base, "POST", "/v1/changes", "{\"fixture\":\"issue.json\"}"));
                assertEquals(first.path("changeId"), repeat.path("changeId"));
                assertEquals("LOCAL-1", first.path("source").path("externalId").asText());
                assertEquals("local-user", first.path("requesterId").asText());
                assertEquals(400, send(base, "POST", "/v1/changes", "{\"fixture\":\"../issue.json\"}").statusCode());
                assertEquals(404, send(base, "POST", "/v1/changes", "{\"fixture\":\"missing.json\"}").statusCode());
                String path = "/v1/changes/" + first.path("changeId").asText();
                ChangeTestSupport.draft(workflow, new ChangeTaskId(first.path("changeId").asText()));
                first = json(send(base, "GET", path, ""));
                var supplement = (com.fasterxml.jackson.databind.node.ObjectNode) ChangeJson.MAPPER.readTree(specDecision(first, "local-user"));
                supplement.put("decision", "SUPPLEMENT");
                supplement.put("supplement", "Keep old behavior");
                JsonNode revised = json(send(base, "POST", path + "/spec-decisions", supplement.toString()));
                assertEquals("DRAFTING_SPEC", revised.path("state").asText());
                ChangeTestSupport.draft(workflow, new ChangeTaskId(first.path("changeId").asText()));
                revised = json(send(base, "GET", path, ""));
                assertEquals(2, revised.path("spec").path("revision").asInt());
                assertEquals(409, send(base, "POST", path + "/spec-decisions", specDecision(first, "local-user")).statusCode());
                var reject = (com.fasterxml.jackson.databind.node.ObjectNode) ChangeJson.MAPPER.readTree(specDecision(revised, "local-user"));
                reject.put("decision", "REJECT");
                assertEquals("REJECTED", json(send(base, "POST", path + "/spec-decisions", reject.toString())).path("state").asText());
            }
        }
    }

    private JsonNode create(String base, String key, boolean high) throws Exception {
        var body = ChangeJson.MAPPER.createObjectNode();
        body.put("idempotencyKey", key).put("title", "Local change").put("requirement", "Fix output").put("actorId", "requester");
        body.putObject("repository").put("path", root.toString()).put("baseRef", "main");
        if (high) body.putObject("source").putArray("labels").add("high-risk");
        HttpResponse<String> response = send(base, "POST", "/v1/changes", body.toString());
        assertEquals(201, response.statusCode(), response.body());
        return json(response);
    }

    private HttpResponse<String> send(String base, String method, String path, String body) throws Exception {
        return send(base, method, path, body, "secret");
    }

    private HttpResponse<String> send(String base, String method, String path, String body, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String specDecision(JsonNode task, String actor) throws Exception {
        return ChangeJson.MAPPER.writeValueAsString(Map.of("decision", "APPROVE", "expectedVersion", task.path("version").asLong(),
                "expectedDraftDigest", task.path("spec").path("digest").asText(), "actorId", actor));
    }

    static String deliveryDecision(ChangeTask task) throws Exception {
        return ChangeJson.MAPPER.writeValueAsString(Map.of("decision", "APPROVE", "expectedVersion", task.version(),
                "expectedSpecDigest", task.spec().digest(), "expectedHeadSha", task.run().headSha(), "expectedRunId", task.run().runId(),
                "expectedJudgmentRevision", task.judgmentRevision()));
    }

    private static Principal principal(String id) {
        return new Principal(id, id, PrincipalType.HUMAN, "local-test", Instant.now().plusSeconds(3600), false);
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception { return ChangeJson.MAPPER.readTree(response.body()); }
}
