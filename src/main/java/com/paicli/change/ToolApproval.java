package com.paicli.change;

import java.time.Instant;
import java.util.Objects;

/** Persisted identity and decision for one exact Worker tool call. Raw arguments are never stored. */
public record ToolApproval(
        String id,
        ChangeTaskId changeId,
        String projectId,
        String runId,
        String callId,
        String toolName,
        String argumentsDigest,
        String argumentsPreview,
        String workingDirectory,
        String specId,
        int specRevision,
        String specDigest,
        ExecutionRoute.ToolPolicyProfile profile,
        long policyVersion,
        String ruleId,
        Status status,
        String decisionReason,
        String approverId,
        String approverType,
        boolean approverLocalTrusted,
        Instant createdAt,
        Instant decidedAt,
        Instant expiresAt
) {
    public ToolApproval {
        id = text(id, "id");
        changeId = Objects.requireNonNull(changeId, "changeId");
        projectId = text(projectId, "projectId");
        runId = text(runId, "runId");
        callId = text(callId, "callId");
        toolName = text(toolName, "toolName");
        argumentsDigest = text(argumentsDigest, "argumentsDigest");
        argumentsPreview = argumentsPreview == null ? "" : argumentsPreview;
        workingDirectory = text(workingDirectory, "workingDirectory");
        specId = text(specId, "specId");
        if (specRevision < 1) throw new IllegalArgumentException("specRevision 必须 >= 1");
        specDigest = text(specDigest, "specDigest");
        profile = Objects.requireNonNull(profile, "profile");
        if (policyVersion < 1) throw new IllegalArgumentException("policyVersion 必须 >= 1");
        ruleId = text(ruleId, "ruleId");
        status = Objects.requireNonNull(status, "status");
        decisionReason = decisionReason == null ? "" : decisionReason.trim();
        approverId = approverId == null ? "" : approverId.trim();
        approverType = approverType == null ? "" : approverType.trim();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public ToolApproval decide(Status next, String reason, String actorId, String actorType,
                               boolean localTrusted, Instant now) {
        if (status != Status.PENDING) throw new ChangeConflictException("工具审批已经结束");
        if (next != Status.APPROVED && next != Status.REJECTED) {
            throw new IllegalArgumentException("人工决策只能是 APPROVED 或 REJECTED");
        }
        return finish(next, reason, actorId, actorType, localTrusted, now);
    }

    public ToolApproval expire(Status next, String reason, Instant now) {
        if (status != Status.PENDING) return this;
        if (!java.util.Set.of(Status.TIMED_OUT, Status.CANCELED, Status.STALE, Status.INTERRUPTED).contains(next)) {
            throw new IllegalArgumentException("不是安全失效状态");
        }
        return finish(next, reason, "change-worker", "SYSTEM", false, now);
    }

    public ToolApproval invalidate(String reason, Instant now) {
        if (status != Status.APPROVED) throw new ChangeConflictException("只有已批准调用可以在执行前失效");
        return finish(Status.STALE, reason, approverId, approverType, approverLocalTrusted, now);
    }

    private ToolApproval finish(Status next, String reason, String actor, String actorType,
                                boolean localTrusted, Instant now) {
        return new ToolApproval(id, changeId, projectId, runId, callId, toolName, argumentsDigest,
                argumentsPreview, workingDirectory, specId, specRevision, specDigest, profile,
                policyVersion, ruleId, next, reason, actor, actorType, localTrusted,
                createdAt, Objects.requireNonNull(now, "now"), expiresAt);
    }

    public enum Status { PENDING, APPROVED, REJECTED, TIMED_OUT, CANCELED, STALE, INTERRUPTED }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return normalized;
    }
}
