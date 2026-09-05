package com.paicli.spec;

import java.io.IOException;
import java.util.Objects;

/** 执行已经锁定的 ChangeSpec；不负责生成或审批 Draft。 */
public interface SpecExecutionEngine {
    SpecRunResult execute(ExecutionContext context) throws IOException;

    record ExecutionContext(
            String confirmedRequest,
            ChangeSpecModule.LockedSpec lockedSpec,
            long generationMs,
            long confirmationMs,
            SpecRunResult.LlmUsage draftLlmUsage,
            Runnable verificationStarted
    ) {
        public ExecutionContext(
                String confirmedRequest,
                ChangeSpecModule.LockedSpec lockedSpec,
                long generationMs,
                long confirmationMs,
                SpecRunResult.LlmUsage draftLlmUsage
        ) {
            this(confirmedRequest, lockedSpec, generationMs, confirmationMs, draftLlmUsage, () -> { });
        }

        public ExecutionContext {
            if (confirmedRequest == null || confirmedRequest.isBlank()) {
                throw new IllegalArgumentException("confirmedRequest 不能为空");
            }
            confirmedRequest = confirmedRequest.trim();
            lockedSpec = Objects.requireNonNull(lockedSpec, "lockedSpec");
            generationMs = Math.max(0L, generationMs);
            confirmationMs = Math.max(0L, confirmationMs);
            draftLlmUsage = draftLlmUsage == null ? SpecRunResult.LlmUsage.empty() : draftLlmUsage;
            verificationStarted = verificationStarted == null ? () -> { } : verificationStarted;
        }
    }
}
