package com.paicli.change;

import com.paicli.runtime.task.PostgresWorkerJobScheduler;
import com.paicli.runtime.task.WorkerJob;
import com.paicli.runtime.task.WorkerJobLifecycleListener;
import com.paicli.spec.SpecRunResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real PostgreSQL + MinIO contract and recovery acceptance. */
class M6bStorageIntegrationTest {
    @TempDir Path root;

    @Test
    void postgresQueueAndS3EvidenceCloseTheMinimumDurableLoop() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("paichange.m6b.integration.enabled"),
                "run with -Ppaichange-m6b-it or docker/run-paichange-m6b-tests.sh");
        String jdbc = property("paichange.m6b.test.jdbc", "jdbc:postgresql://127.0.0.1:55432/paichange");
        String user = property("paichange.m6b.test.user", "paichange");
        String password = property("paichange.m6b.test.password", "paichange-test-only");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path legacyDatabase = root.resolve("legacy.db");
        Path legacyArchive = Files.createDirectories(root.resolve("legacy-archive")).toRealPath();
        Path legacyStaging = Files.createDirectories(root.resolve("legacy-staging"));
        Files.writeString(legacyStaging.resolve("result.json"), "{\"runId\":\"legacy-run\"}");
        Files.writeString(legacyStaging.resolve("change.diff"), "legacy diff\n");
        ChangeTask legacy = legacyTask("change_" + suffix.substring(12, 24), "legacy-" + suffix,
                legacyArchive.resolve("change_" + suffix.substring(12, 24)).resolve("legacy-run"));
        try (SqliteChangeStore sqlite = new SqliteChangeStore(legacyDatabase)) { sqlite.create(legacy, event(legacy)); }
        try (TrustedEvidenceStore evidence = new TrustedEvidenceStore(legacyDatabase, legacyArchive)) {
            evidence.capture(legacy.id(), "legacy-run", legacyStaging);
        }
        Path migratedCache = root.resolve("migrated-cache");
        SqliteToPostgresMigrator.Result migrated = SqliteToPostgresMigrator.migrate(legacyDatabase, legacyArchive,
                jdbc, user, password, s3(), migratedCache);
        assertEquals(1, migrated.tasks());
        assertEquals(1, migrated.events());
        assertEquals(1, migrated.evidenceArchives());
        try (PostgresChangeStore target = new PostgresChangeStore(jdbc, user, password);
             PostgresEvidenceStore evidence = new PostgresEvidenceStore(jdbc, user, password, s3(), migratedCache)) {
            ChangeTask imported = target.find(legacy.id()).orElseThrow();
            assertEquals(legacy.spec().digest(), imported.spec().digest());
            assertEquals(legacy.specApproval(), imported.specApproval());
            assertEquals(legacy.deliveryApproval(), imported.deliveryApproval());
            assertEquals(migratedCache.toRealPath().resolve(legacy.id().value()).resolve("legacy-run"),
                    imported.run().evidencePath());
            assertEquals(1, target.events(legacy.id()).size());
            evidence.verify(legacy.id(), "legacy-run", imported.run().evidencePath());
        }

        ChangeTask task = task("change_" + suffix.substring(0, 12), "m6b-" + suffix);

        try (PostgresChangeStore store = new PostgresChangeStore(jdbc, user, password)) {
            assertEquals(PostgresStorageMigrations.CURRENT_VERSION, store.schemaVersion());
            assertEquals(task, store.create(task, event(task)));
            assertEquals(task, store.findByIdempotencyKey(task.idempotencyKey()).orElseThrow());
            assertEquals(1, store.events(task.id()).size());
            store.checkHealth();
        }
        try (PostgresChangeStore contract = new PostgresChangeStore(jdbc, user, password)) {
            ChangePersistenceContract.exercise(contract, root,
                    UUID.randomUUID().toString().replace("-", ""));
        }
        try (PostgresChangeStore reopened = new PostgresChangeStore(jdbc, user, password)) {
            assertEquals(task, reopened.find(task.id()).orElseThrow());
        }

        S3ObjectStorage objectStorage = s3();
        Path source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve("result.json"), "{\"runId\":\"run-" + suffix + "\"}");
        Files.writeString(source.resolve("change.diff"), "diff\n");
        Path cached;
        try (PostgresEvidenceStore evidence = new PostgresEvidenceStore(jdbc, user, password, objectStorage,
                root.resolve("cache"))) {
            EvidenceStore.Capture capture = evidence.capture(task.id(), "run-" + suffix, source);
            cached = capture.path();
            evidence.verify(task.id(), "run-" + suffix, cached);
            assertTrue(Files.isRegularFile(cached.resolve("result.json")));
            Files.delete(cached.resolve("result.json"));
            evidence.verify(task.id(), "run-" + suffix, cached);
            assertTrue(Files.isRegularFile(cached.resolve("result.json")), "remote object must rebuild local cache");
        }
        try (PostgresEvidenceStore reopened = new PostgresEvidenceStore(jdbc, user, password, s3(), root.resolve("cache"))) {
            reopened.verify(task.id(), "run-" + suffix, cached);
        }

        CountDownLatch completed = new CountDownLatch(1);
        try (PostgresWorkerJobScheduler queue = new PostgresWorkerJobScheduler(jdbc, user, password, 2, 2_000, 25)) {
            queue.registerWorkerJobHandler("m6b.contract", job -> {
                completed.countDown();
                return job.referenceId();
            }, WorkerJobLifecycleListener.NO_OP);
            WorkerJob first = queue.enqueueWorkerJob("m6b.contract", task.id().value());
            WorkerJob duplicate = queue.enqueueWorkerJob("m6b.contract", task.id().value());
            assertEquals(first.id(), duplicate.id(), "active duplicate delivery must converge on one durable job");
            queue.start();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            awaitStatus(queue, first.id(), "COMPLETED");
            queue.checkHealth();
        }

        AtomicInteger recoveries = new AtomicInteger();
        String recoveredId = "job_" + suffix.substring(0, 12);
        try (var connection = DriverManager.getConnection(jdbc, user, password);
             var insert = connection.prepareStatement("""
                     INSERT INTO worker_jobs(id, job_type, reference_id, status, recovery_count, lease_owner,
                         lease_expires_at, created_at, started_at, updated_at)
                     VALUES (?, 'm6b.recovery', ?, 'RUNNING', 0, 'dead-worker', ?, ?, ?, ?)
                     """)) {
            String past = Instant.now().minusSeconds(30).toString();
            insert.setString(1, recoveredId); insert.setString(2, "recovery-" + suffix);
            insert.setString(3, past); insert.setString(4, past); insert.setString(5, past); insert.setString(6, past);
            insert.executeUpdate();
        }
        CountDownLatch recovered = new CountDownLatch(1);
        try (PostgresWorkerJobScheduler queue = new PostgresWorkerJobScheduler(jdbc, user, password, 1, 2_000, 25)) {
            queue.registerWorkerJobHandler("m6b.recovery", job -> { recovered.countDown(); return "ok"; },
                    new WorkerJobLifecycleListener() {
                        @Override public void recovered(WorkerJob job) { recoveries.incrementAndGet(); }
                        @Override public void canceled(WorkerJob job, String reason) { }
                    });
            queue.start();
            assertTrue(recovered.await(10, TimeUnit.SECONDS));
            awaitStatus(queue, recoveredId, "COMPLETED");
            assertEquals(1, recoveries.get(), "expired lease must be observably recovered once");
        }
    }

    private S3ObjectStorage s3() {
        return new S3ObjectStorage(property("paichange.m6b.test.s3.endpoint", "http://127.0.0.1:59000"),
                property("paichange.m6b.test.s3.bucket", "paichange-evidence"), "us-east-1",
                property("paichange.m6b.test.s3.access", "paichange-test"),
                property("paichange.m6b.test.s3.secret", "paichange-test-secret"));
    }

    private static void awaitStatus(PostgresWorkerJobScheduler queue, String id, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (queue.find(id).map(job -> job.status().name()).orElse("").equals(expected)) return;
            Thread.sleep(25);
        }
        fail("job did not reach " + expected + ": " + queue.find(id));
    }

    private ChangeTask task(String id, String key) {
        Instant now = Instant.now();
        return new ChangeTask(new ChangeTaskId(id), key, 0, ChangeState.CREATED,
                new WorkItemRef("test", "M6B-1", ""), new RepositoryRef(root.resolve("repo").toString(), "main"),
                "M6b storage", "persist the minimum loop", "tester", "", "", null, null, null,
                null, null, null, null, null, null, now, now);
    }

    private ChangeTask legacyTask(String id, String key, Path evidence) throws Exception {
        Instant now = Instant.now();
        Path specDirectory = Files.createDirectories(root.resolve("legacy-spec"));
        Path draft = specDirectory.resolve("draft.yaml");
        Path locked = specDirectory.resolve("locked.yaml");
        Files.writeString(draft, "spec_id: legacy-spec\nrevision: 1\n");
        Files.writeString(locked, "spec_id: legacy-spec\nrevision: 1\nlocked: true\n");
        SpecRef spec = new SpecRef("legacy-spec", 1, "legacy-digest", draft, locked);
        RiskAssessment risk = new RiskAssessment(RiskLevel.HIGH, 80, java.util.List.of("migration-contract"));
        ExecutionRoute route = new ExecutionRoute(RiskLevel.HIGH, "test", "test-model",
                ExecutionRoute.ExecutionMode.REACT, true, true, true,
                ExecutionRoute.ToolPolicyProfile.RESTRICTED);
        ApprovalRecord specApproval = new ApprovalRecord("spec-legacy", ApprovalRecord.Stage.SPEC,
                ApprovalRecord.Decision.APPROVED, "spec-approver", "locked", spec.digest(), "", now);
        RunRef run = new RunRef("legacy-run", "legacy-digest", SpecRunResult.Status.FINISHED,
                SpecRunResult.Verdict.PASSED, "legacy-workspace", "paichange/legacy", "0123456789abcdef",
                evidence.toAbsolutePath().normalize(), now);
        ApprovalRecord deliveryApproval = new ApprovalRecord("delivery-legacy", ApprovalRecord.Stage.DELIVERY,
                ApprovalRecord.Decision.APPROVED, "delivery-approver", "verified", spec.digest(),
                run.headSha(), now, run.runId(), 0);
        return new ChangeTask(new ChangeTaskId(id), key, 0, ChangeState.DELIVERY_REVIEW,
                new WorkItemRef("legacy", "M6B-0", ""), new RepositoryRef(root.resolve("legacy-repo").toString(), "main"),
                "Legacy storage", "migrate historical evidence", "tester", "", "", spec, risk, route,
                specApproval, deliveryApproval, null, run, null, null, now, now);
    }

    private static ChangeEvent event(ChangeTask task) {
        return new ChangeEvent(0, task.id(), "change.created", "HUMAN", "tester", null,
                task.state(), "{}", task.createdAt());
    }

    private static String property(String name, String fallback) {
        return System.getProperty(name, fallback);
    }
}
