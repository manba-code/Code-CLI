package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.spec.ChangeSpec;
import com.paicli.spec.SpecRunResult;
import java.time.Instant;
import java.util.*;
import static com.paicli.spec.SpecRunResult.*;

/** Pure reduction over the locked criteria, original verification and latest applicable human entries. */
public final class DeliveryJudgmentReducer {
    private DeliveryJudgmentReducer() { }

    public static HumanReview.Judgment reduce(ChangeTask task, ChangeSpec spec, JsonNode original,
                                               List<HumanReview.Entry> entries, long revision, Instant at) {
        Map<String, JsonNode> originals = new HashMap<>();
        for (JsonNode result : original.path("criterionResults")) {
            if (originals.put(result.path("criterionId").asText(), result) != null) {
                throw new ChangeValidationException("重复的原始 Criterion Result");
            }
        }
        Map<String, String> verifierStatuses = new HashMap<>();
        JsonNode attempts = original.path("verificationAttempts");
        if (attempts.isArray() && !attempts.isEmpty()) {
            for (JsonNode v : attempts.get(attempts.size() - 1).path("verifierResults")) {
                String id = v.path("verifierId").asText();
                if (verifierStatuses.put(id, v.path("status").asText()) != null) {
                    throw new ChangeValidationException("重复的原始 Verifier Result");
                }
            }
        }
        Map<String, HumanReview.Entry> latest = new HashMap<>();
        for (var entry : entries) {
            if (entry.changeId().equals(task.id().value()) && entry.specDigest().equals(task.spec().digest())
                    && entry.runId().equals(task.run().runId()) && entry.headSha().equals(task.run().headSha())) {
                latest.put(entry.criterionId(), entry);
            }
        }
        List<CriterionResult> results = new ArrayList<>();
        for (var criterion : spec.acceptance()) {
            if (criterion.oracle().type() == ChangeSpec.OracleType.HUMAN) {
                var entry = latest.get(criterion.id());
                CriterionStatus status = entry == null || entry.decision() == HumanDecision.SKIPPED
                        ? CriterionStatus.NOT_RUN : entry.decision() == HumanDecision.PASS ? CriterionStatus.PASS : CriterionStatus.FAIL;
                results.add(new CriterionResult(criterion.id(), status, entry == null ? List.of() : List.of(entry.id()),
                        Judge.HUMAN, entry == null ? "尚无当前运行的人工验收" : entry.reason()));
            } else {
                JsonNode result = originals.get(criterion.id());
                CriterionStatus status = CriterionStatus.INCONCLUSIVE;
                List<String> evidence = new ArrayList<>();
                String reason = "缺少有效的原始确定性结果";
                if (result != null && result.path("judge").asText().equalsIgnoreCase("VERIFIER")) {
                    try { status = CriterionStatus.valueOf(result.path("status").asText()); }
                    catch (IllegalArgumentException ignored) { /* Fail closed for unknown status. */ }
                    result.path("evidenceIds").forEach(id -> evidence.add(id.asText()));
                    reason = result.path("reason").asText();
                }
                boolean fail = status == CriterionStatus.FAIL;
                boolean incomplete = status != CriterionStatus.PASS;
                for (String verifierId : criterion.oracle().verifiers()) {
                    String verifier = verifierStatuses.get(verifierId);
                    fail |= "FAIL".equals(verifier);
                    incomplete |= !"PASS".equals(verifier);
                }
                status = fail ? CriterionStatus.FAIL : incomplete ? CriterionStatus.INCONCLUSIVE : CriterionStatus.PASS;
                results.add(new CriterionResult(criterion.id(), status, evidence, Judge.VERIFIER, reason));
            }
        }
        Verdict verdict;
        if (task.run().status() != Status.FINISHED) verdict = Verdict.INCOMPLETE;
        else if (task.run().verdict() == Verdict.SPEC_INVALID) verdict = Verdict.SPEC_INVALID;
        else if (results.stream().anyMatch(r -> r.status() == CriterionStatus.FAIL)) verdict = Verdict.FAILED;
        else if (results.isEmpty() || results.stream().anyMatch(r -> r.status() == CriterionStatus.INCONCLUSIVE
                || r.judge() == Judge.VERIFIER && r.status() == CriterionStatus.NOT_RUN)
                || verificationError(original)) verdict = Verdict.INCOMPLETE;
        else if (results.stream().anyMatch(r -> r.status() == CriterionStatus.NOT_RUN)) verdict = Verdict.NEEDS_HUMAN;
        else verdict = Verdict.PASSED;
        // An unsuccessful original run without a complete deterministic proof never gains a PASS.
        if (task.run().verdict() == Verdict.INCOMPLETE && verdict == Verdict.PASSED) verdict = Verdict.INCOMPLETE;
        return new HumanReview.Judgment(revision, task.spec().digest(), task.run().runId(), task.run().headSha(), verdict, results, at);
    }

    private static boolean verificationError(JsonNode original) {
        JsonNode attempts = original.path("verificationAttempts");
        if (!attempts.isArray() || attempts.isEmpty()) return true;
        for (JsonNode verifier : attempts.get(attempts.size() - 1).path("verifierResults")) {
            if (!Set.of("PASS", "FAIL").contains(verifier.path("status").asText())) return true;
        }
        return false;
    }
}
