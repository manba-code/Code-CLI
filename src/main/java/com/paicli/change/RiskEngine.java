package com.paicli.change;

import com.paicli.spec.ChangeSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 只依据 Work Item、需求文本和锁定 ChangeSpec 的确定性规则评估风险。
 * 该模块不接受 LLM 建议或覆盖值。
 */
public final class RiskEngine {
    private static final Set<String> HIGH_RISK_LABELS = Set.of("high-risk", "high_risk", "risk:high");
    private static final List<String> SENSITIVE_PATH_PARTS = List.of(
            "auth", "authentication", "authorization", "permission", "permissions",
            "payment", "payments", "security", "config", "secrets");
    private static final List<String> MIGRATION_PATH_PARTS = List.of(
            "migration", "migrations", "db/migrate", "database/migrate", "liquibase", "flyway");
    private static final List<String> PUBLIC_INTERFACE_PATH_PARTS = List.of(
            "api/", "public/", "interface/", "interfaces/", "src/main/resources/openapi");

    public RiskAssessment assess(ChangeTask task, ChangeSpec spec) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(spec, "spec");

        int score = 0;
        List<String> reasons = new ArrayList<>();
        boolean explicitlyHigh = task.source().labels().stream()
                .map(RiskEngine::normalized)
                .anyMatch(HIGH_RISK_LABELS::contains);
        if (explicitlyHigh) {
            reasons.add("work_item.label.high_risk");
        }

        if (spec.scope() != null && spec.scope().mode() == ChangeSpec.ScopeMode.OPEN) {
            score += 2;
            reasons.add("scope.open:+2");
        }

        List<String> scopedPaths = scopePaths(spec);
        String searchable = normalized(String.join("\n", scopedPaths)
                + "\n" + task.title()
                + "\n" + task.requirement()
                + "\n" + task.source().priority()
                + "\n" + String.join(" ", task.source().labels()));
        if (containsAny(searchable, SENSITIVE_PATH_PARTS)
                || containsAny(searchable, List.of("认证", "鉴权", "权限", "支付", "安全配置", "密钥"))) {
            score += 3;
            reasons.add("sensitive_area:+3");
        }
        if (containsAny(searchable, MIGRATION_PATH_PARTS)
                || containsAny(searchable, List.of("数据库迁移", "表结构迁移", "schema migration"))) {
            score += 3;
            reasons.add("database_migration:+3");
        }
        boolean compatibilityCriterion = spec.acceptance().stream()
                .anyMatch(criterion -> criterion.kind() == ChangeSpec.CriterionKind.COMPATIBILITY);
        if (compatibilityCriterion || containsAny(searchable, PUBLIC_INTERFACE_PATH_PARTS)) {
            score += 2;
            reasons.add("public_interface_or_compatibility:+2");
        }
        boolean hasCommandVerifier = spec.verifiers().stream()
                .anyMatch(verifier -> verifier.type() == ChangeSpec.VerifierType.COMMAND);
        if (!hasCommandVerifier) {
            score += 2;
            reasons.add("no_command_verifier:+2");
        }
        boolean hasHumanCriterion = spec.acceptance().stream()
                .anyMatch(criterion -> criterion.oracle() != null
                        && criterion.oracle().type() == ChangeSpec.OracleType.HUMAN);
        if (hasHumanCriterion) {
            score += 1;
            reasons.add("human_criterion:+1");
        }

        RiskLevel level = explicitlyHigh || score >= 6
                ? RiskLevel.HIGH
                : score >= 3 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new RiskAssessment(level, score, List.copyOf(new LinkedHashSet<>(reasons)));
    }

    private static List<String> scopePaths(ChangeSpec spec) {
        if (spec.scope() == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(spec.scope().include());
        paths.addAll(spec.scope().exclude());
        return paths;
    }

    private static boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().map(RiskEngine::normalized).anyMatch(value::contains);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
    }
}
