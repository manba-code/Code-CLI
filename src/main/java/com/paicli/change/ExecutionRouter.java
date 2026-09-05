package com.paicli.change;

import com.paicli.config.PaiCliConfig;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 将确定性风险结果映射为任务级 provider/model 与执行策略。 */
public final class ExecutionRouter {
    private final Map<RiskLevel, RoutePolicy> policies;

    public ExecutionRouter(Map<RiskLevel, RoutePolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        EnumMap<RiskLevel, RoutePolicy> copy = new EnumMap<>(RiskLevel.class);
        copy.putAll(policies);
        for (RiskLevel level : RiskLevel.values()) {
            if (!copy.containsKey(level)) {
                throw new IllegalArgumentException("缺少风险路由: " + level);
            }
        }
        this.policies = Map.copyOf(copy);
    }

    public static ExecutionRouter fromConfig(PaiCliConfig config) {
        Objects.requireNonNull(config, "config");
        PaiCliConfig.PaiChangeConfig change = config.getPaiChange();
        EnumMap<RiskLevel, RoutePolicy> routes = new EnumMap<>(RiskLevel.class);
        for (RiskLevel level : RiskLevel.values()) {
            PaiCliConfig.PaiChangeRouteConfig configured = change.route(level.name());
            String provider = textOr(configured == null ? null : configured.getProvider(),
                    config.getDefaultProvider());
            String model = textOr(configured == null ? null : configured.getModel(),
                    config.getModel(provider));
            if (model == null || model.isBlank()) model = com.paicli.llm.LlmClientFactory.defaultModel(provider);
            boolean repair = configured == null || configured.getRepairEnabled() == null
                    ? level != RiskLevel.HIGH
                    : configured.isRepairEnabled();
            boolean deliveryApproval = level == RiskLevel.HIGH
                    || configured == null
                    || configured.getDeliveryApprovalRequired() == null
                    || configured.isDeliveryApprovalRequired();
            ExecutionRoute.ToolPolicyProfile tools = configured == null
                    ? defaultToolPolicy(level)
                    : configuredToolPolicy(configured.getToolPolicy(), level);
            routes.put(level, new RoutePolicy(provider, model, repair, deliveryApproval, tools));
        }
        return new ExecutionRouter(routes);
    }

    public ExecutionRoute route(RiskAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        RoutePolicy policy = policies.get(assessment.level());
        return new ExecutionRoute(
                assessment.level(),
                policy.provider(),
                policy.model(),
                ExecutionRoute.ExecutionMode.REACT,
                policy.repairEnabled(),
                true,
                policy.deliveryApprovalRequired(),
                policy.toolPolicy());
    }

    public record RoutePolicy(
            String provider,
            String model,
            boolean repairEnabled,
            boolean deliveryApprovalRequired,
            ExecutionRoute.ToolPolicyProfile toolPolicy
    ) {
        public RoutePolicy {
            provider = requireText(provider, "provider");
            model = requireText(model, "model");
            toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
        }
    }

    private static ExecutionRoute.ToolPolicyProfile defaultToolPolicy(RiskLevel level) {
        return switch (level) {
            case LOW -> ExecutionRoute.ToolPolicyProfile.STANDARD;
            case MEDIUM -> ExecutionRoute.ToolPolicyProfile.RESTRICTED;
            case HIGH -> ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN;
        };
    }

    private static ExecutionRoute.ToolPolicyProfile configuredToolPolicy(String value, RiskLevel level) {
        return value == null || value.isBlank()
                ? defaultToolPolicy(level)
                : ExecutionRoute.ToolPolicyProfile.valueOf(value.trim().toUpperCase());
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
