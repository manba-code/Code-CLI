package com.paicli.change;

import com.paicli.spec.SpecRunResult;
import java.util.List;
import java.util.Objects;

/** One criterion per append/correction, bound to the exact page snapshot. */
public record HumanEvidenceSubmission(long expectedVersion, String expectedSpecDigest, String expectedRunId,
                                      String expectedHeadSha, long expectedJudgmentRevision, String criterionId,
                                      SpecRunResult.HumanDecision decision, String reason,
                                      List<String> artifactRefs, String actorId, String actorType) {
    public HumanEvidenceSubmission(long expectedVersion, String expectedSpecDigest, String expectedRunId,
                                   String expectedHeadSha, long expectedJudgmentRevision, String criterionId,
                                   SpecRunResult.HumanDecision decision, String reason,
                                   List<String> artifactRefs, String actorId) {
        this(expectedVersion, expectedSpecDigest, expectedRunId, expectedHeadSha, expectedJudgmentRevision,
                criterionId, decision, reason, artifactRefs, actorId, "LEGACY");
    }

    public HumanEvidenceSubmission {
        if (expectedVersion < 0 || expectedJudgmentRevision < 0) throw new IllegalArgumentException("版本必须非负");
        for (String value : List.of(expectedSpecDigest, expectedRunId, expectedHeadSha, criterionId, reason, actorId)) {
            if (value.isBlank()) throw new IllegalArgumentException("身份、Criterion、理由和操作者必填");
        }
        Objects.requireNonNull(decision, "decision");
        if (actorType == null || actorType.isBlank()) throw new IllegalArgumentException("操作者类型必填");
        if (reason.length() > 16000 || actorId.length() > 200) throw new IllegalArgumentException("人工说明过长");
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
        if (artifactRefs.size() > 32 || artifactRefs.stream().anyMatch(r -> r == null || r.length() > 300)) {
            throw new IllegalArgumentException("Artifact 引用过多或过长");
        }
    }
}
