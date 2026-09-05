package com.paicli.change;

import java.time.Instant;
import java.util.Objects;

public record ApprovalRecord(
        String id,
        Stage stage,
        Decision decision,
        String approverId,
        String reason,
        String specDigest,
        String headSha,
        Instant createdAt,
        String runId,
        long judgmentRevision
) {
    public ApprovalRecord(String id, Stage stage, Decision decision, String approverId, String reason,
                          String specDigest, String headSha, Instant createdAt) {
        this(id, stage, decision, approverId, reason, specDigest, headSha, createdAt, "", 0);
    }
    public ApprovalRecord {
        runId = runId == null ? "" : runId;
        id = requireText(id, "id");
        stage = Objects.requireNonNull(stage, "stage");
        decision = Objects.requireNonNull(decision, "decision");
        approverId = requireText(approverId, "approverId");
        reason = reason == null ? "" : reason.trim();
        specDigest = specDigest == null ? "" : specDigest.trim();
        headSha = headSha == null ? "" : headSha.trim();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum Stage { SPEC, DELIVERY }

    public enum Decision { APPROVED, REJECTED }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
