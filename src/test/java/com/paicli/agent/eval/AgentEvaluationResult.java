package com.paicli.agent.eval;

import java.nio.file.Path;

record AgentEvaluationResult(
        String caseId,
        EvaluationMode mode,
        int repetition,
        boolean passed,
        int passedChecks,
        int totalChecks,
        int llmCalls,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long durationMillis,
        double estimatedCostUsd,
        ReviewDecision reviewDecision,
        int correctionRetries,
        boolean reviewRecovered,
        String validationDetail,
        String finalOutput,
        String error,
        Path workspace
) {
    enum ReviewDecision {
        APPROVED, REJECTED, NOT_OBSERVED
    }
}
