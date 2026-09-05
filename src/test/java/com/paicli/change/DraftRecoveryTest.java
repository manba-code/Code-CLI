package com.paicli.change;

import com.paicli.spec.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fault injection at each durable boundary, with real SQLite and immutable Draft files. */
class DraftRecoveryTest {
    @TempDir Path root;
    static final class Time extends Clock {
        long now = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    @Test void allFourCrashWindowsConvergeToOneEffectiveDraftAndKeepLockedDigest() throws Exception {
        for (int window = 0; window < 4; window++) {
            Path data = root.resolve("window-" + window), db = data.resolve("changes.db");
            Time time = new Time();
            ChangeTaskId id;
            DraftJob oldClaim = null;
            ChangeSpecModule.SpecDraft orphan = null;
            try (var store = new SqliteChangeStore(db)) {
                var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(data), time);
                id = workflow.submit(ChangeTestSupport.request(root, "same", false));
                assertEquals(id, workflow.submit(ChangeTestSupport.request(root, "same", false)));
                assertEquals(0, workflow.get(id).task().draftJob().attempts());
                if (window >= 1) oldClaim = workflow.claimDraft(id, 10000);
                if (window >= 2) orphan = workflow.generateDraft(oldClaim);
                if (window >= 3) workflow.completeDraft(id, oldClaim, orphan);
                // Crash: no scheduler acknowledgement, and no cleanup of unreferenced files.
            }
            try (var store = new SqliteChangeStore(db)) {
                var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(data), time);
                workflow.recoverDrafts();
                time.now += 3000;
                DraftJob current = workflow.claimDraft(id, 10000);
                if (window == 3) assertNull(current, "committed result must not regenerate");
                else {
                    assertNotNull(current);
                    assertEquals(window == 0 ? 1 : 2, current.attempts());
                    if (oldClaim != null) assertEquals(oldClaim.input(), current.input());
                    if (orphan != null) {
                        workflow.completeDraft(id, oldClaim, orphan);
                        assertEquals(ChangeState.DRAFTING_SPEC, workflow.get(id).task().state());
                    }
                    workflow.completeDraft(id, current, workflow.generateDraft(current));
                }
                var review = workflow.get(id).task();
                assertEquals(ChangeState.SPEC_REVIEW, review.state());
                assertEquals(DraftJob.Status.SUCCEEDED, review.draftJob().status());
                assertEquals(1, workflow.get(id).events().stream().filter(e -> e.type().equals("spec.draft_generated")).count());
                if (orphan != null && window < 3) assertNotEquals(orphan.path(), review.spec().draftPath());
                workflow.decide(id, new ChangeDecision.ApproveSpec(review.version(), review.spec().digest(), "lead", "checked"));
            }
            try (var store = new SqliteChangeStore(db)) {
                var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(data), time);
                var locked = workflow.get(id).task();
                workflow.recoverDrafts();
                assertEquals(locked, workflow.get(id).task());
                assertTrue(locked.spec().locked());
                assertNull(workflow.claimDraft(id, 10000));
                assertEquals(locked.spec().digest(), new ChangeSpecCodec().decode(Files.readString(locked.spec().lockedPath())).specDigest());
            }
        }
    }

    @Test void retryBudgetAndBackoffSurviveRestartAndManualRetryKeepsHistory() throws Exception {
        Path db = root.resolve("retry.db");
        Time time = new Time();
        ChangeTaskId id;
        String firstGeneration;
        try (var store = new SqliteChangeStore(db)) {
            var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root), time);
            id = workflow.submit(ChangeTestSupport.request(root, "retry", false));
            DraftJob first = workflow.claimDraft(id, 1000);
            firstGeneration = first.generation();
            workflow.failDraft(id, first, true, "temporary outage");
            assertNull(workflow.claimDraft(id, 1000));
        }
        try (var store = new SqliteChangeStore(db)) {
            var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root), time);
            workflow.recoverDrafts();
            for (int attempt = 2; attempt <= 3; attempt++) {
                time.now += 2000;
                DraftJob job = workflow.claimDraft(id, 1000);
                assertEquals(attempt, job.attempts());
                workflow.failDraft(id, job, true, "temporary outage");
            }
            var failed = workflow.get(id);
            assertEquals(ChangeState.FAILED, failed.task().state());
            assertNull(workflow.claimDraft(id, 1000));
            workflow.recoverDrafts();
            assertEquals(failed, workflow.get(id));
            var retried = workflow.retryDraft(id, failed.task().version(), firstGeneration, "owner");
            assertNotEquals(firstGeneration, retried.task().draftJob().generation());
            assertEquals(0, retried.task().draftJob().attempts());
            assertEquals(failed.events(), retried.events().subList(0, failed.events().size()));
            assertThrows(ChangeConflictException.class, () -> workflow.retryDraft(id, failed.task().version(), firstGeneration, "owner"));
            assertThrows(ChangeConflictException.class, () -> workflow.cancelDraft(id, retried.task().version(), firstGeneration, "owner"));
            ChangeTestSupport.draft(workflow, id);
            assertEquals(ChangeState.SPEC_REVIEW, workflow.get(id).task().state());
        }
    }

    @Test void canceledReplacedAndExpiredLeasesCannotWriteBackEvenWithValidArtifacts() throws Exception {
        var store = new InMemoryChangeStore();
        Time time = new Time();
        var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root), time);
        for (String mode : new String[]{"cancel", "replace", "expire"}) {
            var id = workflow.submit(ChangeTestSupport.request(root, mode, false));
            var lease = workflow.claimDraft(id, 1000);
            var result = workflow.generateDraft(lease);
            if (mode.equals("cancel")) {
                var current = workflow.get(id).task();
                workflow.cancelDraft(id, current.version(), lease.generation(), "owner");
            } else if (mode.equals("replace")) {
                workflow.failDraft(id, lease, false, "failed");
                var current = workflow.get(id).task();
                workflow.retryDraft(id, current.version(), lease.generation(), "owner");
            } else time.now += 1000;
            var before = workflow.get(id);
            workflow.completeDraft(id, lease, result);
            assertEquals(before, workflow.get(id));
        }
    }

    @Test void concurrentDuplicateClaimsHaveOnlyOneWinner() throws Exception {
        var store = new InMemoryChangeStore();
        var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
        var id = workflow.submit(ChangeTestSupport.request(root, "claim", false));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var a = callers.submit(() -> workflow.claimDraft(id, 10000));
            var b = callers.submit(() -> workflow.claimDraft(id, 10000));
            var first = a.get(2, TimeUnit.SECONDS); var second = b.get(2, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null));
            var winner = first == null ? second : first;
            var result = workflow.generateDraft(winner);
            workflow.completeDraft(id, winner, result);
            var saved = workflow.get(id);
            workflow.completeDraft(id, winner, result);
            assertEquals(saved, workflow.get(id));
        } finally { callers.shutdownNow(); }
    }

    @Test void migratesInterruptedLegacyRowsButNeverRegeneratesReviewedOrLockedSpecs() throws Exception {
        Path db = root.resolve("legacy.db");
        ChangeTaskId id;
        try (var store = new SqliteChangeStore(db)) {
            var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
            id = workflow.submit(ChangeTestSupport.request(root, "legacy", false));
        }
        // Recreate pre-M1 schema by removing only the additive column.
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE change_tasks DROP COLUMN draft_job_json");
        }
        try (var store = new SqliteChangeStore(db)) {
            var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
            assertNull(workflow.get(id).task().draftJob());
            workflow.recoverDrafts();
            assertNotNull(workflow.get(id).task().draftJob());
            ChangeTestSupport.draft(workflow, id);
            var saved = workflow.get(id);
            workflow.recoverDrafts();
            assertEquals(saved, workflow.get(id));
        }
    }
}
