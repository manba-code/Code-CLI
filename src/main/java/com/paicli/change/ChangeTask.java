package com.paicli.change;

import java.time.Instant;
import java.util.Objects;

public record ChangeTask(
        ChangeTaskId id,
        String idempotencyKey,
        long version,
        ChangeState state,
        WorkItemRef source,
        RepositoryRef repository,
        String title,
        String requirement,
        String requesterId,
        String projectContext,
        String referencedContext,
        SpecRef spec,
        RiskAssessment risk,
        ExecutionRoute route,
        ApprovalRecord specApproval,
        ApprovalRecord deliveryApproval,
        WorkerClaimRef workerClaim,
        RunRef run,
        DraftJob draftJob,
        HumanReview humanReview,
        Instant createdAt,
        Instant updatedAt
) {
    public ChangeTask {
        id = Objects.requireNonNull(id, "id");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能为负数");
        }
        state = Objects.requireNonNull(state, "state");
        source = source == null ? new WorkItemRef("", "", "") : source;
        repository = Objects.requireNonNull(repository, "repository");
        title = requireText(title, "title");
        requirement = requireText(requirement, "requirement");
        requesterId = requireText(requesterId, "requesterId");
        projectContext = normalize(projectContext);
        referencedContext = normalize(referencedContext);
        if ((risk == null) != (route == null)) {
            throw new IllegalArgumentException("risk 和 route 必须同时存在或同时为空");
        }
        if (risk != null && risk.level() != route.riskLevel()) {
            throw new IllegalArgumentException("ExecutionRoute 必须绑定 RiskAssessment 的最终等级");
        }
        if (specApproval != null && specApproval.stage() != ApprovalRecord.Stage.SPEC) {
            throw new IllegalArgumentException("specApproval stage 必须是 SPEC");
        }
        if (deliveryApproval != null && deliveryApproval.stage() != ApprovalRecord.Stage.DELIVERY) {
            throw new IllegalArgumentException("deliveryApproval stage 必须是 DELIVERY");
        }
        if (workerClaim != null && state != ChangeState.RUNNING && state != ChangeState.VERIFYING) {
            throw new IllegalArgumentException("Worker claim 只能存在于 RUNNING 或 VERIFYING 状态");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public ChangeTask(ChangeTaskId id, String idempotencyKey, long version, ChangeState state,
                      WorkItemRef source, RepositoryRef repository, String title, String requirement,
                      String requesterId, String projectContext, String referencedContext, SpecRef spec,
                      RiskAssessment risk, ExecutionRoute route, ApprovalRecord specApproval,
                      ApprovalRecord deliveryApproval, WorkerClaimRef workerClaim, RunRef run, DraftJob draftJob,
                      Instant createdAt, Instant updatedAt) {
        this(id, idempotencyKey, version, state, source, repository, title, requirement, requesterId,
                projectContext, referencedContext, spec, risk, route, specApproval, deliveryApproval,
                workerClaim, run, draftJob, null, createdAt, updatedAt);
    }

    public long judgmentRevision() { return humanReview == null || humanReview.latest() == null ? 0 : humanReview.latest().revision(); }

    public com.paicli.spec.SpecRunResult.Verdict deliveryVerdict() {
        if (run == null) return null;
        if (humanReview == null || humanReview.latest() == null) return run.verdict();
        return humanReview.latest().matches(spec, run) ? humanReview.latest().verdict()
                : com.paicli.spec.SpecRunResult.Verdict.INCOMPLETE;
    }

    public ChangeTask withHumanReview(HumanReview review, ChangeState next, Instant at) {
        return new ChangeTask(id, idempotencyKey, version + 1, next, source, repository, title, requirement,
                requesterId, projectContext, referencedContext, spec, risk, route, specApproval,
                null, null, run, draftJob, review, createdAt, at);
    }

    /** Existing callers and pre-M1 rows have no Draft scheduling record. */
    public ChangeTask(ChangeTaskId id, String idempotencyKey, long version, ChangeState state,
                      WorkItemRef source, RepositoryRef repository, String title, String requirement,
                      String requesterId, String projectContext, String referencedContext, SpecRef spec,
                      RiskAssessment risk, ExecutionRoute route, ApprovalRecord specApproval,
                      ApprovalRecord deliveryApproval, WorkerClaimRef workerClaim, RunRef run,
                      Instant createdAt, Instant updatedAt) {
        this(id, idempotencyKey, version, state, source, repository, title, requirement, requesterId,
                projectContext, referencedContext, spec, risk, route, specApproval, deliveryApproval,
                workerClaim, run, null, createdAt, updatedAt);
    }

    public ChangeTask withDraftJob(DraftJob job, ChangeState next, Instant at) {
        return new ChangeTask(id, idempotencyKey, version + 1, next, source, repository, title,
                job.input().request(), requesterId, projectContext, referencedContext, spec, risk, route,
                specApproval, deliveryApproval, workerClaim, run, job, humanReview, createdAt, at);
    }

    public ChangeTask(
            ChangeTaskId id,
            String idempotencyKey,
            long version,
            ChangeState state,
            WorkItemRef source,
            RepositoryRef repository,
            String title,
            String requirement,
            String requesterId,
            String projectContext,
            String referencedContext,
            SpecRef spec,
            ApprovalRecord specApproval,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, idempotencyKey, version, state, source, repository, title, requirement,
                requesterId, projectContext, referencedContext, spec, null, null, specApproval,
                null, null, null, createdAt, updatedAt);
    }

    /** Phase 1-3 源码兼容构造；平台提交路径会始终保存真实 requesterId。 */
    public ChangeTask(
            ChangeTaskId id,
            String idempotencyKey,
            long version,
            ChangeState state,
            WorkItemRef source,
            RepositoryRef repository,
            String title,
            String requirement,
            String projectContext,
            String referencedContext,
            SpecRef spec,
            ApprovalRecord specApproval,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, idempotencyKey, version, state, source, repository, title, requirement,
                "unknown", projectContext, referencedContext, spec, specApproval, createdAt, updatedAt);
    }

    public ChangeTask transition(ChangeState next, Instant at) {
        return new ChangeTask(
                id, idempotencyKey, version + 1, next, source, repository, title, requirement,
                requesterId, projectContext, referencedContext, spec, risk, route, specApproval,
                deliveryApproval, workerClaim, run, draftJob, humanReview, createdAt, at);
    }

    public ChangeTask withDraft(SpecRef nextSpec, String nextRequirement, Instant at) {
        return new ChangeTask(
                id, idempotencyKey, version + 1, ChangeState.SPEC_REVIEW, source, repository, title,
                nextRequirement, requesterId, projectContext, referencedContext, nextSpec, null, null,
                null, null, null, null, draftJob, humanReview, createdAt, at);
    }

    public ChangeTask approve(
            SpecRef lockedSpec,
            RiskAssessment assessment,
            ExecutionRoute executionRoute,
            ApprovalRecord approval,
            Instant at
    ) {
        return new ChangeTask(
                id, idempotencyKey, version + 1, ChangeState.READY, source, repository, title,
                requirement, requesterId, projectContext, referencedContext, lockedSpec, assessment,
                executionRoute, approval, null, null, null, draftJob, humanReview, createdAt, at);
    }

    public ChangeTask rejectSpec(ApprovalRecord approval, Instant at) {
        return new ChangeTask(
                id, idempotencyKey, version + 1, ChangeState.REJECTED, source, repository, title,
                requirement, requesterId, projectContext, referencedContext, spec, null, null,
                approval, null, null, run, draftJob, humanReview, createdAt, at);
    }

    public ChangeTask withExecution(
            ChangeState next,
            WorkerClaimRef nextClaim,
            RunRef nextRun,
            Instant at
    ) {
        return new ChangeTask(
                id, idempotencyKey, version + 1, next, source, repository, title, requirement,
                requesterId, projectContext, referencedContext, spec, risk, route, specApproval,
                null, nextClaim, nextRun, draftJob, humanReview, createdAt, at);
    }

    public ChangeTask withDeliveryDecision(ChangeState next, ApprovalRecord approval, Instant at) {
        return new ChangeTask(
                id, idempotencyKey, version + 1, next, source, repository, title, requirement,
                requesterId, projectContext, referencedContext, spec, risk, route, specApproval,
                approval, null, run, draftJob, humanReview, createdAt, at);
    }

    /** Phase 5 发布 Adapter 必须用当前代码身份调用；任一绑定变化都会让旧审批失效。 */
    public boolean deliveryApprovedFor(String currentSpecDigest, String currentHeadSha) {
        if (deliveryApproval == null
                || deliveryApproval.decision() != ApprovalRecord.Decision.APPROVED
                || spec == null
                || run == null) {
            return false;
        }
        return spec.digest().equals(currentSpecDigest)
                && run.specDigest().equals(currentSpecDigest)
                && run.headSha().equals(currentHeadSha)
                && deliveryApproval.specDigest().equals(currentSpecDigest)
                && deliveryApproval.headSha().equals(currentHeadSha)
                && deliveryApproval.runId().equals(run.runId())
                && deliveryApproval.judgmentRevision() == judgmentRevision();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
