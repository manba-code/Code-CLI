package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.spec.SpecDraftSession;
import com.paicli.spec.SpecRunCoordinator;
import com.paicli.spec.SpecVerifier;
import com.paicli.tool.CommandExecutionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Loopback GitHub HTTP + real local Git remote: issue -> task -> branch -> PR -> commit status. */
class GitHubScmEndToEndTest {
    @TempDir Path root;

    @Test
    void importsIdempotentlyPushesAndReconcilesUnknownPrStatusAndLocalCompletionResults() throws Exception {
        Path bare = root.resolve("remote.git");
        Path repository = root.resolve("repository");
        initializeGit(bare, repository);
        Path data = Files.createDirectories(root.resolve("data"));
        Path workRoot = root.resolve("workspaces");
        try (FakeGitHub github = new FakeGitHub(bare);
             SqliteChangeStore store = new SqliteChangeStore(data.resolve("changes.db"))) {
            github.unknownResultDelayMillis = 250;
            GitHubSettings settings = new GitHubSettings(URI.create(github.baseUrl()), "example", "repository",
                    "fake-token", repository, "main", "origin", Duration.ofMillis(100));
            try (GitHubScmAdapter scm = new GitHubScmAdapter(data.resolve("changes.db"), settings)) {
                DefaultChangeWorkflow workflow = workflow(store, data, workRoot, scm);
                GitHubWorkItemAdapter workItems = new GitHubWorkItemAdapter(settings, workflow);

                ChangeTaskId first = workItems.submit("42", "requester", "HUMAN");
                assertEquals(first, workItems.submit("42", "requester", "HUMAN"));
                assertEquals(1, store.list().size());
                ChangeTask review = ChangeTestSupport.draft(workflow, first).task();
                assertEquals("example/repository#42", review.source().externalId());
                assertEquals(List.of("backend", "priority::normal"), review.source().labels());

                ChangeTask ready = workflow.decide(first, new ChangeDecision.ApproveSpec(review.version(),
                        review.spec().digest(), "spec-lead", "reviewed")).task();
                workflow.advance(first);
                new DefaultChangeWorker(workflow, new GitWorktreeWorkspaceProvisioner(workRoot), runtime()).run(first);
                ChangeTask delivery = workflow.get(first).task();
                assertEquals(ChangeState.DELIVERY_REVIEW, delivery.state());

                MockScmAdapterTest.sql(data.resolve("changes.db"), "CREATE TRIGGER fail_github_completion "
                        + "BEFORE INSERT ON change_events WHEN NEW.event_type = 'change.completed' "
                        + "BEGIN SELECT RAISE(ABORT, 'disk error'); END");
                assertThrows(IllegalStateException.class,
                        () -> workflow.decide(first, ChangeTestSupport.approveDelivery(delivery)));
                assertEquals(ChangeState.PUBLISHING, workflow.get(first).task().state());
                assertEquals(1, github.pullRequestPosts);
                assertEquals(1, github.statusPosts);
                MockScmAdapterTest.sql(data.resolve("changes.db"), "DROP TRIGGER fail_github_completion");
                workflow.advance(first);

                ChangeTask completed = workflow.get(first).task();
                assertEquals(ChangeState.COMPLETED, completed.state());
                assertEquals("success", scm.find(completed).orElseThrow().conclusion());
                assertEquals(1, scm.history(first).size());
                assertEquals(1, github.pullRequestPosts);
                assertEquals(1, github.statusPosts);
                assertEquals(1, github.pullRequests.size());
                assertEquals(1, github.statuses.size());
                assertEquals(completed.run().headSha(),
                        gitBare(bare, "rev-parse", "refs/heads/" + completed.run().branch()).trim());
                assertEquals(ready.repository(), completed.repository());
                assertFalse(store.events(first).toString().contains("fake-token"));
            }
        }
    }

