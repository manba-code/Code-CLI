package com.paicli.change;

import com.paicli.runtime.task.DurableTask;
import com.paicli.runtime.task.DurableTaskManager;
import com.paicli.runtime.task.TaskStatus;
import com.paicli.runtime.task.WorkerJob;
import com.paicli.spec.ChangeSpecModule;
import com.paicli.spec.ChangeSpecCodec;
import com.paicli.spec.SpecExecutionEngine;
import com.paicli.spec.SpecRunResult;
import com.paicli.spec.WorkspaceChangeTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DefaultChangeWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void workerJobsLoadContextByChangeIdAndUseFreshRuntimeAndWorkspace() throws Exception {
        InMemoryChangeStore store = new InMemoryChangeStore();
        DefaultChangeWorkflow workflow = workflow(store);
        ChangeTask first = ready(workflow, "worker-one");
        ChangeTask second = ready(workflow, "worker-two");
        FakeWorkspaceProvisioner workspaces = new FakeWorkspaceProvisioner(tempDir);
        AtomicInteger runtimeCount = new AtomicInteger();
        java.util.Set<SpecExecutionEngine> engines = ConcurrentHashMap.newKeySet();
        ChangeWorkerRuntimeFactory runtimes = (task, workspace) -> {
            int number = runtimeCount.incrementAndGet();
            SpecExecutionEngine engine = context -> {
                context.verificationStarted().run();
                Path runDirectory = Files.createDirectories(
                        workspace.evidenceRoot().resolve("runs/run-" + number));
                return passedResult(context, runDirectory, "run-" + number);
            };
            engines.add(engine);
            return engine;
        };
        ChangeWorker worker = new DefaultChangeWorker(workflow, workspaces, runtimes,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ChangeWorkerJobHandler handler = new ChangeWorkerJobHandler(workflow, worker);

        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"), prompt -> "unused", 2)) {
            handler.register(manager);
            WorkerJob firstJob = handler.enqueue(manager, first.id(), first.version());
            WorkerJob secondJob = handler.enqueue(manager, second.id(), second.version());
            manager.start();

            assertEquals(TaskStatus.COMPLETED, waitForTerminal(manager, firstJob.id()).status());
            assertEquals(TaskStatus.COMPLETED, waitForTerminal(manager, secondJob.id()).status());
        }

        ChangeTask firstDone = workflow.get(first.id()).task();
        ChangeTask secondDone = workflow.get(second.id()).task();
        assertEquals(ChangeState.DELIVERY_REVIEW, firstDone.state());
        assertEquals(ChangeState.DELIVERY_REVIEW, secondDone.state());
        assertEquals(2, runtimeCount.get());
        assertEquals(2, engines.size());
        assertNotEquals(firstDone.run().workspaceId(), secondDone.run().workspaceId());
        assertTrue(Files.isDirectory(firstDone.run().evidencePath()));
        assertTrue(Files.isDirectory(secondDone.run().evidencePath()));
    }

    @Test
    void workspacePreparationFailurePersistsBusinessFailure() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        DefaultChangeWorkflow workflow = workflow(store);
        ChangeTask ready = ready(workflow, "worker-failure");
        workflow.queueForExecution(ready.id(), ready.version());
        ChangeWorker worker = new DefaultChangeWorker(
                workflow,
                new WorkspaceProvisioner() {
                    @Override
                    public WorkspaceLease prepare(ChangeTask task) throws Exception {
                        throw new java.io.IOException("worktree unavailable");
                    }

                    @Override
                    public WorkspaceSnapshot seal(WorkspaceLease lease) {
                        throw new AssertionError("must not seal");
                    }

                    @Override
                    public void release(WorkspaceLease lease) {
                    }
                },
                (task, workspace) -> {
                    throw new AssertionError("must not create runtime");
                });

        assertThrows(IllegalStateException.class, () -> worker.run(ready.id()));

        ChangeTaskView failed = workflow.get(ready.id());
        assertEquals(ChangeState.FAILED, failed.task().state());
        assertEquals("change.failed", failed.events().get(failed.events().size() - 1).type());
        assertTrue(failed.events().get(failed.events().size() - 1).payloadJson()
                .contains("worktree unavailable"));
    }

    private DefaultChangeWorkflow workflow(InMemoryChangeStore store) {
        ChangeSpecModule specs = new ChangeSpecModule() {
            @Override
            public SpecDraft generateDraft(ChangeContext context) throws java.io.IOException {
                String content = validDocument(context.specId(), context.revision());
                var document = new ChangeSpecCodec().decode(content);
                Path draftPath = tempDir.resolve(context.specId() + "-r" + context.revision() + ".draft.md");
                Files.writeString(draftPath, content);
                return new SpecDraft(
                        draftPath,
                        context.specId(), context.revision(), document.specDigest(),
                        0L, SpecRunResult.LlmUsage.empty());
            }

            @Override
            public LockedSpec lockConfirmed(SpecDraft draft, String expectedDigest) {
                return new LockedSpec(
                        tempDir.resolve(draft.specId() + "-r" + draft.revision() + ".md"),
                        draft.specId(), draft.revision(), draft.specDigest());
            }
        };
        return new DefaultChangeWorkflow(
                store, store, specs, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String validDocument(String id, int revision) {
        return DefaultChangeWorkflowTest.validDocumentForTests(id, revision);
    }

    private ChangeTask ready(DefaultChangeWorkflow workflow, String key) {
        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, new ChangeRequest(
                key,
                new WorkItemRef("mock", key, ""),
                new RepositoryRef(tempDir.toString(), "main"),
                "title",
                "requirement",
                "requester",
                "",
                ""));
        ChangeTask review = workflow.get(id).task();
        return workflow.decide(id, new ChangeDecision.ApproveSpec(
                review.version(), review.spec().digest(), "lead", "ok")).task();
    }

    private static SpecRunResult passedResult(
            SpecExecutionEngine.ExecutionContext context,
            Path runDirectory,
            String runId
    ) {
        return new SpecRunResult(
                SpecRunResult.Status.FINISHED,
                new SpecRunResult.RunIdentity(
                        runId,
                        context.lockedSpec().specId(),
                        context.lockedSpec().revision(),
                        context.lockedSpec().specDigest(),
                        context.lockedSpec().path()),
                "done",
                new WorkspaceChangeTracker.WorkspaceChanges(List.of("changed.txt"), "diff", false),
                List.of(),
                List.of(),
                List.of(),
                SpecRunResult.Verdict.PASSED,
                SpecRunResult.Metrics.empty(),
                SpecRunResult.Artifacts.saved(
                        runDirectory,
                        runDirectory.resolve("result.json"),
                        runDirectory.resolve("change.diff")),
                "");
    }

    private static DurableTask waitForTerminal(DurableTaskManager manager, String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            DurableTask task = manager.find(id).orElseThrow();
            if (task.terminal()) {
                return task;
            }
            Thread.sleep(20);
        }
        fail("task did not finish in time");
        return null;
    }

    private static final class FakeWorkspaceProvisioner implements WorkspaceProvisioner {
        private final Path root;
        private final AtomicInteger sequence = new AtomicInteger();

        private FakeWorkspaceProvisioner(Path root) {
            this.root = root;
        }

        @Override
        public WorkspaceLease prepare(ChangeTask task) throws Exception {
            String id = "workspace-" + sequence.incrementAndGet();
            Path workspace = Files.createDirectories(root.resolve(id));
            Path evidence = Files.createDirectories(root.resolve("evidence-" + id));
            return new WorkspaceLease(
                    task.id(), id, root, workspace, evidence, "paichange/" + id, "base-sha");
        }

        @Override
        public WorkspaceSnapshot seal(WorkspaceLease lease) {
            return new WorkspaceSnapshot(
                    lease.workspaceId(), lease.branch(), "head-" + lease.workspaceId(), lease.evidenceRoot());
        }

        @Override
        public void release(WorkspaceLease lease) {
        }
    }
}
