package com.paicli.change;

import com.paicli.spec.SpecRunResult;
import java.time.Instant;
import java.util.List;

/** Append-only human observations and delivery judgments. The original RunRef is never rewritten. */
public record HumanReview(List<Entry> entries, List<Judgment> judgments) {
    public HumanReview {
        entries = entries == null ? List.of() : List.copyOf(entries);
        judgments = judgments == null ? List.of() : List.copyOf(judgments);
    }
    public record Entry(String id, String changeId, String specDigest, String runId, String headSha,
                        long judgmentRevision, String criterionId, SpecRunResult.HumanDecision decision,
                        String reason, List<String> artifactRefs, String actorId, String actorType,
                        Instant createdAt) {
        public Entry(String id, String changeId, String specDigest, String runId, String headSha,
                     long judgmentRevision, String criterionId, SpecRunResult.HumanDecision decision,
                     String reason, List<String> artifactRefs, String actorId, Instant createdAt) {
            this(id, changeId, specDigest, runId, headSha, judgmentRevision, criterionId, decision,
                    reason, artifactRefs, actorId, "LEGACY", createdAt);
        }
        public Entry {
            artifactRefs = List.copyOf(artifactRefs);
            actorType = actorType == null || actorType.isBlank() ? "LEGACY" : actorType;
        }
    }
    public record Judgment(long revision, String specDigest, String runId, String headSha,
                           SpecRunResult.Verdict verdict, List<SpecRunResult.CriterionResult> criterionResults,
                           Instant createdAt) {
        public Judgment { criterionResults = List.copyOf(criterionResults); }
        public boolean matches(SpecRef spec, RunRef run) {
            return spec != null && run != null && specDigest.equals(spec.digest())
                    && specDigest.equals(run.specDigest()) && runId.equals(run.runId()) && headSha.equals(run.headSha());
        }
    }
    public Judgment latest() { return judgments.isEmpty() ? null : judgments.get(judgments.size() - 1); }
}
