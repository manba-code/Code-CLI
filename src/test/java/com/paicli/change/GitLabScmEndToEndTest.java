package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Local-only GitLab HTTP + real local Git remote: issue -> task -> branch -> MR -> status. */
class GitLabScmEndToEndTest {
    @TempDir Path root;

    @Test
    void importsIdempotentlyPushesAndReconcilesUnknownMrAndStatusResults() throws Exception {
        Path bare = root.resolve("remote.git");
        Path repository = root.resolve("repository");
        git(root, "init", "--bare", bare.toString());
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        Files.writeString(repository.resolve("tracked.txt"), "base\n");
        git(repository, "add", "tracked.txt");
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--no-gpg-sign", "-m", "base");
        git(repository, "remote", "add", "origin", bare.toString());
        git(repository, "push", "-u", "origin", "main");

        Path data = root.resolve("data");
        Path workRoot = root.resolve("workspaces");
        Files.createDirectories(data);
        try (FakeGitLab gitlab = new FakeGitLab(bare);
             SqliteChangeStore store = new SqliteChangeStore(data.resolve("changes.db"))) {
            GitLabSettings settings = new GitLabSettings(URI.create(gitlab.baseUrl()), "group/project", "fake-token",
                    repository, "main", "origin", Duration.ofSeconds(5));
            try (GitLabScmAdapter scm = new GitLabScmAdapter(data.resolve("changes.db"), settings)) {
                DefaultChangeWorkflow workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(data));
                workflow.connectArtifacts(new ChangeArtifactReader(data, workRoot.resolve("artifacts")));
                workflow.connect(id -> { }, scm, DeliveryHeadReader.localGit());
                GitLabWorkItemAdapter workItems = new GitLabWorkItemAdapter(settings, workflow);

                ChangeTaskId first = workItems.submit("42", "requester", "HUMAN");
                ChangeTaskId repeated = workItems.submit("42", "requester", "HUMAN");
                assertEquals(first, repeated);
                assertEquals(1, store.list().size());
                ChangeTask review = ChangeTestSupport.draft(workflow, first).task();
                assertEquals("group/project#42", review.source().externalId());
                assertEquals(List.of("backend", "priority::normal"), review.source().labels());

                ChangeTask ready = workflow.decide(first, new ChangeDecision.ApproveSpec(review.version(),
                        review.spec().digest(), "spec-lead", "reviewed")).task();
                workflow.advance(first);
                DefaultChangeWorker worker = new DefaultChangeWorker(workflow,
                        new GitWorktreeWorkspaceProvisioner(workRoot), runtime());
                worker.run(first);
                ChangeTask delivery = workflow.get(first).task();
                assertEquals(ChangeState.DELIVERY_REVIEW, delivery.state());

                MockScmAdapterTest.sql(data.resolve("changes.db"), "CREATE TRIGGER fail_gitlab_completion "
                        + "BEFORE INSERT ON change_events WHEN NEW.event_type = 'change.completed' "
                        + "BEGIN SELECT RAISE(ABORT, 'disk error'); END");
                assertThrows(IllegalStateException.class,
                        () -> workflow.decide(first, ChangeTestSupport.approveDelivery(delivery)));
                assertEquals(ChangeState.PUBLISHING, workflow.get(first).task().state());
                assertEquals(1, gitlab.mergeRequestPosts);
                assertEquals(1, gitlab.statusPosts);
                MockScmAdapterTest.sql(data.resolve("changes.db"), "DROP TRIGGER fail_gitlab_completion");
                workflow.advance(first);
                ChangeTask completed = workflow.get(first).task();
                assertEquals(ChangeState.COMPLETED, completed.state());
                assertEquals("success", scm.find(completed).orElseThrow().conclusion());
                assertEquals(1, scm.history(first).size());
                assertEquals(1, gitlab.mergeRequestPosts);
                assertEquals(1, gitlab.statusPosts);
                assertEquals(1, gitlab.mergeRequests.size());
                assertEquals(1, gitlab.statuses.size());
                assertEquals(completed.run().headSha(),
                        gitBare(bare, "rev-parse", "refs/heads/" + completed.run().branch()).trim());
                assertEquals(ready.repository(), completed.repository());
            }
        }
    }

    @Test
    void refusesRemoteHeadMismatchBeforePublishingAnyMrOrStatus() throws Exception {
        Path database = root.resolve("mismatch.db");
        try (SqliteChangeStore store = new SqliteChangeStore(database);
             FakeGitLab gitlab = new FakeGitLab(null)) {
            GitLabSettings settings = new GitLabSettings(URI.create(gitlab.baseUrl()), "group/project", "fake-token",
                    root, "main", "origin", Duration.ofSeconds(5));
            try (GitLabScmAdapter scm = new GitLabScmAdapter(database, settings, new GitLabClient(settings),
                    (ignored, branch, head) -> gitlab.branchHead = "different-head")) {
                DefaultChangeWorkflow workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
                workflow.connectArtifacts(new ChangeArtifactReader(root));
                workflow.connect(id -> { }, scm, task -> task.run().headSha());
                ChangeTask ready = ChangeTestSupport.approveSpec(workflow,
                        ChangeTestSupport.request(root, "head-mismatch", true));
                ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"),
                        com.paicli.spec.SpecRunResult.Verdict.PASSED);
                assertThrows(ChangeConflictException.class,
                        () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
                assertEquals(0, gitlab.mergeRequestPosts);
                assertEquals(0, gitlab.statusPosts);
                assertEquals(ChangeState.PUBLISHING, workflow.get(task.id()).task().state());
            }
        }
    }

    @Test
    void reportsAuthenticationAndRateLimitFailuresWithoutExposingToken() throws Exception {
        try (FakeGitLab gitlab = new FakeGitLab(null)) {
            GitLabSettings settings = new GitLabSettings(URI.create(gitlab.baseUrl()), "group/project", "fake-token",
                    root, "main", "origin", Duration.ofSeconds(5));
            GitLabClient client = new GitLabClient(settings);
            gitlab.issueStatus = 401;
            RuntimeException unauthorized = assertThrows(RuntimeException.class, () -> client.issue("42"));
            assertTrue(unauthorized.getMessage().contains("权限") || unauthorized.getMessage().contains("凭据"));
            assertFalse(unauthorized.getMessage().contains("fake-token"));
            gitlab.issueStatus = 429;
            RuntimeException limited = assertThrows(RuntimeException.class, () -> client.issue("42"));
            assertTrue(limited.getMessage().contains("限流"));
            assertFalse(limited.getMessage().contains("fake-token"));
        }
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
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    new SpecVerifier(workspace.workspaceRoot(), command ->
                            CommandExecutionResult.completed(command, 0, "local fake verifier")),
                    (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped("none"),
                    new SpecRunCoordinator.RunOptions(SpecRunCoordinator.RepairPolicy.ENABLED, attempt -> { }));
        };
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

    private static final class FakeGitLab implements AutoCloseable {
        private final HttpServer server;
        private final Path bare;
        private final List<ObjectNode> mergeRequests = new ArrayList<>();
        private final List<ObjectNode> statuses = new ArrayList<>();
        private int mergeRequestPosts;
        private int statusPosts;
        private String branchHead;
        private int issueStatus = 200;

        FakeGitLab(Path bare) throws IOException {
            this.bare = bare;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            try {
                assertEquals("fake-token", exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestMethod().equals("GET") && path.endsWith("/issues/42")) {
                    if (issueStatus != 200) { json(exchange, issueStatus, Map.of("message", "rejected")); return; }
                    json(exchange, 200, Map.of("iid", 42, "title", "Fix GitLab flow", "description", "Ship it",
                            "web_url", baseUrl() + "/group/project/-/issues/42",
                            "labels", List.of("backend", "priority::normal")));
                    return;
                }
                if (exchange.getRequestMethod().equals("GET") && path.contains("/repository/branches/")) {
                    String branch = decode(path.substring(path.indexOf("/repository/branches/") + 21));
                    String head = branchHead;
                    if (head == null && bare != null) {
                        try { head = gitBare(bare, "rev-parse", "refs/heads/" + branch).trim(); }
                        catch (Exception e) { json(exchange, 404, Map.of("message", "missing")); return; }
                    }
                    json(exchange, 200, Map.of("name", branch, "commit", Map.of("id", head)));
                    return;
                }
                if (path.endsWith("/merge_requests") && exchange.getRequestMethod().equals("GET")) {
                    json(exchange, 200, mergeRequests); return;
                }
                if (path.endsWith("/merge_requests") && exchange.getRequestMethod().equals("POST")) {
                    mergeRequestPosts++;
                    Map<String, String> form = form(exchange);
                    ObjectNode mr = ChangeJson.MAPPER.createObjectNode();
                    mr.put("iid", 7).put("source_branch", form.get("source_branch"))
                            .put("target_branch", form.get("target_branch"))
                            .put("state", "opened").put("sha", currentBranchHead(form.get("source_branch")))
                            .put("web_url", baseUrl() + "/group/project/-/merge_requests/7");
                    mergeRequests.add(mr);
                    json(exchange, 500, Map.of("message", "response lost after commit"));
                    return;
                }
                if (path.contains("/repository/commits/") && path.endsWith("/statuses")
                        && exchange.getRequestMethod().equals("GET")) {
                    json(exchange, 200, statuses); return;
                }
                if (path.contains("/statuses/") && exchange.getRequestMethod().equals("POST")) {
                    statusPosts++;
                    Map<String, String> form = form(exchange);
                    String sha = decode(path.substring(path.lastIndexOf('/') + 1));
                    ObjectNode status = ChangeJson.MAPPER.createObjectNode();
                    status.put("sha", sha).put("status", form.get("state")).put("name", form.get("name"))
                            .put("ref", form.get("ref")).put("description", form.get("description"));
                    statuses.add(status);
                    json(exchange, 500, Map.of("message", "response lost after commit"));
                    return;
                }
                json(exchange, 404, Map.of("message", "not found"));
            } catch (AssertionError error) {
                json(exchange, 500, Map.of("message", error.getMessage()));
            }
        }

        private static Map<String, String> form(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> result = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                result.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
            }
            return result;
        }

        private String currentBranchHead(String branch) {
            if (branchHead != null) return branchHead;
            try { return gitBare(bare, "rev-parse", "refs/heads/" + branch).trim(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private static void json(HttpExchange exchange, int status, Object value) throws IOException {
            byte[] body = ChangeJson.MAPPER.writeValueAsBytes(value);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }

        @Override public void close() { server.stop(0); }
    }
}
