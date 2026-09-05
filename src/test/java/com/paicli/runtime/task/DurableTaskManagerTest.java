package com.paicli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DurableTaskManagerTest {

    @Test
    void runsEnqueuedTaskAndPersistsResult(@TempDir Path tempDir) throws Exception {
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> "done:" + prompt,
                1)) {
            manager.start();

            DurableTask task = manager.enqueue("hello");
            DurableTask completed = waitForTerminal(manager, task.id());

            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals("done:hello", completed.result());
            assertTrue(manager.list(10).stream().anyMatch(t -> t.id().equals(task.id())));
        }
    }

    @Test
    void recoversRunningTasksAsEnqueued(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        try (DurableTaskManager manager = new DurableTaskManager(db, prompt -> "never", 1)) {
            DurableTask task = manager.enqueue("resume me");
            markRunning(manager, task.id());
        }

        try (DurableTaskManager recovered = new DurableTaskManager(db, prompt -> "ok", 1)) {
            assertEquals(TaskStatus.ENQUEUED, recovered.find(recovered.list(1).get(0).id()).orElseThrow().status());
        }
    }

    @Test
    void cancelsRunningTask(@TempDir Path tempDir) throws Exception {
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> {
                    Thread.sleep(5000);
                    return "late";
                },
                1)) {
            manager.start();
            DurableTask task = manager.enqueue("slow");
            waitUntilStatus(manager, task.id(), TaskStatus.RUNNING);

            assertTrue(manager.cancel(task.id()));
            DurableTask canceled = waitForTerminal(manager, task.id());

            assertEquals(TaskStatus.CANCELED, canceled.status());
        }
    }

    @Test
    void runsReferenceOnlyWorkerJobAndDeduplicatesActiveReference(@TempDir Path tempDir) throws Exception {
        AtomicReference<WorkerJob> observed = new AtomicReference<>();
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("jobs.db"), prompt -> "prompt:" + prompt, 1)) {
            manager.registerWorkerJobHandler("change.execute", job -> {
                observed.set(job);
                Thread.sleep(100);
                return "done:" + job.referenceId();
            }, WorkerJobLifecycleListener.NO_OP);

            WorkerJob first = manager.enqueueWorkerJob("change.execute", "change_123456789abc");
            WorkerJob duplicate = manager.enqueueWorkerJob("change.execute", "change_123456789abc");
            assertEquals(first.id(), duplicate.id());
            manager.start();

            DurableTask completed = waitForTerminal(manager, first.id());
            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals("", completed.prompt());
            assertEquals("change.execute", observed.get().type());
            assertEquals("change_123456789abc", observed.get().referenceId());
        }
    }

    @Test
    void recoveredWorkerJobNotifiesBusinessLifecycleBeforeRerun(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("recovery-jobs.db");
        String jobId;
        try (DurableTaskManager manager = new DurableTaskManager(db, prompt -> "unused", 1)) {
            WorkerJob job = manager.enqueueWorkerJob("change.execute", "change_abcdef123456");
            jobId = job.id();
            markRunning(manager, jobId);
        }

        AtomicInteger recovered = new AtomicInteger();
        try (DurableTaskManager manager = new DurableTaskManager(db, prompt -> "unused", 1)) {
            manager.registerWorkerJobHandler(
                    "change.execute",
                    job -> "resumed",
                    new WorkerJobLifecycleListener() {
                        @Override
                        public void recovered(WorkerJob job) {
                            assertEquals(1, job.recoveryCount());
                            recovered.incrementAndGet();
                        }
                    });
            manager.start();

            assertEquals(TaskStatus.COMPLETED, waitForTerminal(manager, jobId).status());
            assertEquals(1, recovered.get());
        }
    }

    @Test
    void cancelingWorkerJobNotifiesBusinessLifecycle(@TempDir Path tempDir) throws Exception {
        AtomicInteger canceled = new AtomicInteger();
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("cancel-job.db"), prompt -> "unused", 1)) {
            manager.registerWorkerJobHandler(
                    "change.execute",
                    job -> {
                        Thread.sleep(5000);
                        return "late";
                    },
                    new WorkerJobLifecycleListener() {
                        @Override
                        public void canceled(WorkerJob job, String reason) {
                            canceled.incrementAndGet();
                        }
                    });
            manager.start();
            WorkerJob job = manager.enqueueWorkerJob("change.execute", "change_fedcba654321");
            waitUntilStatus(manager, job.id(), TaskStatus.RUNNING);

            assertTrue(manager.cancel(job.id()));
            assertEquals(TaskStatus.CANCELED, waitForTerminal(manager, job.id()).status());
            assertTrue(canceled.get() >= 1);
        }
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

    private static void waitUntilStatus(DurableTaskManager manager, String id, TaskStatus status) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.find(id).orElseThrow().status() == status) {
                return;
            }
            Thread.sleep(20);
        }
        fail("task did not reach status " + status);
    }

    private static void markRunning(DurableTaskManager manager, String id) throws Exception {
        var field = DurableTaskManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        java.sql.Connection connection = (java.sql.Connection) field.get(manager);
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "UPDATE runtime_tasks SET status = 'running' WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