    @Test
    void refusesHeadMismatchAndClosedPullWithoutPublishingStatus() throws Exception {
        Path database = root.resolve("mismatch.db");
        try (SqliteChangeStore store = new SqliteChangeStore(database);
             FakeGitHub github = new FakeGitHub(null)) {
            GitHubSettings settings = settings(github, root);
            try (GitHubScmAdapter scm = new GitHubScmAdapter(database, settings, new GitHubClient(settings),
                    (ignored, branch, head) -> github.branchHead = "different-head")) {
                DefaultChangeWorkflow workflow = workflow(store, root, root.resolve("artifacts"), scm);
                ChangeTask task = finishedTask(workflow, "head-mismatch");
                assertThrows(ChangeConflictException.class,
                        () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
                assertEquals(0, github.pullRequestPosts);
                assertEquals(0, github.statusPosts);
                assertEquals(ChangeState.PUBLISHING, workflow.get(task.id()).task().state());
            }
        }

        Path closedDatabase = root.resolve("closed.db");
        try (SqliteChangeStore store = new SqliteChangeStore(closedDatabase);
             FakeGitHub github = new FakeGitHub(null)) {
            GitHubSettings settings = settings(github, root);
            try (GitHubScmAdapter scm = new GitHubScmAdapter(closedDatabase, settings, new GitHubClient(settings),
                    (ignored, branch, head) -> github.branchHead = head)) {
                DefaultChangeWorkflow workflow = workflow(store, root, root.resolve("closed-artifacts"), scm);
                ChangeTask task = finishedTask(workflow, "closed-pr");
                github.pullRequests.add(github.pull(task.run().branch(), task.repository().baseRef(),
                        task.run().headSha(), "closed", false));
                assertThrows(ChangeConflictException.class,
                        () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
                assertEquals(0, github.pullRequestPosts, "closed PR must not be treated as reusable or blindly replaced");
                assertEquals(0, github.statusPosts);
                assertEquals(ChangeState.PUBLISHING, workflow.get(task.id()).task().state());
            }
        }
    }

    @Test
    void reportsRemoteFailuresAndMalformedResponsesWithoutExposingToken() throws Exception {
        try (FakeGitHub github = new FakeGitHub(null)) {
            GitHubSettings settings = settings(github, root);
            GitHubClient client = new GitHubClient(settings);
            github.issueStatus = 401;
            RuntimeException unauthorized = assertThrows(RuntimeException.class, () -> client.issue("42"));
            assertTrue(unauthorized.getMessage().contains("权限") || unauthorized.getMessage().contains("凭据"));
            assertFalse(unauthorized.getMessage().contains("fake-token"));
            github.issueStatus = 429;
            RuntimeException limited = assertThrows(RuntimeException.class, () -> client.issue("42"));
            assertTrue(limited.getMessage().contains("限流"));
            assertFalse(limited.getMessage().contains("fake-token"));
            github.issueStatus = 200;
            github.branchHead = "";
            RuntimeException malformed = assertThrows(RuntimeException.class, () -> client.branchHead("main"));
            assertTrue(malformed.getMessage().contains("object.sha"));
            assertFalse(malformed.getMessage().contains("fake-token"));
        }
    }

    private DefaultChangeWorkflow workflow(SqliteChangeStore store, Path data, Path artifacts, ScmAdapter scm)
            throws IOException {
        DefaultChangeWorkflow workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(data));
        workflow.connectArtifacts(new ChangeArtifactReader(data, artifacts));
        workflow.connect(id -> { }, scm, task -> task.run().headSha());
        return workflow;
    }

    private ChangeTask finishedTask(DefaultChangeWorkflow workflow, String key) throws Exception {
        ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, key, true));
        return ChangeTestSupport.finish(workflow, ready, root.resolve("runs-" + key),
                com.paicli.spec.SpecRunResult.Verdict.PASSED);
    }

    private GitHubSettings settings(FakeGitHub github, Path repository) {
        return new GitHubSettings(URI.create(github.baseUrl()), "example", "repository", "fake-token",
                repository, "main", "origin", Duration.ofSeconds(5));
    }

    private ChangeWorkerRuntimeFactory runtime() {
        return (task, workspace) -> {
            SpecDraftSession unused = new SpecDraftSession(request -> { throw new AssertionError(); },
                    document -> { throw new AssertionError(); });
            return new SpecRunCoordinator(workspace.workspaceRoot(), workspace.evidenceRoot().resolve("runs"), unused,
                    request -> request,
                    (phase, input, spec) -> {
                        try {
                            Files.writeString(workspace.workspaceRoot().resolve("output.txt"), "correct");
                            return SpecRunCoordinator.ReActExecutionResult.completed("local fake execution");
                        } catch (IOException e) { throw new IllegalStateException(e); }
                    },
                    new SpecVerifier(workspace.workspaceRoot(), command ->
                            CommandExecutionResult.completed(command, 0, "local fake verifier")),
                    (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped("none"),
                    new SpecRunCoordinator.RunOptions(SpecRunCoordinator.RepairPolicy.ENABLED, attempt -> { }));
        };
    }

    private static void initializeGit(Path bare, Path repository) throws Exception {
        git(repository.getParent(), "init", "--bare", bare.toString());
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        Files.writeString(repository.resolve("tracked.txt"), "base\n");
        git(repository, "add", "tracked.txt");
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--no-gpg-sign", "-m", "base");
        git(repository, "remote", "add", "origin", bare.toString());
        git(repository, "push", "-u", "origin", "main");
    }

    private static void git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }

    private static String gitBare(Path bare, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir", bare.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static final class FakeGitHub implements AutoCloseable {
        private final HttpServer server;
        private final Path bare;
        private final List<ObjectNode> pullRequests = Collections.synchronizedList(new ArrayList<>());
        private final List<ObjectNode> statuses = Collections.synchronizedList(new ArrayList<>());
        private int pullRequestPosts;
        private int statusPosts;
        private String branchHead;
        private int issueStatus = 200;
        private long unknownResultDelayMillis;

        FakeGitHub(Path bare) throws IOException {
            this.bare = bare;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                assertEquals("Bearer fake-token", exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                assertFalse(new String(requestBody, StandardCharsets.UTF_8).contains("fake-token"));
                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestMethod().equals("GET") && path.endsWith("/issues/42")) {
                    if (issueStatus != 200) { json(exchange, issueStatus, Map.of("message", "rejected fake-token")); return; }
                    json(exchange, 200, Map.of("number", 42, "title", "Fix GitHub flow", "body", "Ship it",
                            "html_url", baseUrl() + "/example/repository/issues/42",
                            "labels", List.of(Map.of("name", "backend"), Map.of("name", "priority::normal"))));
                    return;
                }
                if (exchange.getRequestMethod().equals("GET") && path.contains("/git/ref/heads/")) {
                    String branch = decode(path.substring(path.indexOf("/git/ref/heads/") + 15));
                    String head = branchHead;
                    if (head == null && bare != null) {
                        try { head = gitBare(bare, "rev-parse", "refs/heads/" + branch).trim(); }
                        catch (Exception e) { json(exchange, 404, Map.of("message", "missing")); return; }
                    }
                    json(exchange, 200, Map.of("ref", "refs/heads/" + branch, "object", Map.of("sha", head)));
                    return;
                }
                if (path.endsWith("/pulls") && exchange.getRequestMethod().equals("GET")) {
                    json(exchange, 200, pullRequests); return;
                }
                if (path.endsWith("/pulls") && exchange.getRequestMethod().equals("POST")) {
                    pullRequestPosts++;
                    JsonNode body = ChangeJson.MAPPER.readTree(requestBody);
                    ObjectNode pull = pull(body.path("head").asText(), body.path("base").asText(),
                            currentBranchHead(body.path("head").asText()), "open", false);
                    pullRequests.add(pull);
                    delayUnknownResult();
                    json(exchange, 500, Map.of("message", "response lost after commit"));
                    return;
                }
                if (path.contains("/commits/") && path.endsWith("/statuses")
                        && exchange.getRequestMethod().equals("GET")) {
                    json(exchange, 200, statuses); return;
                }
                if (path.contains("/statuses/") && exchange.getRequestMethod().equals("POST")) {
                    statusPosts++;
                    JsonNode body = ChangeJson.MAPPER.readTree(requestBody);
                    ObjectNode status = ChangeJson.MAPPER.createObjectNode();
                    status.put("sha", decode(path.substring(path.lastIndexOf('/') + 1)))
                            .put("state", body.path("state").asText()).put("context", body.path("context").asText())
                            .put("description", body.path("description").asText())
                            .put("target_url", body.path("target_url").asText());
                    statuses.add(status);
                    delayUnknownResult();
                    json(exchange, 500, Map.of("message", "response lost after commit"));
                    return;
                }
                json(exchange, 404, Map.of("message", "not found"));
            } catch (AssertionError error) {
                json(exchange, 500, Map.of("message", "assertion failed"));
            }
        }

        ObjectNode pull(String branch, String base, String head, String state, boolean merged) {
            ObjectNode pull = ChangeJson.MAPPER.createObjectNode();
            pull.put("number", pullRequests.size() + 7).put("state", state).put("merged", merged)
                    .put("html_url", baseUrl() + "/example/repository/pull/" + (pullRequests.size() + 7));
            pull.putObject("head").put("ref", branch).put("sha", head)
                    .putObject("repo").put("full_name", "example/repository");
            pull.putObject("base").put("ref", base);
            return pull;
        }

        private String currentBranchHead(String branch) {
            if (branchHead != null) return branchHead;
            try { return gitBare(bare, "rev-parse", "refs/heads/" + branch).trim(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }

        private void delayUnknownResult() {
            if (unknownResultDelayMillis <= 0) return;
            try { Thread.sleep(unknownResultDelayMillis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }

        private static void json(HttpExchange exchange, int status, Object value) throws IOException {
            byte[] body = ChangeJson.MAPPER.writeValueAsBytes(value);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }

        @Override public void close() { server.stop(0); }
    }
}
