package com.paicli.change;

import java.util.Objects;

public sealed interface ChangeDecision
        permits ChangeDecision.ApproveSpec, ChangeDecision.SupplementSpec, ChangeDecision.RejectSpec,
        ChangeDecision.ApproveDelivery, ChangeDecision.RejectDelivery {

    long expectedVersion();

    String actorId();

    record ApproveSpec(long expectedVersion, String expectedDigest, String actorId, String reason)
            implements ChangeDecision {
        public ApproveSpec {
            expectedDigest = requireText(expectedDigest, "expectedDigest");
            actorId = requireText(actorId, "actorId");
            reason = normalize(reason);
        }
    }

    record SupplementSpec(
            long expectedVersion,
            String expectedDigest,
            String actorId,
            String supplement
    ) implements ChangeDecision {
        public SupplementSpec {
            expectedDigest = requireText(expectedDigest, "expectedDigest");
            actorId = requireText(actorId, "actorId");
            supplement = requireText(supplement, "supplement");
        }
    }

    record RejectSpec(long expectedVersion, String expectedDigest, String actorId, String reason)
            implements ChangeDecision {
        public RejectSpec {
            expectedDigest = requireText(expectedDigest, "expectedDigest");
            actorId = requireText(actorId, "actorId");
            reason = normalize(reason);
        }
    }

    record ApproveDelivery(
            long expectedVersion,
            String expectedSpecDigest,
            String expectedHeadSha,
            String expectedRunId,
            long expectedJudgmentRevision,
            String actorId,
            String reason
    ) implements ChangeDecision {
        public ApproveDelivery {
            expectedSpecDigest = requireText(expectedSpecDigest, "expectedSpecDigest");
            expectedHeadSha = requireText(expectedHeadSha, "expectedHeadSha");
            expectedRunId = requireText(expectedRunId, "expectedRunId");
            if (expectedJudgmentRevision < 0) throw new IllegalArgumentException("判断版本必须非负");
            actorId = requireText(actorId, "actorId");
            reason = normalize(reason);
        }
    }

    record RejectDelivery(
            long expectedVersion,
            String expectedSpecDigest,
            String expectedHeadSha,
            String expectedRunId,
            long expectedJudgmentRevision,
            String actorId,
            String reason
    ) implements ChangeDecision {
        public RejectDelivery {
            expectedSpecDigest = requireText(expectedSpecDigest, "expectedSpecDigest");
            expectedHeadSha = requireText(expectedHeadSha, "expectedHeadSha");
            expectedRunId = requireText(expectedRunId, "expectedRunId");
            if (expectedJudgmentRevision < 0) throw new IllegalArgumentException("判断版本必须非负");
            actorId = requireText(actorId, "actorId");
            reason = normalize(reason);
        }
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
