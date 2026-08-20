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
        while (true) {
            ChangeSpecDocument document = draftProvider.generate(effectiveRequest);
            ReviewDecision decision = Objects.requireNonNull(
                    reviewHandler.review(document),
                    "review decision");
            switch (decision.action()) {
                case CONFIRM -> {
                    return new Result(Status.CONFIRMED, document);
                }
                case CANCEL -> {
                    return new Result(Status.CANCELED, null);
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

    private static String requireRequest(String request) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("request 不能为空");
        }
        return request.trim();
    }

    @FunctionalInterface
    public interface DraftProvider {
        ChangeSpecDocument generate(String request) throws IOException;
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

    public record Result(Status status, ChangeSpecDocument document) {
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
