package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Actual offline composition: loopback HTTP, SQLite, Git and Java fixture verification, no LLM/network SCM. */
class OfflineChangeDemoTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;

    @Test void webArtifactsRepairAndApprovalPublishOneMockCheck() throws Exception {
        try (var platform = OfflineChangeDemo.create(root);
             var threads = new RuntimeThreadStore(root.resolve("threads.db"));
             var server = new RuntimeApiServer(threads, p -> p, 0, "test-only-key", platform.handler())) {
            platform.start(); server.start(); base = "http://127.0.0.1:" + server.port();
            var shell = raw("GET", "/changes", null, false);
            assertEquals(200, shell.statusCode());
            assertTrue(shell.headers().firstValue("Content-Security-Policy").orElseThrow().contains("script-src 'self'"));
            assertFalse(shell.body().contains("test-only-key"));
            assertEquals(404, raw("GET", "/changes/../../pom.xml", null, false).statusCode());
            assertEquals(404, raw("GET", "/changes?key=never", null, false).statusCode());
            assertEquals(200, raw("GET", "/changes/app.js", null, false).statusCode());
            assertTrue(send("GET", "/v1/changes/capabilities", null, 200).path("offlineDemo").asBoolean());
            assertEquals(400, raw("POST", "/v1/changes", "{}", true).statusCode());
            JsonNode first = create();
            String path = "/v1/changes/" + first.path("changeId").asText();
            assertEquals(first.path("changeId"), create().path("changeId"));
            assertEquals(401, raw("GET", path + "/artifacts", null, false).statusCode());
            assertEquals(400, raw("GET", path + "/artifacts?path=../../pom.xml", null, true).statusCode());
            ObjectNode supplement = specDecision(first, "SUPPLEMENT");
            supplement.put("supplement", "保留自动取消 <img src=x onerror=alert(1)> <script>alert(2)</script>");
            send("POST", path + "/spec-decisions", supplement.toString(), 200);
            JsonNode review = await(path, "SPEC_REVIEW");
            send("POST", path + "/spec-decisions", specDecision(first, "APPROVE").toString(), 409);
            JsonNode artifacts = send("GET", path + "/artifacts?fromRevision=1&toRevision=2", null, 200);
            assertEquals(2, artifacts.path("revisions").size());
            assertTrue(artifacts.path("draft").asText().contains("<script>")); // API is data; browser must render as text.
            assertTrue(artifacts.path("revisionDiff").asText().contains("+revision: 2"));
            assertEquals(404, raw("GET", path + "/artifacts?fromRevision=1&toRevision=99", null, true).statusCode());
            send("POST", path + "/spec-decisions", specDecision(review, "APPROVE").toString(), 200);
            JsonNode delivery = await(path, "DELIVERY_REVIEW");
            assertEquals("MEDIUM", delivery.path("risk").path("level").asText());
            assertEquals("offline-demo", delivery.path("route").path("provider").asText());
            assertEquals("PASSED", delivery.path("run").path("verdict").asText());
            artifacts = send("GET", path + "/artifacts", null, 200);
            assertEquals(1, artifacts.path("result").path("metrics").path("repairCount").asInt());
            assertEquals(2, artifacts.path("verificationAttempts").size());
            assertEquals("FAIL", artifacts.path("verificationAttempts").get(0).path("verifierResults").get(0).path("status").asText());
            assertEquals("PASS", artifacts.path("verificationAttempts").get(1).path("verifierResults").get(0).path("status").asText());
            assertEquals(4, artifacts.path("evidence").size());
            assertTrue(artifacts.path("codeDiff").asText().contains("hours > 24"));
            assertTrue(delivery.path("delivery").isNull());
            String approval = deliveryDecision(delivery, "APPROVE");
            JsonNode done = send("POST", path + "/delivery-decisions", approval, 200);
            assertEquals("COMPLETED", done.path("state").asText());
            assertEquals("success", done.path("delivery").path("conclusion").asText());
            send("POST", path + "/delivery-decisions", approval, 409);
            assertTrue(Files.readString(root.resolve("fixture-repository/payment/RefundPolicy.java")).contains("hours > 48"));
            assertEquals(200, raw("POST", "/v1/threads", "", true).statusCode());
        }
        Path db = root.resolve("platform/changes.db");
        assertEquals(1, MockScmAdapterTest.count(db, "runtime_tasks"));
        assertEquals(1, MockScmAdapterTest.count(db, "mock_pull_requests"));
        assertEquals(1, MockScmAdapterTest.count(db, "mock_pr_checks"));
    }

    @Test void specRejectionNeverStartsWorkerAndDeliveryRejectionNeverPublishesSuccess() throws Exception {
        for (boolean delivery : new boolean[]{false, true}) {
            Path runRoot = root.resolve(delivery ? "delivery" : "spec");
            try (var platform = OfflineChangeDemo.create(runRoot);
                 var threads = new RuntimeThreadStore(runRoot.resolve("threads.db"));
                 var server = new RuntimeApiServer(threads, p -> p, 0, "test-only-key", platform.handler())) {
                platform.start(); server.start(); base = "http://127.0.0.1:" + server.port();
                JsonNode review = create(); String path = "/v1/changes/" + review.path("changeId").asText();
                if (delivery) {
                    send("POST", path + "/spec-decisions", specDecision(review, "APPROVE").toString(), 200);
                    JsonNode verified = await(path, "DELIVERY_REVIEW");
                    JsonNode rejected = send("POST", path + "/delivery-decisions", deliveryDecision(verified, "REJECT"), 200);
                    assertEquals("REJECTED", rejected.path("state").asText());
                    assertTrue(rejected.path("delivery").isNull());
                } else {
                    assertEquals("REJECTED", send("POST", path + "/spec-decisions", specDecision(review, "REJECT").toString(), 200).path("state").asText());
                    assertEquals(0, MockScmAdapterTest.count(runRoot.resolve("platform/changes.db"), "runtime_tasks"));
                }
                assertEquals(0, MockScmAdapterTest.count(runRoot.resolve("platform/changes.db"), "mock_pr_checks"));
            }
        }
    }

    private JsonNode create() throws Exception {
        JsonNode created = send("POST", "/v1/changes", "{\"fixture\":\"offline-refund.json\"}", 201);
        return await("/v1/changes/" + created.path("changeId").asText(), "SPEC_REVIEW");
    }
    private ObjectNode specDecision(JsonNode task, String decision) throws Exception {
        ObjectNode body = (ObjectNode) ChangeJson.MAPPER.readTree(ChangeApiHandlerTest.specDecision(task, "techlead"));
        return body.put("decision", decision);
    }
    private String deliveryDecision(JsonNode task, String decision) throws Exception {
        return ChangeJson.MAPPER.writeValueAsString(Map.of("decision", decision, "actorId", "techlead",
                "expectedVersion", task.path("version").asLong(), "expectedSpecDigest", task.path("spec").path("digest").asText(),
                "expectedRunId", task.path("run").path("runId").asText(), "expectedJudgmentRevision", task.path("judgmentRevision").asLong(),
                "expectedHeadSha", task.path("run").path("headSha").asText()));
    }
    private JsonNode await(String path, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = send("GET", path, null, 200);
            if (state.equals(last.path("state").asText())) return last;
            Thread.sleep(50);
        }
        fail("Expected " + state + ": " + last); return null;
    }
    private JsonNode send(String method, String path, String body, int status) throws Exception {
        var response = raw(method, path, body, true);
        assertEquals(status, response.statusCode(), response.body());
        return ChangeJson.MAPPER.readTree(response.body());
    }
    private HttpResponse<String> raw(String method, String path, String body, boolean auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8));
        if (auth) request.header("Authorization", "Bearer test-only-key");
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "":body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
