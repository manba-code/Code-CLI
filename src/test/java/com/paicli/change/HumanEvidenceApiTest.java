package com.paicli.change;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static com.paicli.spec.SpecRunResult.HumanDecision.*;
import static com.paicli.spec.SpecRunResult.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class HumanEvidenceApiTest {
    @TempDir Path root;
    @Test void authenticatedEvidenceApiRejectsStaleInvalidAndForeignReferencesAndExposesHistory() throws Exception {
        Path db = root.resolve("changes.db");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db);
             var threads = new RuntimeThreadStore(root.resolve("threads.db"))) {
            var workflow = new DefaultChangeWorkflow(store, store, HumanEvidenceTestSupport.specs(root));
            workflow.connect(id -> {}, scm, task -> task.run().headSha());
            var handler = new ChangeApiHandler(workflow, store, new MockWorkItemAdapter(root, workflow), new ChangeArtifactReader(root), true);
            var task = HumanEvidenceTestSupport.finished(workflow, root, "http", true, NEEDS_HUMAN, "PASS");
            var input = HumanEvidenceTestSupport.input(task, "AC-H1", PASS);
            String body = ChangeJson.MAPPER.writeValueAsString(input);
            try (var server = new RuntimeApiServer(threads, p -> p, 0, "m2-local-test", handler)) {
                server.start(); String base = "http://127.0.0.1:" + server.port() + "/v1/changes/" + task.id().value();
                assertEquals(401, send(base + "/human-evidence", body, "wrong").statusCode());
                for (String field : List.of("expectedVersion", "expectedRunId", "expectedJudgmentRevision", "expectedSpecDigest", "expectedHeadSha", "reason")) {
                    var invalid = (ObjectNode) ChangeJson.MAPPER.readTree(body); invalid.remove(field);
                    assertEquals(400, send(base + "/human-evidence", invalid.toString(), "m2-local-test").statusCode(), field);
                }
                var invalid = (ObjectNode) ChangeJson.MAPPER.readTree(body); invalid.put("path", "/etc/passwd");
                assertEquals(400, send(base + "/human-evidence", invalid.toString(), "m2-local-test").statusCode());
                invalid.remove("path"); invalid.putArray("artifactRefs").add("https://example.com");
                assertEquals(422, send(base + "/human-evidence", invalid.toString(), "m2-local-test").statusCode());
                invalid.putArray("artifactRefs").add("code-diff"); invalid.put("criterionId", "AC-1");
                assertEquals(422, send(base + "/human-evidence", invalid.toString(), "m2-local-test").statusCode());
                var saved = send(base + "/human-evidence", body, "m2-local-test");
                assertEquals(200, saved.statusCode(), saved.body());
                var detail = ChangeJson.MAPPER.readTree(saved.body());
                assertEquals(1, detail.path("judgmentRevision").asLong());
                assertEquals("NEEDS_HUMAN", detail.path("deliveryVerdict").asText());
                assertEquals(1, detail.path("humanReview").path("entries").size());
                assertEquals(409, send(base + "/human-evidence", body, "m2-local-test").statusCode());
                task = workflow.get(task.id()).task();
                var approval = (ObjectNode) ChangeJson.MAPPER.readTree(ChangeApiHandlerTest.deliveryDecision(task));
                approval.remove("expectedRunId");
                assertEquals(400, send(base + "/delivery-decisions", approval.toString(), "m2-local-test").statusCode());
                assertEquals(422, send(base + "/delivery-decisions", ChangeApiHandlerTest.deliveryDecision(task), "m2-local-test").statusCode());
                var artifacts = send(base + "/artifacts", null, "m2-local-test");
                assertEquals(200, artifacts.statusCode());
                var artifactJson = ChangeJson.MAPPER.readTree(artifacts.body());
                assertEquals(5, artifactJson.path("artifactRefs").size());
                assertEquals("NEEDS_HUMAN", artifactJson.path("result").path("verdict").asText());
                assertEquals(1, artifactJson.path("humanReview").path("entries").size());
            }
        }
    }
    private HttpResponse<String> send(String uri, String body, String key) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .method(body == null ? "GET" : "POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
