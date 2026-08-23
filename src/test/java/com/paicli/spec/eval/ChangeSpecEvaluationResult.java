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
        String diagnosticClassification,
        int repairCount,
        int llmCalls,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long productDurationMs,
        long draftDurationMs,
        long reactExecutionMs,
        long reactLlmRequestMs,
        long reactToolExecutionMs,
        long publicVerificationMs,
        long hiddenOracleDurationMs,
        long penalizedTimeToAcceptedChangeMs,
        double estimatedCost,
        String specDigest,
        String detail,
        String error,
        Path workspace,
        Path draftDiagnostic
) {
    ChangeSpecEvaluationResult {
        productDurationMs = Math.max(0L, productDurationMs);
        draftDurationMs = Math.max(0L, draftDurationMs);
        reactExecutionMs = Math.max(0L, reactExecutionMs);
        reactLlmRequestMs = Math.max(0L, reactLlmRequestMs);
        reactToolExecutionMs = Math.max(0L, reactToolExecutionMs);
        publicVerificationMs = Math.max(0L, publicVerificationMs);
        hiddenOracleDurationMs = Math.max(0L, hiddenOracleDurationMs);
        penalizedTimeToAcceptedChangeMs = Math.max(0L, penalizedTimeToAcceptedChangeMs);
    }

    boolean falseCompletion() {
        return completionClaimed && !taskSuccess;
    }

    long observedOutcomeDurationMs() {
        return saturatingAdd(productDurationMs, hiddenOracleDurationMs);
    }

    boolean trustedProductDecisionAvailable() {
        return mode.usesChangeSpec() && acceptancePassed && taskSuccess;
    }

    long trustedProductDecisionMs() {
        return trustedProductDecisionAvailable() ? productDurationMs : 0L;
    }

    boolean repairEligible() {
        return mode == ChangeSpecEvaluationMode.SPEC_WITH_REPAIR && repairCount > 0;
    }

    private static long saturatingAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }
}
