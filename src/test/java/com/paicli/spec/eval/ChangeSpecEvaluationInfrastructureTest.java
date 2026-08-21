package com.paicli.spec.eval;

import com.paicli.tool.CommandExecutionResult;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSpecEvaluationInfrastructureTest {

    @Test
    void catalogContainsTwoCasesPerTierAndFixedVerifierCommand() {
        List<ChangeSpecEvaluationCase> cases = ChangeSpecEvaluationCatalog.defaultCases();

        assertEquals(6, cases.size());
        for (ChangeSpecEvaluationTier tier : ChangeSpecEvaluationTier.values()) {
            assertEquals(2, cases.stream().filter(value -> value.tier() == tier).count());
        }
        assertEquals(6, cases.stream().map(ChangeSpecEvaluationCase::id).collect(Collectors.toSet()).size());
        assertTrue(cases.stream().allMatch(value -> value.isAllowedVerifierCommand(
                ChangeSpecEvaluationCatalog.PUBLIC_VERIFIER)));
        assertTrue(cases.stream().noneMatch(value -> value.isAllowedVerifierCommand("mvn clean test")));
        assertTrue(cases.stream().allMatch(value -> value.draftContext().contains("deterministic")));
    }

    @Test
    void hiddenOracleAndScopeCheckAreIndependent(@TempDir Path tempDir) throws Exception {
        ChangeSpecEvaluationCase evaluationCase = new ChangeSpecEvaluationCase(
                "infra",
                ChangeSpecEvaluationTier.SMALL,
                "change allowed.txt",
                "bounded deterministic",
                Map.of("allowed.txt", "before", "protected.txt", "keep"),
                Map.of("hidden.txt", "secret"),
                Set.of("allowed.txt"),
                "java -version",
                javaVersionCommand(),
                Duration.ofSeconds(20));
        Path workspace = tempDir.resolve("workspace");
        evaluationCase.materialize(workspace);
        ChangeSpecEvaluationCase.WorkspaceSnapshot baseline = evaluationCase.snapshot(workspace);
        Files.writeString(workspace.resolve("allowed.txt"), "after");

        ChangeSpecEvaluationCase.ValidationResult passing = evaluationCase.verify(workspace, baseline);

        assertTrue(passing.passed());
        assertTrue(Files.exists(workspace.resolve("hidden.txt")));

        Path violating = tempDir.resolve("violating");
        evaluationCase.materialize(violating);
        Files.writeString(violating.resolve("protected.txt"), "changed");
        ChangeSpecEvaluationCase.ValidationResult failed = evaluationCase.verify(violating, baseline);

        assertFalse(failed.passed());
        assertTrue(failed.unexpectedFiles().contains("protected.txt"));
    }

    @Test
    @EnabledIfSystemProperty(named = "paicli.changeSpecEval.validateFixtures", matches = "true")
    void everyFixtureHasAReferenceImplementationThatPassesBothOracles(@TempDir Path tempDir) throws Exception {
        Map<String, Map<String, String>> solutions = ChangeSpecEvaluationCatalog.referenceSolutions();
        for (ChangeSpecEvaluationCase evaluationCase : ChangeSpecEvaluationCatalog.defaultCases()) {
            Path workspace = tempDir.resolve(evaluationCase.id());
            evaluationCase.materialize(workspace);
            ChangeSpecEvaluationCase.WorkspaceSnapshot baseline = evaluationCase.snapshot(workspace);
            Map<String, String> solution = solutions.get(evaluationCase.id());
            assertEquals(evaluationCase.allowedChangedFiles(), solution.keySet());
            for (Map.Entry<String, String> entry : solution.entrySet()) {
                Files.writeString(workspace.resolve(entry.getKey()), entry.getValue());
            }

            ChangeSpecEvaluationCase.ValidationResult result = evaluationCase.verify(workspace, baseline);

            assertTrue(result.passed(), evaluationCase.id() + ": " + result.detail());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "paicli.changeSpecEval.validateFixtures", matches = "true")
    void publicVerifierRunsThroughProductionToolRegistryPath(@TempDir Path tempDir) throws Exception {
        ChangeSpecEvaluationCase evaluationCase = ChangeSpecEvaluationCatalog.defaultCases().get(0);
        Path workspace = tempDir.resolve(evaluationCase.id());
        evaluationCase.materialize(workspace);
        for (Map.Entry<String, String> entry
                : ChangeSpecEvaluationCatalog.referenceSolutions().get(evaluationCase.id()).entrySet()) {
            Files.writeString(workspace.resolve(entry.getKey()), entry.getValue());
        }
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());

        CommandExecutionResult result = registry.executeCommandForVerification(
                ChangeSpecEvaluationCatalog.PUBLIC_VERIFIER);

        assertEquals(CommandExecutionResult.Status.COMPLETED, result.status(), result.reason());
        assertEquals(0, result.exitCode(), result.output());
    }

    @Test
    void reportKeepsAutomatedHumanTimeAsNotAvailable(@TempDir Path tempDir) {
        List<ChangeSpecEvaluationResult> results = List.of(
                result(ChangeSpecEvaluationMode.REACT, true, true, ""),
                result(ChangeSpecEvaluationMode.SPEC_NO_REPAIR, false, false, "digest-1"),
                result(ChangeSpecEvaluationMode.SPEC_WITH_REPAIR, true, true, "digest-1"));

        String report = ChangeSpecEvaluationReport.toMarkdown(
                results, "stub", "stub-model", 7L, 1, 60_000L, false);

        assertTrue(report.contains("人工介入时间：N/A"));
        assertTrue(report.contains("digest 一致：1/1 对"));
        assertTrue(report.contains("不能单独得出‘满足完整提效门槛’"));
        assertTrue(report.contains("A · 普通 ReAct"));
    }

    private static ChangeSpecEvaluationResult result(
            ChangeSpecEvaluationMode mode,
            boolean success,
            boolean completed,
            String digest
    ) {
        return new ChangeSpecEvaluationResult(
                "case",
                ChangeSpecEvaluationTier.MEDIUM,
                mode,
                1,
                success,
                success,
                completed,
                mode.usesChangeSpec(),
                mode.usesChangeSpec() && completed,
                false,
                completed ? "PASSED" : "FAILED",
                mode == ChangeSpecEvaluationMode.SPEC_WITH_REPAIR ? 1 : 0,
                2,
                20,
                10,
                0,
                100,
                20,
                success ? 120 : 60_000,
                0,
                digest,
                "detail",
                "",
                Path.of("target", mode.name().toLowerCase()));
    }

    private static List<String> javaVersionCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-version");
    }
}
