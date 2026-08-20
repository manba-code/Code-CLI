package com.paicli.spec.eval;

import java.nio.file.Path;

record ChangeSpecEvaluationResult(
        String caseId,
        ChangeSpecEvaluationTier tier,
        ChangeSpecEvaluationMode mode,
        int repetition,
        boolean taskSuccess,
        boolean firstPassSuccess,
        boolean completionClaimed,
        boolean acceptanceApplicable,
        boolean acceptancePassed,
        boolean scopeViolation,
        String publicVerdict,
        int repairCount,
        int llmCalls,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long productDurationMs,
        long hiddenOracleDurationMs,
        long timeToAcceptedChangeMs,
        double estimatedCostUsd,
        String specDigest,
        String detail,
        String error,
        Path workspace
) {
    boolean falseCompletion() {
        return completionClaimed && !taskSuccess;
    }
}
