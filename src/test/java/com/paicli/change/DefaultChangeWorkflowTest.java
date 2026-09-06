package com.paicli.change;

import com.paicli.spec.ChangeSpecModule;
import com.paicli.spec.ChangeSpecCodec;
import com.paicli.spec.SpecRunResult;
import com.paicli.config.PaiCliConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultChangeWorkflowTest {
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void submitsIdempotentlyAndApprovesOnlyCurrentDraft() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        FakeSpecModule specs = new FakeSpecModule(tempDir);
        ChangeWorkflow workflow = workflow(store, specs);
        ChangeRequest request = request("request-1");

        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, request);
        ChangeTaskId repeated = ChangeTestSupport.submitAndDraft(workflow, request);
        ChangeTaskView review = workflow.get(id);

        assertEquals(id, repeated);
        assertEquals(ChangeState.SPEC_REVIEW, review.task().state());
        assertEquals(2L, review.task().version());
        assertEquals(3, review.events().size());
        assertEquals(List.of("change.created", "spec.drafting_started", "spec.draft_generated"),
                review.events().stream().map(ChangeEvent::type).toList());

        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveSpec(1L, review.task().spec().digest(), "lead", "ok")));
        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveSpec(2L, "old-digest", "lead", "ok")));

        ChangeTaskView ready = workflow.decide(id,
                new ChangeDecision.ApproveSpec(2L, review.task().spec().digest(), "lead", "范围与验收明确"));

        assertEquals(ChangeState.READY, ready.task().state());
        assertEquals(3L, ready.task().version());
        assertTrue(ready.task().spec().locked());
        assertEquals("lead", ready.task().specApproval().approverId());
        assertEquals(1, specs.lockCalls);
        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.RejectSpec(3L, "digest-r1", "lead", "late")));
    }

    @Test
    void supplementCreatesNewRevisionAndInvalidatesOldDigest() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        FakeSpecModule specs = new FakeSpecModule(tempDir);
        ChangeWorkflow workflow = workflow(store, specs);
        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, request("request-2"));

        ChangeTaskView secondReview = workflow.decide(id,
                new ChangeDecision.SupplementSpec(
                        2L, workflow.get(id).task().spec().digest(), "owner", "不得修改公开接口"));

        secondReview = ChangeTestSupport.draft(workflow, id);
        assertEquals(ChangeState.SPEC_REVIEW, secondReview.task().state());
        assertEquals(2, secondReview.task().spec().revision());
        String secondDigest = secondReview.task().spec().digest();
        assertTrue(secondReview.task().requirement().contains("不得修改公开接口"));
        assertEquals(6, secondReview.events().size());
        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveSpec(4L, "digest-r1", "lead", "stale page")));
        assertTrue(!secondDigest.isBlank());
    }

    @Test
    void draftFailureIsPersistedAsFailedInsteadOfPretendingReviewExists() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        ChangeSpecModule failing = new ChangeSpecModule() {
            @Override
            public SpecDraft generateDraft(ChangeContext context) throws java.io.IOException {
                throw new java.io.IOException("model unavailable");
            }

            @Override
            public LockedSpec lockConfirmed(SpecDraft draft, String expectedDigest) {
                throw new AssertionError("must not lock");
            }
        };
        ChangeWorkflow workflow = workflow(store, failing);

        ChangeTaskView result = workflow.get(ChangeTestSupport.submitAndDraft(workflow, request("request-3")));

        assertEquals(ChangeState.FAILED, result.task().state());
        assertEquals("spec.draft_failed", result.events().get(result.events().size() - 1).type());
        assertTrue(result.events().get(result.events().size() - 1).payloadJson().contains("model unavailable"));
    }

    @Test
    void executionClaimIsExclusiveAndPersistsRunAssociation() throws Exception {
        InMemoryChangeStore store = new InMemoryChangeStore();
        DefaultChangeWorkflow workflow = workflow(store, new FakeSpecModule(tempDir));
        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, request("request-worker"));
        ChangeTask review = workflow.get(id).task();
        ChangeTask ready = workflow.decide(id, new ChangeDecision.ApproveSpec(
                review.version(), review.spec().digest(), "lead", "ok")).task();
        workflow.queueForExecution(id, ready.version());

        ChangeExecutionControl.ExecutionLease execution = workflow.claimExecution(id);
        assertThrows(ChangeConflictException.class, () -> workflow.claimExecution(id));
        execution.started("workspace-1");
        execution.verificationStarted();
        Path evidence = java.nio.file.Files.createDirectories(tempDir.resolve("evidence/run-1"));
        execution.complete(new RunRef(
                "run-1",
                review.spec().digest(),
                SpecRunResult.Status.FINISHED,
                SpecRunResult.Verdict.PASSED,
                "workspace-1",
                "paichange/change_worker/one",
                "0123456789abcdef",
                evidence,
                NOW));

        ChangeTaskView completed = workflow.get(id);
        assertEquals(ChangeState.DELIVERY_REVIEW, completed.task().state());
        assertEquals("run-1", completed.task().run().runId());
        assertEquals("0123456789abcdef", completed.task().run().headSha());
        assertEquals(List.of(
                        "execution.queued",
                        "execution.claimed",
                        "execution.started",
                        "verification.started",
                        "execution.completed"),
                completed.events().stream().map(ChangeEvent::type).skip(4).toList());
    }

    @Test
    void recoversOrCancelsAbandonedExecutionWithExplicitEvents() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        DefaultChangeWorkflow workflow = workflow(store, new FakeSpecModule(tempDir));
        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, request("request-recovery"));
        ChangeTask review = workflow.get(id).task();
        ChangeTask ready = workflow.decide(id, new ChangeDecision.ApproveSpec(
                review.version(), review.spec().digest(), "lead", "ok")).task();
        workflow.queueForExecution(id, ready.version());
        workflow.claimExecution(id).started("workspace-abandoned");

        workflow.recoverExecution(id, "process restarted");
        assertEquals(ChangeState.QUEUED, workflow.get(id).task().state());
        assertEquals("execution.recovered", workflow.get(id).events().get(7).type());

        workflow.cancelExecution(id, "operator canceled");
        ChangeTaskView canceled = workflow.get(id);
        assertEquals(ChangeState.CANCELED, canceled.task().state());
        assertEquals("change.canceled", canceled.events().get(8).type());
    }

    @Test
    void mediumRiskRequesterCannotApproveOwnSpecByDefault() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        DefaultChangeWorkflow workflow = workflow(store, new FakeSpecModule(tempDir));
        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, request("self-spec"));
        ChangeTask review = workflow.get(id).task();

        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveSpec(
                        review.version(), review.spec().digest(), "requester", "self approve")));

        ChangeTask unchanged = workflow.get(id).task();
        assertEquals(ChangeState.SPEC_REVIEW, unchanged.state());
        assertEquals(review.version(), unchanged.version());
        assertTrue(!unchanged.spec().locked());
    }

    @Test
    void selfApprovalRuleCanBeDisabledForMediumRisk() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        PaiCliConfig config = new PaiCliConfig();
        config.getPaiChange().setForbidRequesterSelfApprovalForMediumAndHigh(false);
        DefaultChangeWorkflow workflow = workflow(store, new FakeSpecModule(tempDir), config);
        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, request("self-spec-configured"));
        ChangeTask review = workflow.get(id).task();

        ChangeTask ready = workflow.decide(id, new ChangeDecision.ApproveSpec(
                review.version(), review.spec().digest(), "requester", "policy allows")).task();

        assertEquals(ChangeState.READY, ready.state());
        assertEquals(RiskLevel.MEDIUM, ready.risk().level());
        assertEquals(ready.risk().level(), ready.route().riskLevel());
    }

    @Test
    void deliveryApprovalRejectsStaleBindingsAndHighRiskSelfApproval() throws Exception {
        InMemoryChangeStore store = new InMemoryChangeStore();
        DefaultChangeWorkflow workflow = workflow(store, new FakeSpecModule(tempDir));
        ChangeRequest highRisk = new ChangeRequest(
                "delivery-high",
                new WorkItemRef("mock-gitlab", "ISSUE-HIGH", "", List.of("high-risk"), "urgent"),
                new RepositoryRef("group/project", "main"),
                "修改关键认证逻辑",
                "修改认证逻辑",
                "requester",
                "",
                "");
        ChangeTaskId id = ChangeTestSupport.submitAndDraft(workflow, highRisk);
        ChangeTask review = workflow.get(id).task();
        ChangeTask ready = workflow.decide(id, new ChangeDecision.ApproveSpec(
                review.version(), review.spec().digest(), "lead", "spec ok")).task();
        assertEquals(RiskLevel.HIGH, ready.risk().level());

        ChangeTask delivery = completeForDelivery(workflow, ready, "delivery-run", "head-current");
        assertEquals(ChangeState.DELIVERY_REVIEW, delivery.state());
        assertEquals(null, delivery.deliveryApproval());

        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveDelivery(
                        delivery.version() - 1, delivery.spec().digest(), delivery.run().headSha(),
                        delivery.run().runId(), delivery.judgmentRevision(),
                        "lead", "stale version")));
        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveDelivery(
                        delivery.version(), "stale-spec", delivery.run().headSha(),
                        delivery.run().runId(), delivery.judgmentRevision(),
                        "lead", "stale spec")));
        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveDelivery(
                        delivery.version(), delivery.spec().digest(), "head-stale",
                        delivery.run().runId(), delivery.judgmentRevision(),
                        "lead", "stale head")));
        assertThrows(ChangeConflictException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveDelivery(
                        delivery.version(), delivery.spec().digest(), delivery.run().headSha(),
                        delivery.run().runId(), delivery.judgmentRevision(),
                        "requester", "self approve")));
        assertThrows(ChangeForbiddenException.class, () -> workflow.decide(id,
                new ChangeDecision.ApproveDelivery(
                        delivery.version(), delivery.spec().digest(), delivery.run().headSha(),
                        delivery.run().runId(), delivery.judgmentRevision(),
                        "lead", "same actor as spec approval")));

        ChangeTask publishing = workflow.decide(id, new ChangeDecision.ApproveDelivery(
                delivery.version(), delivery.spec().digest(), delivery.run().headSha(),
                        delivery.run().runId(), delivery.judgmentRevision(),
                        "delivery-lead", "delivery ok")).task();

        assertEquals(ChangeState.PUBLISHING, publishing.state());
        assertEquals(ApprovalRecord.Stage.DELIVERY, publishing.deliveryApproval().stage());
        assertEquals(delivery.spec().digest(), publishing.deliveryApproval().specDigest());
        assertEquals(delivery.run().headSha(), publishing.deliveryApproval().headSha());
        assertTrue(publishing.deliveryApprovedFor(delivery.spec().digest(), delivery.run().headSha()));
        assertTrue(!publishing.deliveryApprovedFor(delivery.spec().digest(), "head-changed"));
        assertTrue(!publishing.deliveryApprovedFor("spec-changed", delivery.run().headSha()));
        assertEquals("delivery.approved",
                workflow.get(id).events().get(workflow.get(id).events().size() - 1).type());
    }

    private DefaultChangeWorkflow workflow(ChangeStore store, ChangeSpecModule specs) {
        return workflow(store, specs, new PaiCliConfig());
    }

    private DefaultChangeWorkflow workflow(
            ChangeStore store,
            ChangeSpecModule specs,
            PaiCliConfig config
    ) {
        return new DefaultChangeWorkflow(
                store,
                (ChangeEventStore) store,
                specs,
                config,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ChangeTask completeForDelivery(
            DefaultChangeWorkflow workflow,
            ChangeTask ready,
            String runId,
            String headSha
    ) throws Exception {
        workflow.queueForExecution(ready.id(), ready.version());
        ChangeExecutionControl.ExecutionLease execution = workflow.claimExecution(ready.id());
        execution.verificationStarted();
        Path evidence = java.nio.file.Files.createDirectories(tempDir.resolve("evidence/" + runId));
        execution.complete(new RunRef(
                runId,
                ready.spec().digest(),
                SpecRunResult.Status.FINISHED,
                SpecRunResult.Verdict.PASSED,
                "workspace-" + runId,
                "paichange/" + runId,
                headSha,
                evidence,
                NOW));
        return workflow.get(ready.id()).task();
    }

    private static ChangeRequest request(String key) {
        return new ChangeRequest(
                key,
                new WorkItemRef("mock-gitlab", "ISSUE-1", "https://example.test/issues/1"),
                new RepositoryRef("group/project", "main"),
                "修复预算分配",
                "修复余数分配错误",
                "requester",
                "project context",
                "");
    }

    private static final class FakeSpecModule implements ChangeSpecModule {
        private final Path root;
        private final List<ChangeContext> contexts = new ArrayList<>();
        private final ChangeSpecCodec codec = new ChangeSpecCodec();
        private int lockCalls;

        private FakeSpecModule(Path root) {
            this.root = root;
        }

        @Override
        public SpecDraft generateDraft(ChangeContext context) throws java.io.IOException {
            contexts.add(context);
            String content = validDocumentForTests(context.specId(), context.revision());
            var document = codec.decode(content);
            Path path = root.resolve(context.specId() + "-r" + context.revision() + ".draft.md");
            java.nio.file.Files.writeString(path, content);
            return new SpecDraft(
                    path,
                    context.specId(),
                    context.revision(),
                    document.specDigest(),
                    5L,
                    SpecRunResult.LlmUsage.empty());
        }

        @Override
        public LockedSpec lockConfirmed(SpecDraft draft, String expectedDigest) {
            lockCalls++;
            return new LockedSpec(
                    root.resolve(draft.specId() + "-r" + draft.revision() + ".md"),
                    draft.specId(),
                    draft.revision(),
                    draft.specDigest());
        }
    }

    static String validDocumentForTests(String id, int revision) {
        return """
                ---
                schema: paicli/change-spec/v1
                id: %s
                revision: %d
                title: 修复问题
                intent:
                  goal: 修复问题
                  non_goals: []
                scope:
                  mode: open
                  include: []
                  exclude: []
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: 问题已修复
                    oracle:
                      type: human
                      verifiers: []
                  - id: AC-SCOPE
                    kind: scope
                    statement: 修改不得超出声明的 Scope
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-SCOPE
                    type: path_scope
                ---
                """.formatted(id, revision);
    }
}
