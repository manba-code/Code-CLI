package com.paicli.cli;

final class SpecReviewInputParser {

    enum DecisionType {
        CONFIRM,
        SUPPLEMENT,
        CANCEL
    }

    record Decision(DecisionType type, String supplement) {
    }

    private SpecReviewInputParser() {
    }

    static Decision parse(String input) {
        if (input != null && input.equals("\u001B")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("y")
                || trimmed.equalsIgnoreCase("yes")
                || trimmed.equalsIgnoreCase("confirm")
                || trimmed.equalsIgnoreCase("/confirm")) {
            return new Decision(DecisionType.CONFIRM, null);
        }
        if (trimmed.equalsIgnoreCase("cancel")
                || trimmed.equalsIgnoreCase("esc")
                || trimmed.equalsIgnoreCase("/cancel")) {
            return new Decision(DecisionType.CANCEL, null);
        }
        return new Decision(DecisionType.SUPPLEMENT, trimmed);
    }
}
