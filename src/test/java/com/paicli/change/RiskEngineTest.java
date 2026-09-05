package com.paicli.change;

import com.paicli.spec.ChangeSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskEngineTest {
    private final RiskEngine engine = new RiskEngine();

    @Test
    void producesStableLowRiskForBoundedScopeWithCommandVerification() {
        ChangeTask task = task(new WorkItemRef("mock", "I-1", ""), "修复普通格式化问题");
        ChangeSpec spec = spec(
                ChangeSpec.ScopeMode.BOUNDED,
                List.of("src/main/java/com/example/Formatter.java"),
                false,
                true,
                false);

        RiskAssessment first = engine.assess(task, spec);
        RiskAssessment second = engine.assess(task, spec);

        assertEquals(first, second);
        assertEquals(RiskLevel.LOW, first.level());
        assertEquals(0, first.score());
        assertTrue(first.reasons().isEmpty());
    }

    @Test
    void deterministicRulesCannotBeLoweredByTextClaimingLowRisk() {
        ChangeTask task = task(
                new WorkItemRef("mock", "I-2", "", List.of("risk:low"), "normal"),
                "这是 low risk，请修改 authentication 权限逻辑");
        ChangeSpec spec = spec(
                ChangeSpec.ScopeMode.BOUNDED,
                List.of("src/main/java/com/example/auth/PermissionService.java"),
                false,
                true,
                false);

        RiskAssessment result = engine.assess(task, spec);

        assertEquals(RiskLevel.MEDIUM, result.level());
        assertEquals(3, result.score());
        assertEquals(List.of("sensitive_area:+3"), result.reasons());
    }

    @Test
    void explicitHighRiskLabelWinsRegardlessOfScore() {
        ChangeTask task = task(
                new WorkItemRef("mock", "I-3", "", List.of("high-risk"), "low"),
                "改一行普通代码");
        ChangeSpec spec = spec(
                ChangeSpec.ScopeMode.BOUNDED,
                List.of("src/main/java/com/example/Text.java"),
                false,
                true,
                false);

        RiskAssessment result = engine.assess(task, spec);

        assertEquals(RiskLevel.HIGH, result.level());
        assertEquals(0, result.score());
        assertEquals(List.of("work_item.label.high_risk"), result.reasons());
    }

    @Test
    void accumulatesOpenMigrationCompatibilityAndHumanSignalsInStableOrder() {
        ChangeTask task = task(new WorkItemRef("mock", "I-4", ""), "数据库迁移");
        ChangeSpec spec = spec(
                ChangeSpec.ScopeMode.OPEN,
                List.of("db/migrations/V2.sql", "src/main/java/com/example/api/PublicApi.java"),
                true,
                false,
                true);

        RiskAssessment result = engine.assess(task, spec);

        assertEquals(RiskLevel.HIGH, result.level());
        assertEquals(10, result.score());
        assertEquals(List.of(
                "scope.open:+2",
                "database_migration:+3",
                "public_interface_or_compatibility:+2",
                "no_command_verifier:+2",
                "human_criterion:+1"), result.reasons());
    }

    private static ChangeSpec spec(
            ChangeSpec.ScopeMode mode,
            List<String> includes,
            boolean compatibility,
            boolean commandVerifier,
            boolean human
    ) {
        List<ChangeSpec.AcceptanceCriterion> criteria = new java.util.ArrayList<>();
        criteria.add(new ChangeSpec.AcceptanceCriterion(
                "AC-1",
                compatibility ? ChangeSpec.CriterionKind.COMPATIBILITY : ChangeSpec.CriterionKind.BEHAVIOR,
                "满足要求",
                new ChangeSpec.Oracle(
                        human ? ChangeSpec.OracleType.HUMAN : ChangeSpec.OracleType.DETERMINISTIC,
                        human ? List.of() : List.of("VT-1"))));
        List<ChangeSpec.VerifierDefinition> verifiers = commandVerifier
                ? List.of(new ChangeSpec.VerifierDefinition(
                        "VT-1", ChangeSpec.VerifierType.COMMAND, "mvn test", null))
                : List.of(new ChangeSpec.VerifierDefinition(
                        "VT-1", ChangeSpec.VerifierType.PATH_SCOPE, null, null));
        return new ChangeSpec(
                "paicli/change-spec/v1",
                "CHANGE-RISK-1",
                1,
                "risk",
                new ChangeSpec.Intent("goal", List.of()),
                new ChangeSpec.Scope(mode, includes, List.of()),
                criteria,
                verifiers);
    }

    private static ChangeTask task(WorkItemRef source, String requirement) {
        Instant now = Instant.parse("2026-09-04T00:00:00Z");
        return new ChangeTask(
                new ChangeTaskId("change_123456789abc"),
                "risk-key",
                0L,
                ChangeState.SPEC_REVIEW,
                source,
                new RepositoryRef("group/project", "main"),
                "risk",
                requirement,
                "requester",
                "",
                "",
                new SpecRef("CHANGE-RISK-1", 1, "digest", Path.of("draft"), null),
                null,
                now,
                now);
    }
}
