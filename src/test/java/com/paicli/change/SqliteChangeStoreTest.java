package com.paicli.change;

import com.paicli.spec.ChangeSpecModule;
import com.paicli.spec.ChangeSpecCodec;
import com.paicli.spec.SpecRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteChangeStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void draftCanBeApprovedAfterStoreIsReopened() throws Exception {
        Path database = tempDir.resolve("changes.db");
        ChangeTaskId id;
        try (SqliteChangeStore store = new SqliteChangeStore(database)) {
            ChangeWorkflow workflow = workflow(store);
            id = ChangeTestSupport.submitAndDraft(workflow, request());
            assertEquals(ChangeState.SPEC_REVIEW, workflow.get(id).task().state());
        }

        try (SqliteChangeStore reopened = new SqliteChangeStore(database)) {
            ChangeWorkflow workflow = workflow(reopened);
            ChangeTask persisted = workflow.get(id).task();
            ChangeTaskView approved = workflow.decide(id, new ChangeDecision.ApproveSpec(
                    persisted.version(), persisted.spec().digest(), "lead", "approved after restart"));

            assertEquals(ChangeState.READY, approved.task().state());
            assertEquals(4, approved.events().size());
            assertEquals("spec.approved", approved.events().get(3).type());
        }
    }

    @Test
    void failedEventAppendRollsBackStateUpdate() throws Exception {
        Path database = tempDir.resolve("rollback.db");
        try (SqliteChangeStore store = new SqliteChangeStore(database)) {
            ChangeWorkflow workflow = workflow(store);
            ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, request());
            ChangeTask before = workflow.get(id).task();
            ChangeTask next = before.transition(ChangeState.FAILED, NOW.plusSeconds(1));
            ChangeEvent invalidEvent = new ChangeEvent(
                    0L,
                    id,
                    "change.failed",
                    "SYSTEM",
                    "test",
                    before.state(),
                    next.state(),
                    "not-json",
                    NOW.plusSeconds(1));

            assertThrows(IllegalStateException.class,
                    () -> store.update(before.version(), next, invalidEvent));
            ChangeTask after = store.find(id).orElseThrow();
            assertEquals(before.version(), after.version());
            assertEquals(before.state(), after.state());
        }
    }

    @Test
    void executionClaimAndRunReferenceSurviveReopen() throws Exception {
        Path database = tempDir.resolve("execution.db");
        ChangeTaskId id;
        try (SqliteChangeStore store = new SqliteChangeStore(database)) {
            DefaultChangeWorkflow workflow = (DefaultChangeWorkflow) workflow(store);
            id = ChangeTestSupport.submitAndDraft(workflow, request());
            ChangeTask review = workflow.get(id).task();
            ChangeTask ready = workflow.decide(id, new ChangeDecision.ApproveSpec(
                    review.version(), review.spec().digest(), "lead", "ok")).task();
            workflow.queueForExecution(id, ready.version());
            ChangeExecutionControl.ExecutionLease lease = workflow.claimExecution(id);
            lease.verificationStarted();
            Path evidence = java.nio.file.Files.createDirectories(tempDir.resolve("run-evidence"));
            lease.complete(new RunRef(
                    "run-sqlite",
                    review.spec().digest(),
                    SpecRunResult.Status.FINISHED,
                    SpecRunResult.Verdict.PASSED,
                    "workspace-sqlite",
                    "paichange/sqlite",
                    "abcdef0123456789",
                    evidence,
                    NOW));
        }

        try (SqliteChangeStore reopened = new SqliteChangeStore(database)) {
            ChangeTask task = reopened.find(id).orElseThrow();
            assertEquals(ChangeState.DELIVERY_REVIEW, task.state());
            assertEquals("run-sqlite", task.run().runId());
            assertEquals("workspace-sqlite", task.run().workspaceId());
            assertEquals(SpecRunResult.Verdict.PASSED, task.run().verdict());
            assertEquals(RiskLevel.MEDIUM, task.risk().level());
            assertEquals(task.risk().level(), task.route().riskLevel());
            assertEquals(java.util.List.of("backend"), task.source().labels());
            assertEquals("normal", task.source().priority());

            ChangeWorkflow workflow = workflow(reopened);
            ChangeTask approved = workflow.decide(id, new ChangeDecision.ApproveDelivery(
                    task.version(), task.spec().digest(), task.run().headSha(), task.run().runId(), task.judgmentRevision(),
                        "delivery-lead", "evidence complete")).task();
            assertEquals(ChangeState.PUBLISHING, approved.state());
        }

        try (SqliteChangeStore reopenedAgain = new SqliteChangeStore(database)) {
            ChangeTask task = reopenedAgain.find(id).orElseThrow();
            assertEquals(ChangeState.PUBLISHING, task.state());
            assertEquals(ApprovalRecord.Stage.DELIVERY, task.deliveryApproval().stage());
            assertEquals("abcdef0123456789", task.deliveryApproval().headSha());
            assertEquals(task.spec().digest(), task.deliveryApproval().specDigest());
        }
    }

    @Test
    void toolPolicyApprovalsAndRedactedEventsSurviveReopen() throws Exception {
        Path database = tempDir.resolve("tool-governance.db");
        ChangeTaskId id;
        String approvalId = "tool_approval_sql";
        String projectId;
        Instant expires = NOW.plusSeconds(60);
        try (SqliteChangeStore store = new SqliteChangeStore(database)) {
            DefaultChangeWorkflow workflow = (DefaultChangeWorkflow) workflow(store);
            id = ChangeTestSupport.submitAndDraft(workflow, request());
            ChangeTask review = workflow.get(id).task();
            ChangeTask ready = workflow.decide(id, new ChangeDecision.ApproveSpec(
                    review.version(), review.spec().digest(), "lead", "ok")).task();
            projectId = ChangeProject.id(ready.repository());
            ProjectToolPolicy.Rule rule = new ProjectToolPolicy.Rule("approve-write", Set.of(
                    ExecutionRoute.ToolPolicyProfile.RESTRICTED), ProjectToolPolicy.Effect.REQUIRE_APPROVAL,
                    "write_file", "", "", "", tempDir.toString(), Map.of("path", "src/.*"));
            assertEquals(2, store.updatePolicy(projectId, 1, List.of(rule), NOW, "admin", "HUMAN").version());
            ToolApproval pending = new ToolApproval(approvalId, id, projectId, "run-sql", "call-sql",
                    "write_file", "sha256", "{\"path\":\"src/X.java\",\"content\":\"<redacted payload>\"}",
                    tempDir.toString(), ready.spec().specId(), ready.spec().revision(), ready.spec().digest(),
                    ExecutionRoute.ToolPolicyProfile.RESTRICTED, 2, "approve-write", ToolApproval.Status.PENDING,
                    "", "", "", false, NOW, null, expires);
            store.createApproval(pending);
            store.updateApproval(pending.decide(ToolApproval.Status.APPROVED, "reviewed", "approver",
                    "HUMAN", false, NOW.plusSeconds(1)), ToolApproval.Status.PENDING);
        }

        try (SqliteChangeStore reopened = new SqliteChangeStore(database)) {
            assertEquals(2, reopened.policy(projectId).version());
            ToolApproval approval = reopened.findApproval(approvalId).orElseThrow();
            assertEquals(ToolApproval.Status.APPROVED, approval.status());
            assertEquals("approver", approval.approverId());
            assertEquals(1, reopened.approvals(id).size());
            var events = reopened.events(id);
            assertEquals("tool.approval_approved", events.get(events.size() - 1).type());
            assertFalse(events.get(events.size() - 1).payloadJson().contains("secret-value"));
        }
    }

    private ChangeWorkflow workflow(SqliteChangeStore store) {
        ChangeSpecModule specs = new ChangeSpecModule() {
            @Override
            public SpecDraft generateDraft(ChangeContext context) throws java.io.IOException {
                String content = DefaultChangeWorkflowTest.validDocumentForTests(
                        context.specId(), context.revision());
                var document = new ChangeSpecCodec().decode(content);
                Path draftPath = tempDir.resolve(context.specId() + "-r" + context.revision() + ".draft.md");
                java.nio.file.Files.writeString(draftPath, content);
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

    private static ChangeRequest request() {
        return new ChangeRequest(
                "sqlite-request",
                new WorkItemRef(
                        "mock-gitlab", "ISSUE-2", "", java.util.List.of("backend"), "normal"),
                new RepositoryRef("group/project", "main"),
                "修复窗口限流",
                "半开窗口边界必须正确",
                "requester",
                "",
                "");
    }
}
