package com.paicli.spec.eval;

enum ChangeSpecEvaluationMode {
    REACT("A · 普通 ReAct", false),
    SPEC_NO_REPAIR("B · ChangeSpec（无修复）", true),
    SPEC_WITH_REPAIR("C · ChangeSpec（一次修复）", true);

    private final String displayName;
    private final boolean changeSpec;

    ChangeSpecEvaluationMode(String displayName, boolean changeSpec) {
        this.displayName = displayName;
        this.changeSpec = changeSpec;
    }

    String displayName() {
        return displayName;
    }

    boolean usesChangeSpec() {
        return changeSpec;
    }
}
