package com.paicli.change;

import com.paicli.spec.SpecRunResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.paicli.spec.SpecRunResult.HumanDecision.*;
import static com.paicli.spec.SpecRunResult.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class HumanEvidenceWorkflowTest {
    @TempDir Path root;
    private DefaultChangeWorkflow workflow(SqliteChangeStore store, MockScmAdapter scm, AtomicReference<String> head) throws Exception {
        var config = new com.paicli.config.PaiCliConfig();
        var route = new com.paicli.config.PaiCliConfig.PaiChangeRouteConfig();
        route.setDeliveryApprovalRequired(false);
        config.getPaiChange().setRoutes(Map.of("LOW", route, "MEDIUM", route));
        var workflow = new DefaultChangeWorkflow(store, store, HumanEvidenceTestSupport.specs(root), config, java.time.Clock.systemUTC());
        workflow.connect(id -> {}, scm, task -> head.get());
        workflow.connectArtifacts(new ChangeArtifactReader(root));
        return workflow;
    }

    @Test void highRiskCorrectionsInvalidateApprovalAndSurviveReopenWithImmutableOriginals() throws Exception {
        Path db = root.resolve("changes.db");
        ChangeTaskId id; String approval; byte[] original, diff;
        var head = new AtomicReference<>("head-current");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, head);
            var task = HumanEvidenceTestSupport.finished(w, root, "high", true, NEEDS_HUMAN, "PASS"); id = task.id();
            original = Files.readAllBytes(task.run().evidencePath().resolve("result.json"));
            diff = Files.readAllBytes(task.run().evidencePath().resolve("change.diff"));
            w.advance(id); task = w.get(id).task();
            assertEquals("pending", scm.find(task).orElseThrow().conclusion());
            var pending = task;
            assertThrows(ChangeValidationException.class, () -> w.decide(id, ChangeTestSupport.approveDelivery(pending)));
            task = HumanEvidenceTestSupport.record(w, task, "AC-H1", PASS);
            assertEquals(NEEDS_HUMAN, task.deliveryVerdict());
            task = HumanEvidenceTestSupport.record(w, task, "AC-H2", SKIPPED);
            assertEquals(NEEDS_HUMAN, task.deliveryVerdict());
            task = HumanEvidenceTestSupport.record(w, task, "AC-H2", PASS);
            assertEquals(PASSED, task.deliveryVerdict()); assertEquals(NEEDS_HUMAN, task.run().verdict());
            assertEquals(ChangeState.DELIVERY_REVIEW, task.state()); assertNull(task.deliveryApproval());
            w.advance(id); task = w.get(id).task();
            assertEquals("pending", scm.find(task).orElseThrow().conclusion());
            task = w.decide(id, ChangeTestSupport.approveDelivery(task)).task();
            assertEquals(ChangeState.COMPLETED, task.state());
            approval = task.deliveryApproval().id();
            assertTrue(task.deliveryApprovedFor(task.spec().digest(), task.run().headSha()));
            assertEquals(3, task.deliveryApproval().judgmentRevision());
            task = HumanEvidenceTestSupport.record(w, task, "AC-H1", FAIL);
            assertEquals(FAILED, task.deliveryVerdict()); assertNull(task.deliveryApproval());
            assertEquals(ChangeState.FAILED, task.state());
            w.advance(id); task = w.get(id).task();
            assertEquals("failure", scm.find(task).orElseThrow().conclusion());
            int history = scm.history(id).size(); w.advance(id); assertEquals(history, scm.history(id).size());
            assertThrows(ChangeConflictException.class, () -> scm.publish(pending, "pending"));
            assertEquals("failure", scm.find(task).orElseThrow().conclusion());
            assertArrayEquals(original, Files.readAllBytes(task.run().evidencePath().resolve("result.json")));
            assertArrayEquals(diff, Files.readAllBytes(task.run().evidencePath().resolve("change.diff")));
        }
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, head); var task = w.get(id).task();
            assertEquals(4, task.humanReview().entries().size()); assertEquals(5, task.humanReview().judgments().size());
            assertEquals(FAILED, task.deliveryVerdict()); assertNull(task.deliveryApproval());
            assertTrue(w.get(id).events().stream().anyMatch(e -> e.type().equals("human.evidence_recorded") && e.payloadJson().contains(approval)));
            assertTrue(scm.history(id).stream().anyMatch(h -> h.conclusion().equals("success") && h.approvalId().equals(approval)));
            task = HumanEvidenceTestSupport.record(w, task, "AC-H1", PASS);
            assertEquals(ChangeState.DELIVERY_REVIEW, task.state()); assertNull(task.deliveryApproval());
            task = w.decide(id, ChangeTestSupport.approveDelivery(task)).task();
            assertEquals(ChangeState.COMPLETED, task.state());
        }
    }

    @Test void lowRiskNeedsNoDeliveryApprovalButHumanWriteDoesNotPublish() throws Exception {
        Path db = root.resolve("changes.db");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current"));
            var task = HumanEvidenceTestSupport.finished(w, root, "low", false, NEEDS_HUMAN, "PASS");
            // The fixture's open scope is MEDIUM. Use the explicit route configuration to test an approval-free policy.
            assertFalse(task.route().deliveryApprovalRequired());
            task = HumanEvidenceTestSupport.record(w, task, "AC-H1", PASS);
            task = HumanEvidenceTestSupport.record(w, task, "AC-H2", PASS);
            assertEquals(ChangeState.PUBLISHING, task.state()); assertTrue(scm.find(task).isEmpty());
            w.advance(task.id()); assertEquals(ChangeState.COMPLETED, w.get(task.id()).task().state());
        }
    }

    @ParameterizedTest @ValueSource(strings={"FAIL", "ERROR", "INCONCLUSIVE", "NOT_RUN"})
    void deterministicFailureOrMissingProofCannotBecomePassed(String status) throws Exception {
        Path db = root.resolve("changes.db");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current"));
            var task = HumanEvidenceTestSupport.finished(w, root, status, true, status.equals("FAIL") ? FAILED : INCOMPLETE, status);
            task = HumanEvidenceTestSupport.record(w, task, "AC-H1", PASS);
            task = HumanEvidenceTestSupport.record(w, task, "AC-H2", PASS);
            assertNotEquals(PASSED, task.deliveryVerdict());
            w.advance(task.id()); assertEquals("failure", scm.find(task).orElseThrow().conclusion());
        }
    }

    @Test void everyIdentityAndArtifactBoundaryRejectsWithoutAppending() throws Exception {
        Path db = root.resolve("changes.db"); var head = new AtomicReference<>("head-current");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, head);
            var task = HumanEvidenceTestSupport.finished(w, root, "stale", true, NEEDS_HUMAN, "PASS");
            var good = HumanEvidenceTestSupport.input(task, "AC-H1", PASS);
            for (String field : List.of("expectedVersion", "expectedSpecDigest", "expectedRunId", "expectedHeadSha", "expectedJudgmentRevision")) {
                var body = (com.fasterxml.jackson.databind.node.ObjectNode) ChangeJson.MAPPER.valueToTree(good);
                if (field.equals("expectedVersion") || field.equals("expectedJudgmentRevision")) body.put(field, 999);
                else body.put(field, "stale");
                var stale = ChangeJson.MAPPER.treeToValue(body, HumanEvidenceSubmission.class);
                assertThrows(ChangeConflictException.class, () -> w.recordHumanEvidence(task.id(), stale), field);
            }
            head.set("moved"); assertThrows(ChangeConflictException.class, () -> w.recordHumanEvidence(task.id(), good)); head.set("head-current");
            for (String ref : List.of("/etc/passwd", "https://example.com", "../other/result.json", "evidence:other-task")) {
                var bad = new HumanEvidenceSubmission(task.version(), task.spec().digest(), task.run().runId(), task.run().headSha(), 0,
                        "AC-H1", PASS, "observed", List.of(ref), "reviewer");
                assertThrows(ChangeValidationException.class, () -> w.recordHumanEvidence(task.id(), bad));
            }
            assertThrows(ChangeValidationException.class, () -> HumanEvidenceTestSupport.record(w, task, "AC-1", PASS));
            assertThrows(ChangeValidationException.class, () -> HumanEvidenceTestSupport.record(w, task, "UNKNOWN", PASS));
            assertNull(w.get(task.id()).task().humanReview());
            var updated = w.recordHumanEvidence(task.id(), good).task();
            assertThrows(ChangeConflictException.class, () -> w.recordHumanEvidence(task.id(), good));
            assertEquals(1, w.get(task.id()).task().humanReview().entries().size());
            var staleApproval = new ChangeDecision.ApproveDelivery(updated.version(), updated.spec().digest(), updated.run().headSha(),
                    updated.run().runId(), 0, "lead", "stale");
            assertThrows(ChangeConflictException.class, () -> w.decide(updated.id(), staleApproval));
        }
    }

    @Test void priorRunAndSpecJudgmentsCannotAuthorizeAnotherIdentity() throws Exception {
        Path db = root.resolve("changes.db");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current"));
            var task = HumanEvidenceTestSupport.finished(w, root, "identity", true, NEEDS_HUMAN, "PASS");
            task = HumanEvidenceTestSupport.record(w, task, "AC-H1", PASS);
            task = HumanEvidenceTestSupport.record(w, task, "AC-H2", PASS);
            task = w.decide(task.id(), ChangeTestSupport.approveDelivery(task)).task();
            var run = task.run();
            var nextRun = new RunRef("new-run", run.specDigest(), run.status(), run.verdict(), run.workspaceId(),
                    run.branch(), run.headSha(), run.evidencePath(), run.completedAt());
            var replaced = task.withExecution(ChangeState.DELIVERY_REVIEW, null, nextRun, Instant.now());
            store.update(task.version(), replaced, new ChangeEvent(0, task.id(), "test.new_run", "TEST", "test",
                    task.state(), replaced.state(), "{}", Instant.now()));
            assertEquals(INCOMPLETE, replaced.deliveryVerdict());
            assertFalse(replaced.deliveryApprovedFor(replaced.spec().digest(), replaced.run().headSha()));
            assertFalse(task.deliveryApprovedFor("different-spec", task.run().headSha()));
            assertFalse(task.deliveryApprovedFor(task.spec().digest(), "different-head"));
            var oldPage = HumanEvidenceTestSupport.input(task, "AC-H1", PASS);
            assertThrows(ChangeConflictException.class, () -> w.recordHumanEvidence(replaced.id(), oldPage));
            var currentPage = HumanEvidenceTestSupport.input(replaced, "AC-H1", PASS);
            assertThrows(ChangeConflictException.class, () -> w.recordHumanEvidence(replaced.id(), currentPage));
            assertThrows(ChangeConflictException.class, () -> w.advance(replaced.id()));
        }
    }

    @Test void legacyPendingCheckMigrationAndM2PublicationFailureRecoverWithoutDuplicateHistory() throws Exception {
        Path db = root.resolve("changes.db"); ChangeTaskId id;
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current"));
            var task = HumanEvidenceTestSupport.finished(w, root, "migration", true, NEEDS_HUMAN, "PASS");
            id = task.id(); w.advance(id);
        }
        MockScmAdapterTest.sql(db, "DROP TABLE mock_check_history");
        MockScmAdapterTest.sql(db, "ALTER TABLE change_tasks DROP COLUMN human_review_json");
        MockScmAdapterTest.sql(db, "ALTER TABLE change_tasks DROP COLUMN delivery_binding_json");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current")); var task = w.get(id).task();
            assertEquals(1, scm.history(id).size()); assertEquals("pending", scm.find(task).orElseThrow().conclusion());
            task = HumanEvidenceTestSupport.record(w, task, "AC-H1", PASS);
            task = HumanEvidenceTestSupport.record(w, task, "AC-H2", PASS);
            MockScmAdapterTest.sql(db,"CREATE TRIGGER fail_m2_event BEFORE INSERT ON change_events WHEN NEW.event_type = 'pr.check_published' BEGIN SELECT RAISE(ABORT, 'disk error'); END");
            assertThrows(IllegalStateException.class, () -> w.advance(id));
            assertEquals(2, scm.history(id).size());
            MockScmAdapterTest.sql(db, "DROP TRIGGER fail_m2_event");
        }
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current")); w.advance(id); w.advance(id);
            assertEquals(2, scm.history(id).size());
            var task = w.get(id).task(); assertEquals(2, task.judgmentRevision());
            task = w.decide(id, ChangeTestSupport.approveDelivery(task)).task();
            assertEquals(ChangeState.COMPLETED, task.state()); assertEquals(3, scm.history(id).size());
        }
    }

    @Test void upgradeReturnsLegacyUnboundPublishingApprovalToReview() throws Exception {
        Path db = root.resolve("changes.db");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current"));
            var ready = ChangeTestSupport.approveSpec(w, ChangeTestSupport.request(root, "legacy-approval", true));
            var task = ChangeTestSupport.finish(w, ready, root.resolve("runs"), PASSED);
            var legacy = new ApprovalRecord("old-approval", ApprovalRecord.Stage.DELIVERY, ApprovalRecord.Decision.APPROVED,
                    "lead", "legacy approved", task.spec().digest(), task.run().headSha(), Instant.now());
            var publishing = task.withDeliveryDecision(ChangeState.PUBLISHING, legacy, Instant.now());
            store.update(task.version(), publishing, new ChangeEvent(0, task.id(), "test.legacy_approval", "TEST", "test",
                    task.state(), publishing.state(), "{}", Instant.now()));
            w.advance(task.id()); w.advance(task.id());
            var review = w.get(task.id()).task();
            assertEquals(ChangeState.DELIVERY_REVIEW, review.state()); assertNull(review.deliveryApproval());
            assertTrue(scm.find(review).isEmpty());
            assertEquals(1, w.get(task.id()).events().stream().filter(e -> e.type().equals("delivery.approval_invalidated")).count());
            var completed = w.decide(review.id(), ChangeTestSupport.approveDelivery(review)).task();
            assertEquals(ChangeState.COMPLETED, completed.state());
        }
    }

    @Test void concurrentSubmissionsHaveOneWinnerAndEventRollbackLeavesNoEvidence() throws Exception {
        Path db = root.resolve("changes.db");
        try (var store = new SqliteChangeStore(db); var scm = new MockScmAdapter(db)) {
            var w = workflow(store, scm, new AtomicReference<>("head-current"));
            var task = HumanEvidenceTestSupport.finished(w, root, "cas", true, NEEDS_HUMAN, "PASS");
            var input = HumanEvidenceTestSupport.input(task, "AC-H1", PASS);
            MockScmAdapterTest.sql(db,"CREATE TRIGGER fail_human BEFORE INSERT ON change_events WHEN NEW.event_type = 'human.evidence_recorded' BEGIN SELECT RAISE(ABORT, 'disk error'); END");
            assertThrows(IllegalStateException.class, () -> w.recordHumanEvidence(task.id(), input));
            assertNull(w.get(task.id()).task().humanReview()); assertEquals(task.version(), w.get(task.id()).task().version());
            MockScmAdapterTest.sql(db, "DROP TRIGGER fail_human");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> submit = () -> { try { w.recordHumanEvidence(task.id(), input); return true; }
                    catch (ChangeConflictException e) { return false; } };
                var futures = pool.invokeAll(List.of(submit, submit));
                assertEquals(1, (futures.get(0).get() ? 1 : 0) + (futures.get(1).get() ? 1 : 0));
                assertEquals(1, w.get(task.id()).task().humanReview().entries().size());
            } finally { pool.shutdownNow(); }
        }
    }
}
