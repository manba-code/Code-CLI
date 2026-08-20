package com.paicli.spec;

import java.util.List;

/**
 * 一次代码变更的机器契约。是否已被用户确认由上层运行流程决定。
 */
public record ChangeSpec(
        String schema,
        String id,
        int revision,
        String title,
        Intent intent,
        Scope scope,
        List<AcceptanceCriterion> acceptance,
        List<VerifierDefinition> verifiers
) {
    public ChangeSpec {
        acceptance = immutable(acceptance);
        verifiers = immutable(verifiers);
    }

    public record Intent(String goal, List<String> nonGoals) {
        public Intent {
            nonGoals = immutable(nonGoals);
        }
    }

    public record Scope(ScopeMode mode, List<String> include, List<String> exclude) {
        public Scope {
            include = immutable(include);
            exclude = immutable(exclude);
        }
    }

    public record AcceptanceCriterion(String id, CriterionKind kind, String statement, Oracle oracle) {
    }

    public record Oracle(OracleType type, List<String> verifiers) {
        public Oracle {
            verifiers = immutable(verifiers);
        }
    }

    public record VerifierDefinition(
            String id,
            VerifierType type,
            String command,
            CommandExpectation expect
    ) {
    }

    public record CommandExpectation(
            Integer exitCode,
            String junitReportGlob,
            Integer minimumTests
    ) {
    }

    public enum ScopeMode {
        OPEN,
        BOUNDED
    }

    public enum CriterionKind {
        BEHAVIOR,
        SCOPE,
        COMPATIBILITY,
        QUALITY,
        SAFETY,
        PERFORMANCE
    }

    public enum OracleType {
        DETERMINISTIC,
        HUMAN
    }

    public enum VerifierType {
        PATH_SCOPE,
        COMMAND
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
