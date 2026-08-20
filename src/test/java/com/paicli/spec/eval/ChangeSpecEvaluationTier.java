package com.paicli.spec.eval;

enum ChangeSpecEvaluationTier {
    SMALL("小型"),
    MEDIUM("中型"),
    HIGH_RISK("高风险");

    private final String displayName;

    ChangeSpecEvaluationTier(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }
}
