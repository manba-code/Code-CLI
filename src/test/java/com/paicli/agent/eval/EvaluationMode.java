package com.paicli.agent.eval;

enum EvaluationMode {
    REACT("ReAct 单 Agent"),
    PLAN_EXECUTE("Plan-and-Execute（无质量 Reviewer）"),
    MULTI_AGENT("完整 Multi-Agent");

    private final String displayName;

    EvaluationMode(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }
}
