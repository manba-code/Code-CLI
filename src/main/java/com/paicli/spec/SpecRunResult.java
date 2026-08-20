package com.paicli.spec;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一次 ChangeSpec 运行的完整可观察结果。
 *
 * <p>CLI 和测试只依赖这个接口；Criterion 归约、Verdict、Evidence 与持久化细节由
 * {@link SpecRunCoordinator} 隐藏在模块内部。</p>
 */
public record SpecRunResult(
        Status status,
        RunIdentity identity,
        String agentResponse,
        WorkspaceChangeTracker.WorkspaceChanges workspaceChanges,
        List<SpecVerifier.VerifierResult> verifierResults,
        List<CriterionResult> criterionResults,
        List<HumanEvidence> humanEvidence,
        Verdict verdict,
        Metrics metrics,
        Artifacts artifacts,
        String detail
) {
    public SpecRunResult {
        status = Objects.requireNonNull(status, "status");
        verifierResults = verifierResults == null ? List.of() : List.copyOf(verifierResults);
        criterionResults = criterionResults == null ? List.of() : List.copyOf(criterionResults);
        humanEvidence = humanEvidence == null ? List.of() : List.copyOf(humanEvidence);
        metrics = metrics == null ? Metrics.empty() : metrics;
        artifacts = artifacts == null ? Artifacts.notApplicable() : artifacts;
        detail = detail == null ? "" : detail;
    }

    public static SpecRunResult canceled(Metrics metrics) {
        return new SpecRunResult(
                Status.CANCELED,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                metrics,
                Artifacts.notApplicable(),
                "用户在确认前取消 ChangeSpec Draft");
    }

    public SpecRunResult withArtifacts(Status newStatus, Artifacts newArtifacts, String newDetail) {
        return new SpecRunResult(
                newStatus,
                identity,
                agentResponse,
                workspaceChanges,
                verifierResults,
                criterionResults,
                humanEvidence,
                verdict,
                metrics,
                newArtifacts,
                newDetail);
    }

    public record RunIdentity(
            String runId,
            String specId,
            int revision,
            String specDigest,
            Path lockedSpecPath
    ) {
        public RunIdentity {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(specId, "specId");
            Objects.requireNonNull(specDigest, "specDigest");
            Objects.requireNonNull(lockedSpecPath, "lockedSpecPath");
        }
    }

    public record CriterionResult(
            String criterionId,
            CriterionStatus status,
            List<String> evidenceIds,
            Judge judge,
            String reason
    ) {
        public CriterionResult {
            Objects.requireNonNull(criterionId, "criterionId");
            Objects.requireNonNull(status, "status");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            Objects.requireNonNull(judge, "judge");
            reason = reason == null ? "" : reason;
        }
    }

    public record HumanEvidence(
            String evidenceId,
            String criterionId,
            HumanDecision decision,
            long durationMs,
            String reason
    ) {
        public HumanEvidence {
            Objects.requireNonNull(evidenceId, "evidenceId");
            Objects.requireNonNull(criterionId, "criterionId");
            Objects.requireNonNull(decision, "decision");
            durationMs = Math.max(0L, durationMs);
            reason = reason == null ? "" : reason;
        }
    }

    public record LlmUsage(
            int calls,
            long inputTokens,
            long outputTokens,
            long cachedInputTokens
    ) {
        public LlmUsage {
            calls = Math.max(0, calls);
            inputTokens = Math.max(0L, inputTokens);
            outputTokens = Math.max(0L, outputTokens);
            cachedInputTokens = Math.max(0L, cachedInputTokens);
        }

        public static LlmUsage empty() {
            return new LlmUsage(0, 0L, 0L, 0L);
        }

        public LlmUsage plus(LlmUsage other) {
            if (other == null) {
                return this;
            }
            return new LlmUsage(
                    calls + other.calls,
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    cachedInputTokens + other.cachedInputTokens);
        }
    }

    public record Metrics(
            long specGenerationMs,
            long specConfirmationMs,
            long reactExecutionMs,
            long verificationMs,
            long humanCriterionMs,
            long totalMs,
            LlmUsage draftLlmUsage,
            LlmUsage reactLlmUsage
    ) {
        public Metrics {
            specGenerationMs = Math.max(0L, specGenerationMs);
            specConfirmationMs = Math.max(0L, specConfirmationMs);
            reactExecutionMs = Math.max(0L, reactExecutionMs);
            verificationMs = Math.max(0L, verificationMs);
            humanCriterionMs = Math.max(0L, humanCriterionMs);
            totalMs = Math.max(0L, totalMs);
            draftLlmUsage = draftLlmUsage == null ? LlmUsage.empty() : draftLlmUsage;
            reactLlmUsage = reactLlmUsage == null ? LlmUsage.empty() : reactLlmUsage;
        }

        public static Metrics empty() {
            return new Metrics(0L, 0L, 0L, 0L, 0L, 0L, LlmUsage.empty(), LlmUsage.empty());
        }

        public LlmUsage totalLlmUsage() {
            return draftLlmUsage.plus(reactLlmUsage);
        }
    }

    public record Artifacts(
            PersistenceStatus status,
            Path runDirectory,
            Path resultJson,
            Path changeDiff,
            String detail
    ) {
        public Artifacts {
            status = Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
        }

        public static Artifacts notApplicable() {
            return new Artifacts(PersistenceStatus.NOT_APPLICABLE, null, null, null, "");
        }

        public static Artifacts saved(Path runDirectory, Path resultJson, Path changeDiff) {
            return new Artifacts(PersistenceStatus.SAVED, runDirectory, resultJson, changeDiff, "");
        }

        public static Artifacts failed(Path runDirectory, String detail) {
            return new Artifacts(PersistenceStatus.FAILED, runDirectory, null, null, detail);
        }
    }

    public enum Status {
        CANCELED,
        PREPARATION_FAILED,
        SPEC_INVALID,
        REACT_CANCELED,
        REACT_FAILED,
        VERIFICATION_FAILED,
        FINISHED
    }

    public enum CriterionStatus {
        PASS,
        FAIL,
        INCONCLUSIVE,
        NOT_RUN
    }

    public enum Judge {
        VERIFIER,
        HUMAN
    }

    public enum HumanDecision {
        PASS,
        FAIL,
        SKIPPED
    }

    public enum Verdict {
        SPEC_INVALID,
        FAILED,
        INCOMPLETE,
        NEEDS_HUMAN,
        PASSED
    }

    public enum PersistenceStatus {
        NOT_APPLICABLE,
        SAVED,
        FAILED
    }
}
