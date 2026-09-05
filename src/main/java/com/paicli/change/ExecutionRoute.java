package com.paicli.change;

import java.util.Objects;

public record ExecutionRoute(
        RiskLevel riskLevel,
        String provider,
        String model,
        ExecutionMode executionMode,
        boolean repairEnabled,
        boolean specApprovalRequired,
        boolean deliveryApprovalRequired,
        ToolPolicyProfile toolPolicy
) {
    public ExecutionRoute {
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        executionMode = Objects.requireNonNull(executionMode, "executionMode");
        toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
        if (executionMode != ExecutionMode.REACT) {
            throw new IllegalArgumentException("PaiChange MVP 只支持 REACT 执行模式");
        }
    }

    public enum ExecutionMode { REACT }

    public enum ToolPolicyProfile { STANDARD, RESTRICTED, LOCKED_DOWN }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
