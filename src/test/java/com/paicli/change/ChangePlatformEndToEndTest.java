package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.config.PaiCliConfig;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.spec.*;
import com.paicli.tool.CommandExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实本地 Git、SQLite、HTTP、SpecExecutionEngine/Verifier/repair；仅 ReAct 和 command 执行器使用本地替身。 */
class ChangePlatformEndToEndTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger runtimes = new AtomicInteger();
    private final AtomicInteger repairs = new AtomicInteger();

    @Test
    void httpFixtureApprovalRepairDeliveryAndReplayUseOneJobAndOnePullRequest() throws Exception {
        Path repo = repository();
        Path data = root.resolve("data");
        Path fixtures = Files.createDirectories(data.resolve("fixtures"));
        Files.writeString(fixtures.resolve("local.json"), ChangeJson.MAPPER.writeValueAsString(Map.of(
                "idempotencyKey", "fixture-full", "sourceType", "mock_gitlab_issue", "externalId", "LOCAL-42",
                "title", "Local repair", "description", "Fix output", "requester", "requester",
                "repository", Map.of("path", repo.toString(), "baseRef", "main"))));
        try (ChangePlatform platform = platform(data, false);
             RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"));
             RuntimeApiServer api = new RuntimeApiServer(threads, p -> p, 0, "secret", platform.handler())) {
            platform.start(); api.start();
            String base = "http://127.0.0.1:" + api.port();
            JsonNode review = send(base, "POST", "/v1/changes", "{\"fixture\":\"local.json\"}", 201);
            String id = review.path("changeId").asText();
            String path = "/v1/changes/" + id;
            assertEquals(id, send(base, "POST", "/v1/changes", "{\"fixture\":\"local.json\"}", 201).path("changeId").asText());
            review = await(base, path, "SPEC_REVIEW");
            String decision = ChangeApiHandlerTest.specDecision(review, "lead");
            send(base, "POST", path + "/spec-decisions", decision, 200);
            send(base, "POST", path + "/spec-decisions", decision, 409);
            JsonNode delivery = await(base, path, "DELIVERY_REVIEW");
            assertEquals("PASSED", delivery.path("run").path("verdict").asText());
            assertTrue(delivery.path("delivery").isNull());
            assertEquals(1, repairs.get());
            Path evidence = Path.of(delivery.path("run").path("evidencePath").asText());
            JsonNode result = ChangeJson.MAPPER.readTree(Files.readString(evidence.resolve("result.json")));
            assertEquals(2, result.path("verificationAttempts").size());
            String approval = deliveryDecision(delivery);
            JsonNode completed = send(base, "POST", path + "/delivery-decisions", approval, 200);
            assertEquals("COMPLETED", completed.path("state").asText());
            assertEquals("success", completed.path("delivery").path("conclusion").asText());
            assertEquals(delivery.path("run").path("headSha"), completed.path("delivery").path("headSha"));
            send(base, "POST", path + "/delivery-decisions", approval, 409);
            assertEquals(1, runtimes.get());
            assertEquals("user dirty\n", Files.readString(repo.resolve("tracked.txt")));
            assertFalse(Files.exists(repo.resolve("output.txt")));
            assertEquals("correct", git(repo, "show", delivery.path("run").path("headSha").asText() + ":output.txt").trim());
            JsonNode events = send(base, "GET", path + "/events?after=0", "", 200).path("events");
            assertEquals("change.completed", events.get(events.size() - 1).path("type").asText());
        }
        assertEquals(1, MockScmAdapterTest.count(data.resolve("changes.db"), "runtime_tasks"));
        assertEquals(1, MockScmAdapterTest.count(data.resolve("changes.db"), "mock_pull_requests"));
        assertEquals(1, MockScmAdapterTest.count(data.resolve("changes.db"), "mock_pr_checks"));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("changes.db"));
             var s = connection.createStatement(); var r = s.executeQuery("SELECT prompt, job_type, reference_id FROM runtime_tasks")) {
            assertTrue(r.next());
            assertEquals("", r.getString("prompt"));
            assertEquals("change.execute", r.getString("job_type"));
            assertTrue(r.getString("reference_id").startsWith("change_"));
        }
    }

    @Test
    void queuedApprovalSurvivesRestartAndLowRiskExemptionPublishesAutomatically() throws Exception {
        Path repo = repository();
        Path data = root.resolve("restart");
        String path;
        try (ChangePlatform platform = platform(data, true);
             RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"));
             RuntimeApiServer api = new RuntimeApiServer(threads, p -> p, 0, "secret", platform.handler())) {
            api.start(); // Deliberately leave the queue stopped, then restart after persisted approval.
            String base = "http://127.0.0.1:" + api.port();
            JsonNode review = send(base, "POST", "/v1/changes", request(repo, "restart"), 201);
            path = "/v1/changes/" + review.path("changeId").asText();
            try (SqliteChangeStore stored = new SqliteChangeStore(data.resolve("changes.db"))) {
                ChangeTestSupport.draft(new DefaultChangeWorkflow(stored, stored, ChangeTestSupport.specs(data)),
                        new ChangeTaskId(review.path("changeId").asText()));
            }
            review = send(base, "GET", path, "", 200);
            assertEquals("QUEUED", send(base, "POST", path + "/spec-decisions",
                    ChangeApiHandlerTest.specDecision(review, "lead"), 200).path("state").asText());
            assertEquals(0, runtimes.get());
        }
        try (ChangePlatform platform = platform(data, true);
             RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"));
             RuntimeApiServer api = new RuntimeApiServer(threads, p -> p, 0, "secret", platform.handler())) {
            platform.start(); api.start();
            String base = "http://127.0.0.1:" + api.port();
            JsonNode completed = await(base, path, "COMPLETED");
            assertEquals("success", completed.path("delivery").path("conclusion").asText());
            assertTrue(completed.path("deliveryApproval").isNull());
            assertEquals(1, runtimes.get());
        }
    }

    @Test
    void draftSchedulingSurvivesRestartBeforeConsumerStarts() throws Exception {
        Path repo = repository(), data = root.resolve("draft-restart");
        String path, generation;
        try (var platform = platform(data, false);
             var threads = new RuntimeThreadStore(root.resolve("threads.db"));
             var api = new RuntimeApiServer(threads, p -> p, 0, "secret", platform.handler())) {
            api.start(); // No consumer: commit intent, then stop the whole composition.
            String base = "http://127.0.0.1:" + api.port();
            JsonNode created = send(base, "POST", "/v1/changes", request(repo, "draft-restart"), 201);
            assertEquals("DRAFTING_SPEC", created.path("state").asText());
            path = "/v1/changes/" + created.path("changeId").asText();
            generation = created.path("draftJob").path("generation").asText();
        }
        try (var platform = platform(data, false);
             var threads = new RuntimeThreadStore(root.resolve("threads.db"));
             var api = new RuntimeApiServer(threads, p -> p, 0, "secret", platform.handler())) {
            platform.start(); api.start();
            String base = "http://127.0.0.1:" + api.port();
            JsonNode review = await(base, path, "SPEC_REVIEW");
            assertEquals(generation, review.path("draftJob").path("generation").asText());
            assertEquals(1, review.path("draftJob").path("attempts").asInt());
            assertEquals(0, runtimes.get());
        }
    }

    private ChangePlatform platform(Path data, boolean exempt) throws Exception {
        PaiCliConfig config = new PaiCliConfig();
        if (exempt) {
            var low = new PaiCliConfig.PaiChangeRouteConfig();
            low.setDeliveryApprovalRequired(false);
            config.getPaiChange().setRoutes(Map.of("LOW", low));
        }
        return new ChangePlatform(data, data.resolve("fixtures"), ChangeTestSupport.specs(data), config,
                new GitWorktreeWorkspaceProvisioner(root.resolve("workspaces")), runtime(), DeliveryHeadReader.localGit());
    }

    private ChangeWorkerRuntimeFactory runtime() {
        return (task, workspace) -> {
            runtimes.incrementAndGet();
            SpecDraftSession unused = new SpecDraftSession(request -> { throw new AssertionError("no drafting in worker"); },
                    document -> { throw new AssertionError("no terminal approval"); });
            return new SpecRunCoordinator(workspace.workspaceRoot(), workspace.evidenceRoot().resolve("runs"),
                    unused, request -> request,
                    (phase, input, spec) -> {
                        try {
                            boolean repair = phase == SpecRunCoordinator.ReActPhase.REPAIR;
                            if (repair) repairs.incrementAndGet();
                            Files.writeString(workspace.workspaceRoot().resolve("output.txt"), repair ? "correct" : "wrong");
                            return SpecRunCoordinator.ReActExecutionResult.completed("local execution");
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    },
                    new SpecVerifier(workspace.workspaceRoot(), command -> {
                        try {
                            return CommandExecutionResult.completed(command,
                                    Files.readString(workspace.workspaceRoot().resolve("output.txt")).equals("correct") ? 0 : 1,
                                    "local check");
                        } catch (java.io.IOException e) { return CommandExecutionResult.startError(command, e.getMessage()); }
                    }),
                    (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped("no human criterion"),
                    new SpecRunCoordinator.RunOptions(task.route().repairEnabled()
                            ? SpecRunCoordinator.RepairPolicy.ENABLED : SpecRunCoordinator.RepairPolicy.DISABLED, attempt -> { }));
        };
    }

    private Path repository() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("tracked.txt"), "committed\n");
        git(repo, "add", "tracked.txt");
        git(repo, "-c", "user.name=Local Test", "-c", "user.email=test@example.invalid", "commit", "--no-gpg-sign", "-m", "initial");
        Files.writeString(repo.resolve("tracked.txt"), "user dirty\n");
        return repo;
    }

    private static String request(Path repo, String key) throws Exception {
        return ChangeJson.MAPPER.writeValueAsString(Map.of("idempotencyKey", key, "title", "Local fix",
                "requirement", "Fix output", "actorId", "requester", "repository", Map.of("path", repo.toString(), "baseRef", "main")));
    }

    private static String deliveryDecision(JsonNode task) throws Exception {
        return ChangeJson.MAPPER.writeValueAsString(Map.of("decision", "APPROVE", "actorId", "lead",
                "expectedVersion", task.path("version").asLong(), "expectedSpecDigest", task.path("spec").path("digest").asText(),
                "expectedRunId", task.path("run").path("runId").asText(), "expectedJudgmentRevision", task.path("judgmentRevision").asLong(),
                "expectedHeadSha", task.path("run").path("headSha").asText()));
    }

    private JsonNode await(String base, String path, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = send(base, "GET", path, "", 200);
            if (last.path("state").asText().equals(state)) return last;
            Thread.sleep(30);
        }
        fail("Expected " + state + ", got " + last + " events=" + send(base, "GET", path + "/events", "", 200));
        return null;
    }

    private JsonNode send(String base, String method, String path, String body, int expected) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer secret").method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        return ChangeJson.MAPPER.readTree(response.body());
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "git timeout");
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), output);
        return output;
    }
}
