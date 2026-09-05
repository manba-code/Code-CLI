package com.paicli.change;

import com.paicli.spec.SpecRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MockScmAdapterTest {
    @TempDir Path root;

    @Test
    void highRiskRequiresApprovalAndCheckSurvivesReopenWithoutDuplicates() throws Exception {
        ChangeTaskId id;
        Path db = root.resolve("changes.db");
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> task.run().headSha());
            ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, "high", true));
            ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), SpecRunResult.Verdict.PASSED);
            id = task.id();
            workflow.advance(id);
            assertTrue(scm.find(task).isEmpty());
            assertEquals(RiskLevel.HIGH, task.risk().level());
            ChangeTask completed = workflow.decide(id, ChangeTestSupport.approveDelivery(task)).task();
            assertEquals(ChangeState.COMPLETED, completed.state());
            assertEquals("success", scm.find(completed).orElseThrow().conclusion());
            long events = workflow.get(id).events().size();
            workflow.advance(id);
            assertEquals(events, workflow.get(id).events().size());
            assertThrows(ChangeConflictException.class, () -> workflow.decide(id, ChangeTestSupport.approveDelivery(task)));
        }
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            assertEquals("head-current", scm.find(store.find(id).orElseThrow()).orElseThrow().headSha());
            assertEquals(1, count(db, "mock_pull_requests"));
            assertEquals(1, count(db, "mock_pr_checks"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = SpecRunResult.Verdict.class, names = {"FAILED", "INCOMPLETE", "SPEC_INVALID", "NEEDS_HUMAN"})
    void nonPassedVerdictsNeverPublishSuccess(SpecRunResult.Verdict verdict) throws Exception {
        Path db = root.resolve("changes.db");
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> task.run().headSha());
            ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, "verdict", true));
            ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), verdict);
            workflow.advance(task.id());
            task = workflow.get(task.id()).task();
            assertEquals(verdict == SpecRunResult.Verdict.NEEDS_HUMAN ? "pending" : "failure",
                    scm.find(task).orElseThrow().conclusion());
            assertNotEquals(ChangeState.COMPLETED, task.state());
            ChangeTask current = task;
            assertThrows(RuntimeException.class, () -> workflow.decide(current.id(), ChangeTestSupport.approveDelivery(current)));
            workflow.advance(task.id());
            assertEquals(1, count(db, "mock_pr_checks"));
        }
    }

    @Test
    void changedHeadAndMissingEvidenceBlockApprovalAndPublication() throws Exception {
        Path db = root.resolve("changes.db");
        AtomicReference<String> head = new AtomicReference<>("moved-head");
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> head.get());
            ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, "changed", true));
            ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), SpecRunResult.Verdict.PASSED);
            assertThrows(ChangeConflictException.class, () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
            assertNull(workflow.get(task.id()).task().deliveryApproval());
            head.set(task.run().headSha());
            Files.delete(task.run().evidencePath().resolve("result.json"));
            assertThrows(ChangeValidationException.class, () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
            assertEquals(0, count(db, "mock_pr_checks"));
        }
    }

    @Test
    void failedScmTransactionKeepsApprovalAndPublishingThenRetryCompletesOnce() throws Exception {
        Path db = root.resolve("changes.db");
        ChangeTaskId id;
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> task.run().headSha());
            ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, "scm-error", true));
            ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), SpecRunResult.Verdict.PASSED);
            id = task.id();
            sql(db, "CREATE TRIGGER fail_check BEFORE INSERT ON mock_pr_checks BEGIN SELECT RAISE(ABORT, 'disk error'); END");
            assertThrows(IllegalStateException.class, () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
            assertEquals(ChangeState.PUBLISHING, workflow.get(id).task().state());
            assertNotNull(workflow.get(id).task().deliveryApproval());
            assertEquals(0, count(db, "mock_pull_requests"));
        }
        sql(db, "DROP TRIGGER fail_check");
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> task.run().headSha());
            workflow.advance(id);
            workflow.advance(id);
            assertEquals(ChangeState.COMPLETED, workflow.get(id).task().state());
            assertEquals(1, count(db, "mock_pr_checks"));
        }
    }

    @Test
    void committedCheckDoesNotPretendCompletedWhenEventTransactionFails() throws Exception {
        Path db = root.resolve("changes.db");
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> task.run().headSha());
            ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, "event-error", true));
            ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), SpecRunResult.Verdict.PASSED);
            sql(db, "CREATE TRIGGER fail_event BEFORE INSERT ON change_events WHEN NEW.event_type = 'change.completed' BEGIN SELECT RAISE(ABORT, 'disk error'); END");
            assertThrows(IllegalStateException.class, () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
            assertEquals(ChangeState.PUBLISHING, workflow.get(task.id()).task().state());
            assertEquals(1, count(db, "mock_pr_checks"));
            sql(db, "DROP TRIGGER fail_event");
            workflow.advance(task.id());
            assertEquals(ChangeState.COMPLETED, workflow.get(task.id()).task().state());
            assertEquals(1, workflow.get(task.id()).events().stream().filter(e -> e.type().equals("pr.check_published")).count());
        }
    }

    @Test
    void publicationRetryRechecksHeadSpecVerdictAndApproval() throws Exception {
        Path db = root.resolve("changes.db");
        AtomicReference<String> head = new AtomicReference<>("head-current");
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> head.get());
            ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, "retry-check", true));
            ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), SpecRunResult.Verdict.PASSED);
            sql(db, "CREATE TRIGGER fail_check BEFORE INSERT ON mock_pr_checks BEGIN SELECT RAISE(ABORT, 'disk error'); END");
            assertThrows(IllegalStateException.class, () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
            sql(db, "DROP TRIGGER fail_check");
            head.set("moved-head");
            assertThrows(ChangeConflictException.class, () -> workflow.advance(task.id()));
            head.set(task.run().headSha());
            Path locked = task.spec().lockedPath();
            String original = Files.readString(locked);
            Files.writeString(locked, original.replace("Write the verified output", "Changed contract"));
            assertThrows(ChangeConflictException.class, () -> workflow.advance(task.id()));
            Files.writeString(locked, original);
            Path evidence = task.run().evidencePath().resolve("result.json");
            String saved = Files.readString(evidence);
            Files.writeString(evidence, saved.replace("PASSED", "FAILED"));
            assertThrows(ChangeValidationException.class, () -> workflow.advance(task.id()));
            Files.writeString(evidence, saved);
            ChangeTask publishing = workflow.get(task.id()).task();
            // Simulate a corrupt/reconstructed state: HIGH must be guarded again at publication.
            ChangeTask withoutApproval = publishing.withDeliveryDecision(ChangeState.PUBLISHING, null, java.time.Instant.now());
            store.update(publishing.version(), withoutApproval, new ChangeEvent(0, task.id(), "test.corruption", "TEST", "test",
                    ChangeState.PUBLISHING, ChangeState.PUBLISHING, "{}", java.time.Instant.now()));
            assertThrows(ChangeValidationException.class, () -> workflow.advance(task.id()));
            assertEquals(0, count(db, "mock_pr_checks"));
        }
    }

    @Test
    void failedApprovalTransactionCannotCreateAnyCheck() throws Exception {
        Path db = root.resolve("changes.db");
        try (SqliteChangeStore store = new SqliteChangeStore(db); MockScmAdapter scm = new MockScmAdapter(db)) {
            DefaultChangeWorkflow workflow = connected(store, scm, task -> task.run().headSha());
            ChangeTask ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, "approval-error", true));
            ChangeTask task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), SpecRunResult.Verdict.PASSED);
            sql(db, "CREATE TRIGGER fail_approval BEFORE INSERT ON change_approvals WHEN NEW.stage = 'DELIVERY' BEGIN SELECT RAISE(ABORT, 'disk error'); END");
            assertThrows(IllegalStateException.class, () -> workflow.decide(task.id(), ChangeTestSupport.approveDelivery(task)));
            assertEquals(ChangeState.DELIVERY_REVIEW, workflow.get(task.id()).task().state());
            assertNull(workflow.get(task.id()).task().deliveryApproval());
            assertEquals(0, count(db, "mock_pr_checks"));
        }
    }

    private DefaultChangeWorkflow connected(SqliteChangeStore store, MockScmAdapter scm, DeliveryHeadReader heads) {
        DefaultChangeWorkflow workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
        workflow.connect(id -> { }, scm, heads);
        return workflow;
    }

    static void sql(Path db, String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); }
    }

    static int count(Path db, String table) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM " + table)) { r.next(); return r.getInt(1); }
    }
}
