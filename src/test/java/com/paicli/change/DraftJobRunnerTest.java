package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.runtime.api.*;
import com.paicli.runtime.task.DraftJobRunner;
import com.paicli.spec.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class DraftJobRunnerTest {
    @TempDir Path root;
    final HttpClient client = HttpClient.newHttpClient();

    @Test void httpReturnsIdentityBeforeGenerationFinishesAndCancelInterruptsTheCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var specs = new FileChangeSpecModule(root, input -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(); }
            catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); throw new IOException(e); }
            return generation(input);
        });
        try (var store = new SqliteChangeStore(root.resolve("changes.db"));
             var threads = new RuntimeThreadStore(root.resolve("threads.db"))) {
            var workflow = new DefaultChangeWorkflow(store, store, specs);
            try (var runner = new DraftJobRunner(workflow, store);
                 var server = new RuntimeApiServer(threads, p -> p, 0, "test-only",
                         new ChangeApiHandler(workflow, store, new MockWorkItemAdapter(root, workflow)))) {
                runner.start(); server.start();
                String base = "http://127.0.0.1:" + server.port() + "/v1/changes";
                String body = ChangeJson.MAPPER.writeValueAsString(Map.of("idempotencyKey", "hanging",
                        "title", "Async", "requirement", "Fix output", "actorId", "owner",
                        "repository", Map.of("path", root.toString(), "baseRef", "main")));
                JsonNode created = send(base, body, 201); // 2s request timeout while stub remains blocked
                assertEquals("DRAFTING_SPEC", created.path("state").asText());
                assertFalse(created.path("changeId").asText().isBlank());
                assertEquals(1, release.getCount());
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(created.path("changeId"), send(base, body, 201).path("changeId"));
                assertEquals(1, calls.get());
                String path = base + "/" + created.path("changeId").asText();
                JsonNode current = send(path, null, 200);
                assertFalse(current.path("draftJob").has("input"));
                assertFalse(current.path("draftJob").has("lease"));
                String operation = operation(current);
                send(path + "/draft-cancel", operation.replace("\"expectedVersion\":" + current.path("version").asLong(), "\"expectedVersion\":999"), 409);
                assertEquals("CANCELED", send(path + "/draft-cancel", operation, 200).path("state").asText());
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
                send(path + "/draft-cancel", operation, 409);
                send(path + "/draft-retry", operation(send(path, null, 200)), 409);
                assertEquals("CANCELED", send(path, null, 200).path("state").asText());
            } finally { release.countDown(); }
        }
    }

    @Test void uncooperativeLateResultIsDiscardedAndDoesNotOverwriteNewGeneration() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var specs = new FileChangeSpecModule(root, input -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* deliberately uncooperative */ }
                }
                exited.countDown();
            }
            return generation(input);
        });
        var store = new InMemoryChangeStore();
        var workflow = new DefaultChangeWorkflow(store, store, specs);
        var id = workflow.submit(ChangeTestSupport.request(root, "late", false));
        try (var runner = new DraftJobRunner(workflow, store, 1, 10000)) {
            runner.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var running = workflow.get(id).task();
            workflow.failDraft(id, running.draftJob(), false, "operator failure injection");
            var failed = workflow.get(id).task();
            var replacement = workflow.retryDraft(id, failed.version(), failed.draftJob().generation(), "owner").task();
            release.countDown(); assertTrue(exited.await(2, TimeUnit.SECONDS));
            await(() -> workflow.get(id).task().state() == ChangeState.SPEC_REVIEW);
            var review = workflow.get(id);
            assertEquals(replacement.draftJob().generation(), review.task().draftJob().generation());
            assertEquals(2, calls.get());
            assertEquals(1, review.events().stream().filter(e -> e.type().equals("spec.draft_generated")).count());
        } finally { release.countDown(); }
    }

    @Test void timeoutTerminatesCancelableStubAndExhaustsBoundedRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger(), interrupted = new AtomicInteger();
        var specs = new FileChangeSpecModule(root, input -> {
            calls.incrementAndGet();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.incrementAndGet(); throw new IOException(e); }
            throw new AssertionError();
        });
        var store = new InMemoryChangeStore();
        var workflow = new DefaultChangeWorkflow(store, store, specs);
        var id = workflow.submit(ChangeTestSupport.request(root, "timeout", false));
        try (var runner = new DraftJobRunner(workflow, store, 1, 100)) {
            runner.start();
            await(() -> workflow.get(id).task().state() == ChangeState.FAILED);
            await(() -> interrupted.get() == 3);
            assertEquals(3, calls.get());
            assertEquals(3, workflow.get(id).task().draftJob().attempts());
        }
    }

    @Test void contentQualificationFailureIsNotInfrastructureRetriedAndHttpRetryKeepsHistory() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var specs = new FileChangeSpecModule(root, input -> {
            if (calls.incrementAndGet() == 1) throw new ChangeSpecValidationException(java.util.List.of("secret must not leak"));
            return generation(input);
        });
        var store = new InMemoryChangeStore();
        var workflow = new DefaultChangeWorkflow(store, store, specs);
        var id = workflow.submit(ChangeTestSupport.request(root, "qualification", false));
        try (var threads = new RuntimeThreadStore(root.resolve("threads.db"));
             var runner = new DraftJobRunner(workflow, store);
             var server = new RuntimeApiServer(threads, p -> p, 0, "test-only",
                     new ChangeApiHandler(workflow, store, new MockWorkItemAdapter(root, workflow)))) {
            runner.start(); server.start();
            await(() -> workflow.get(id).task().state() == ChangeState.FAILED);
            var failed = workflow.get(id);
            assertEquals(1, failed.task().draftJob().attempts());
            assertFalse(failed.task().draftJob().error().contains("secret"));
            String path = "http://127.0.0.1:" + server.port() + "/v1/changes/" + id.value();
            var current = send(path, null, 200);
            assertEquals("DRAFTING_SPEC", send(path + "/draft-retry", operation(current), 200).path("state").asText());
            send(path + "/draft-retry", operation(current), 409);
            await(() -> workflow.get(id).task().state() == ChangeState.SPEC_REVIEW);
            assertEquals(failed.events(), workflow.get(id).events().subList(0, failed.events().size()));
            assertEquals(2, calls.get());
        }
    }

    private static SpecDraftSession.DraftGeneration generation(ChangeSpecModule.ChangeContext input) {
        return new SpecDraftSession.DraftGeneration(new ChangeSpecCodec().decode(
                ChangeTestSupport.document(input.specId(), input.revision())), SpecRunResult.LlmUsage.empty(), 0);
    }
    private static String operation(JsonNode task) throws Exception {
        return ChangeJson.MAPPER.writeValueAsString(Map.of("expectedVersion", task.path("version").asLong(),
                "expectedGeneration", task.path("draftJob").path("generation").asText(), "actorId", "owner"));
    }
    private JsonNode send(String url, String body, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer test-only");
        if (body == null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return ChangeJson.MAPPER.readTree(response.body());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "state did not converge");
    }
}
