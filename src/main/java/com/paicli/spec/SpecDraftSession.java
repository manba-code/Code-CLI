package com.paicli.spec;

import java.io.IOException;
import java.util.Objects;

/**
 * 管理 Draft 的生成、补充重生成和确认；不负责持久化或代码执行。
 */
public final class SpecDraftSession {
    private final DraftProvider draftProvider;
    private final ReviewHandler reviewHandler;

    public SpecDraftSession(DraftProvider draftProvider, ReviewHandler reviewHandler) {
        this.draftProvider = Objects.requireNonNull(draftProvider, "draftProvider");
        this.reviewHandler = Objects.requireNonNull(reviewHandler, "reviewHandler");
    }

    public Result run(String request) throws IOException {
        String effectiveRequest = requireRequest(request);
        long generationMs = 0L;
        long confirmationMs = 0L;
        SpecRunResult.LlmUsage llmUsage = SpecRunResult.LlmUsage.empty();
        while (true) {
            DraftGeneration generation = Objects.requireNonNull(
                    draftProvider.generate(effectiveRequest),
                    "draft generation");
            ChangeSpecDocument document = Objects.requireNonNull(generation.document(), "draft document");
            generationMs += generation.durationMs();
            llmUsage = llmUsage.plus(generation.llmUsage());
            long reviewStartedAt = System.nanoTime();
            ReviewDecision decision = Objects.requireNonNull(
                    reviewHandler.review(document),
                    "review decision");
            confirmationMs += elapsedMillis(reviewStartedAt);
            switch (decision.action()) {
                case CONFIRM -> {
                    return new Result(
                            Status.CONFIRMED,
                            document,
                            effectiveRequest,
                            generationMs,
                            confirmationMs,
                            llmUsage);
                }
                case CANCEL -> {
                    return new Result(
                            Status.CANCELED,
                            null,
                            null,
                            generationMs,
                            confirmationMs,
                            llmUsage);
                }
                case SUPPLEMENT -> {
                    if (decision.supplement() != null && !decision.supplement().isBlank()) {
                        effectiveRequest = effectiveRequest
                                + "\n\n用户补充要求：\n"
                                + decision.supplement().trim();
                    }
                }
            }
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String requireRequest(String request) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("request 不能为空");
        }
        return request.trim();
    }

    @FunctionalInterface
    public interface DraftProvider {
        DraftGeneration generate(String request) throws IOException;
    }

    @FunctionalInterface
    public interface ReviewHandler {
        ReviewDecision review(ChangeSpecDocument document);
    }

    public record ReviewDecision(Action action, String supplement) {
        public ReviewDecision {
            Objects.requireNonNull(action, "action");
        }

        public static ReviewDecision confirm() {
            return new ReviewDecision(Action.CONFIRM, null);
        }

        public static ReviewDecision supplement(String supplement) {
            return new ReviewDecision(Action.SUPPLEMENT, supplement);
        }

        public static ReviewDecision cancel() {
            return new ReviewDecision(Action.CANCEL, null);
        }
    }

    public record DraftGeneration(
            ChangeSpecDocument document,
            SpecRunResult.LlmUsage llmUsage,
            long durationMs
    ) {
        public DraftGeneration {
            Objects.requireNonNull(document, "document");
            llmUsage = llmUsage == null ? SpecRunResult.LlmUsage.empty() : llmUsage;
            durationMs = Math.max(0L, durationMs);
        }

        public static DraftGeneration unmeasured(ChangeSpecDocument document) {
            return new DraftGeneration(document, SpecRunResult.LlmUsage.empty(), 0L);
        }
    }

    public record Result(
            Status status,
            ChangeSpecDocument document,
            String confirmedRequest,
            long generationMs,
            long confirmationMs,
            SpecRunResult.LlmUsage llmUsage
    ) {
        public Result {
            llmUsage = llmUsage == null ? SpecRunResult.LlmUsage.empty() : llmUsage;
        }
    }

    public enum Action {
        CONFIRM,
        SUPPLEMENT,
        CANCEL
    }

    public enum Status {
        CONFIRMED,
        CANCELED
    }
}
