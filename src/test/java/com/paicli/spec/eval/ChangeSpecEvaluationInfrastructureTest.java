package com.paicli.spec.eval;

import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.tool.CommandExecutionResult;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSpecEvaluationInfrastructureTest {

    @Test
    void evaluationModelOverrideOnlyMutatesInMemoryConfig() {
        PaiCliConfig config = new PaiCliConfig();
        config.setDefaultProvider("glm");

        ChangeSpecQualityEvaluationTest.applyModelOverride(config, "glm", "candidate-model");

        assertEquals("candidate-model", config.getModel("glm"));
    }

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
                results, "stub", "stub-model", 7L, 1, 60_000L, false, "USD");

        assertTrue(report.contains("人工总投入：NOT_MEASURED"));
        assertTrue(report.contains("digest 一致：1/1 对"));
        assertTrue(report.contains("PASS / FAIL / NOT_EVALUABLE / NOT_MEASURED"));
        assertTrue(report.contains("A · 普通 ReAct"));
        assertTrue(report.contains("产品耗时 P50"));
        assertTrue(report.contains("客观正确候选 TTA P50"));
        assertTrue(report.contains("可信产品决策 TTA P50"));
        assertTrue(report.contains("惩罚 TTA P50"));
        assertTrue(report.contains("ReAct LLM 请求 P50"));
        assertTrue(report.contains("ReAct 工具批次墙钟 P50"));
        assertTrue(report.contains("并行批次按整批等待时间统计"));
    }

    @Test
    void reportTreatsCeilingFloorAndNoRepairOpportunityAsNotEvaluable() {
        List<ChangeSpecEvaluationResult> results = List.of(
                detailedResult(ChangeSpecEvaluationMode.REACT, true, true, true, "", 0,
                        1_000L, 200L, 1_200L),
                detailedResult(ChangeSpecEvaluationMode.SPEC_NO_REPAIR, true, true, true, "digest-1", 0,
                        1_500L, 200L, 1_700L),
                detailedResult(ChangeSpecEvaluationMode.SPEC_WITH_REPAIR, true, true, true, "digest-1", 0,
                        1_700L, 200L, 1_900L));

        String report = ChangeSpecEvaluationReport.toMarkdown(
                results, "stub", "stub-model", 7L, 1, 60_000L, false, "USD");

        assertTrue(report.contains("任务成功率非劣"), report);
        assertTrue(report.contains("基线接近天花板"), report);
        assertTrue(report.contains("A/C 声明内虚假率均为 0%"), report);
        assertTrue(report.contains("repair_eligible_count=0"), report);
        assertTrue(report.contains("| 完整人工投入 | NOT_MEASURED |"), report);
        assertTrue(report.contains("100.00% [20.65%, 100.00%]"), report);
    }

    @Test
    void reportSeparatesAbBcAcContrastsAndObservedFailureTime() {
        List<ChangeSpecEvaluationResult> results = List.of(
                detailedResult(ChangeSpecEvaluationMode.REACT, false, false, true, "", 0,
                        1_000L, 200L, 60_000L),
                detailedResult(ChangeSpecEvaluationMode.SPEC_NO_REPAIR, false, false, false, "digest-1", 0,
                        1_500L, 300L, 60_000L),
                detailedResult(ChangeSpecEvaluationMode.SPEC_WITH_REPAIR, true, false, true, "digest-1", 1,
                        2_000L, 300L, 2_300L));

        String report = ChangeSpecEvaluationReport.toMarkdown(
                results, "stub", "stub-model", 7L, 1, 60_000L, false, "USD");

        assertTrue(report.contains("A→B"), report);
        assertTrue(report.contains("B→C"), report);
        assertTrue(report.contains("A→C"), report);
        assertTrue(report.contains("repair_eligible_count=1"), report);
        assertTrue(report.contains("条件成功率=100.00%"), report);
        assertTrue(report.contains("1.20s"), report);
        assertTrue(report.contains("失败实际耗时 P50"), report);
        assertTrue(report.contains("不是实际失败耗时"), report);
    }

    @Test
    void reportUsesConfiguredCostCurrency() {
        String report = ChangeSpecEvaluationReport.toMarkdown(
                List.of(result(ChangeSpecEvaluationMode.REACT, true, true, "")),
                "stub", "stub-model", 7L, 1, 60_000L, true, "CNY");

        assertTrue(report.contains("成本币种：CNY"), report);
        assertTrue(report.contains("CNY 0.00"), report);
        assertFalse(report.contains("$0.00"), report);
    }

    @Test
    void totalProductDurationIncludesDraftAndSaturatesSafely() {
        assertEquals(55_000L, ChangeSpecEvaluationRunner.totalProductDuration(15_000L, 40_000L));
        assertEquals(Long.MAX_VALUE,
                ChangeSpecEvaluationRunner.totalProductDuration(Long.MAX_VALUE - 5, 10));
    }

    @Test
    void evaluationLlmClientMeasuresRequestWallClock() throws Exception {
        Queue<Long> ticks = new ArrayDeque<>(List.of(1_000_000L, 4_000_000L));
        ChangeSpecEvaluationLlmClient client = new ChangeSpecEvaluationLlmClient(
                new StubLlmClient("ok"), ticks::remove);

        client.chat(List.of(), List.of());

        assertEquals(3L, client.requestDurationMs());
    }

    @Test
    void evaluationLlmClientAlsoMeasuresFailedRequests() {
        Queue<Long> ticks = new ArrayDeque<>(List.of(3_000_000L, 9_000_000L));
        ChangeSpecEvaluationLlmClient client = new ChangeSpecEvaluationLlmClient(
                new StubLlmClient(), ticks::remove);

        assertThrows(RuntimeException.class, () -> client.chat(List.of(), List.of()));
        assertEquals(6L, client.requestDurationMs());
    }

    @Test
    void evaluationToolRegistryMeasuresBatchWallClock() {
        Queue<Long> ticks = new ArrayDeque<>(List.of(2_000_000L, 7_000_000L));
        ChangeSpecEvaluationToolRegistry registry = new ChangeSpecEvaluationToolRegistry(ticks::remove);

        registry.executeTools(List.of(new ToolRegistry.ToolInvocation(
                "call-1", "unknown_tool", "{}")));

        assertEquals(5L, registry.batchDurationMs());
    }

    @Test
    void processOutputDecodingNeverTurnsNativeBytesIntoOracleException() {
        byte[] nativeBytes = "测试失败".getBytes(Charset.forName("GB18030"));

        String decoded = ChangeSpecEvaluationCase.decodeProcessOutput(nativeBytes);

        assertEquals("测试失败", decoded);
    }

    @Test
    void reportDoesNotCountUnavailablePairedDraftAsDigestMatch() {
        String report = ChangeSpecEvaluationReport.toMarkdown(
                List.of(
                        result(ChangeSpecEvaluationMode.SPEC_NO_REPAIR, false, false, ""),
                        result(ChangeSpecEvaluationMode.SPEC_WITH_REPAIR, false, false, "")),
                "stub", "stub-model", 7L, 1, 60_000L, false, "USD");

        assertTrue(report.contains("digest 一致：0/1 对"), report);
    }

    @Test
    void invalidPairedDraftPersistsSanitizedAttemptDiagnosticsAndLinksTheReport(
            @TempDir Path tempDir
    ) throws Exception {
        String oversizedDraft = "---\napi_key: top-secret\ntitle:\n  nested: value\n"
                + "x".repeat(9 * 1024)
                + "\n---";
        StubLlmClient stub = new StubLlmClient(
                oversizedDraft,
                "---\npassword: second-secret\ntitle:\n  nested: value\n---");
        ChangeSpecEvaluationRunner runner = new ChangeSpecEvaluationRunner(
                () -> stub, tempDir, 0d, 0d, 60_000L);

        ChangeSpecPairedDraft draft = runner.preparePairedDraft(
                ChangeSpecEvaluationCatalog.defaultCases().get(0), 3);

        assertFalse(draft.available());
        assertEquals(2, stub.calls);
        assertTrue(draft.diagnosticFile() != null);
        Path diagnostic = tempDir.resolve(draft.diagnosticFile());
        assertTrue(Files.isRegularFile(diagnostic));
        String content = Files.readString(diagnostic);
        assertTrue(content.contains("## Attempt 1"), content);
        assertTrue(content.contains("## Attempt 2"), content);
        assertTrue(content.contains("verifiers") || content.contains("title"), content);
        assertTrue(content.contains("***"), content);
        assertFalse(content.contains("top-secret"), content);
        assertFalse(content.contains("second-secret"), content);
        assertTrue(content.contains("truncated: true"), content);

        ChangeSpecEvaluationResult invalid = result(
                ChangeSpecEvaluationMode.SPEC_NO_REPAIR, false, false, "", draft.diagnosticFile());
        String report = ChangeSpecEvaluationReport.toMarkdown(
                List.of(invalid), "stub", "stub-model", 7L, 1, 60_000L, false, "USD");
        assertTrue(report.contains("[Draft 诊断](<" + draft.diagnosticFile().toString().replace('\\', '/') + ">)"), report);
    }

    @Test
    void classifiesFinishedFailedSpecWithNoWorkspaceChanges() {
        assertEquals("NO_CHANGE_COMPLETION",
                ChangeSpecEvaluationRunner.classifyChangeSpecRun(true, false, false));
        assertEquals("", ChangeSpecEvaluationRunner.classifyChangeSpecRun(true, true, false));
        assertEquals("", ChangeSpecEvaluationRunner.classifyChangeSpecRun(true, false, true));
        assertEquals("", ChangeSpecEvaluationRunner.classifyChangeSpecRun(false, false, false));

        ChangeSpecEvaluationResult classified = result(
                ChangeSpecEvaluationMode.SPEC_WITH_REPAIR,
                false,
                false,
                "digest",
                null,
                "NO_CHANGE_COMPLETION");
        String report = ChangeSpecEvaluationReport.toMarkdown(
                List.of(classified), "stub", "stub-model", 7L, 1, 60_000L, false, "USD");
        assertTrue(report.contains("| FAILED | NO_CHANGE_COMPLETION | YES | 1 |"), report);
    }

    @Test
    void pairedDraftRejectsCommandOutsideFixtureAllowlistAndPersistsDiagnostic(
            @TempDir Path tempDir
    ) throws Exception {
        EligibilityStubLlmClient stub = new EligibilityStubLlmClient(
                "mvn -q -DskipTests=false verify");
        ChangeSpecEvaluationRunner runner = new ChangeSpecEvaluationRunner(
                () -> stub, tempDir, 0d, 0d, 60_000L);

        ChangeSpecPairedDraft draft = runner.preparePairedDraft(
                ChangeSpecEvaluationCatalog.defaultCases().get(0), 4);

        assertFalse(draft.available());
        assertEquals(2, stub.calls);
        assertTrue(draft.error().contains("不在评测任务允许列表"), draft.error());
        assertTrue(draft.diagnosticFile() != null);
        String diagnostic = Files.readString(tempDir.resolve(draft.diagnosticFile()));
        assertTrue(diagnostic.contains("mvn -q -DskipTests=false verify"), diagnostic);
        assertTrue(diagnostic.contains("不在评测任务允许列表"), diagnostic);
        assertTrue(diagnostic.contains("## Attempt 2"), diagnostic);
    }

    @Test
    void pairedDraftCanCorrectEligibilityFailureOnSecondAttempt(@TempDir Path tempDir) {
        EligibilityStubLlmClient stub = new EligibilityStubLlmClient(
                "mvn -q -DskipTests=false verify",
                ChangeSpecEvaluationCatalog.PUBLIC_VERIFIER);
        ChangeSpecEvaluationRunner runner = new ChangeSpecEvaluationRunner(
                () -> stub, tempDir, 0d, 0d, 60_000L);

        ChangeSpecPairedDraft draft = runner.preparePairedDraft(
                ChangeSpecEvaluationCatalog.defaultCases().get(0), 6);

        assertTrue(draft.available(), draft.error());
        assertEquals(2, stub.calls);
        assertTrue(draft.diagnosticFile() == null);
    }

    @Test
    void pairedDraftAcceptsFixtureAllowlistedCommand(@TempDir Path tempDir) {
        EligibilityStubLlmClient stub = new EligibilityStubLlmClient(
                ChangeSpecEvaluationCatalog.PUBLIC_VERIFIER);
        ChangeSpecEvaluationRunner runner = new ChangeSpecEvaluationRunner(
                () -> stub, tempDir, 0d, 0d, 60_000L);

        ChangeSpecPairedDraft draft = runner.preparePairedDraft(
                ChangeSpecEvaluationCatalog.defaultCases().get(0), 5);

        assertTrue(draft.available(), draft.error());
        assertEquals(1, stub.calls);
        assertTrue(draft.diagnosticFile() == null);
    }

    private static ChangeSpecEvaluationResult result(
            ChangeSpecEvaluationMode mode,
            boolean success,
            boolean completed,
            String digest
    ) {
        return result(mode, success, completed, digest, null);
    }

    private static ChangeSpecEvaluationResult result(
            ChangeSpecEvaluationMode mode,
            boolean success,
            boolean completed,
            String digest,
            Path draftDiagnostic
    ) {
        return result(mode, success, completed, digest, draftDiagnostic, "");
    }

    private static ChangeSpecEvaluationResult result(
            ChangeSpecEvaluationMode mode,
            boolean success,
            boolean completed,
            String digest,
            Path draftDiagnostic,
            String diagnosticClassification
    ) {
        return detailedResult(
                mode,
                success,
                success,
                completed,
                digest,
                mode == ChangeSpecEvaluationMode.SPEC_WITH_REPAIR ? 1 : 0,
                100L,
                20L,
                success ? 120L : 60_000L,
                draftDiagnostic,
                diagnosticClassification);
    }

    private static ChangeSpecEvaluationResult detailedResult(
            ChangeSpecEvaluationMode mode,
            boolean success,
            boolean firstPassSuccess,
            boolean completed,
            String digest,
            int repairCount,
            long productDurationMs,
            long oracleDurationMs,
            long penalizedTtaMs
    ) {
        return detailedResult(mode, success, firstPassSuccess, completed, digest, repairCount,
                productDurationMs, oracleDurationMs, penalizedTtaMs, null, "");
    }

    private static ChangeSpecEvaluationResult detailedResult(
            ChangeSpecEvaluationMode mode,
            boolean success,
            boolean firstPassSuccess,
            boolean completed,
            String digest,
            int repairCount,
            long productDurationMs,
            long oracleDurationMs,
            long penalizedTtaMs,
            Path draftDiagnostic,
            String diagnosticClassification
    ) {
        return new ChangeSpecEvaluationResult(
                "case",
                ChangeSpecEvaluationTier.MEDIUM,
                mode,
                1,
                success,
                firstPassSuccess,
                completed,
                mode.usesChangeSpec(),
                mode.usesChangeSpec() && completed,
                false,
                completed ? "PASSED" : "FAILED",
                diagnosticClassification,
                repairCount,
                2,
                20,
                10,
                0,
                productDurationMs,
                mode.usesChangeSpec() ? 10L : 0L,
                Math.max(0L, productDurationMs - (mode.usesChangeSpec() ? 20L : 0L)),
                30L,
                40L,
                mode.usesChangeSpec() ? 10L : 0L,
                oracleDurationMs,
                penalizedTtaMs,
                0,
                digest,
                "detail",
                "",
                Path.of("target", mode.name().toLowerCase()),
                draftDiagnostic);
    }

    private static final class StubLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private int calls;

        private StubLlmClient(String... responses) {
            for (String response : responses) {
                this.responses.add(new ChatResponse("assistant", response, List.of(), 10, 10));
            }
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls++;
            return responses.remove();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override public String getModelName() { return "stub-model"; }
        @Override public String getProviderName() { return "stub"; }
    }

    private static final class EligibilityStubLlmClient implements LlmClient {
        private static final Pattern DRAFT_ID = Pattern.compile("CHANGE-\\d{8}-\\d{6}-\\d{3}");
        private final List<String> commands;
        private int calls;

        private EligibilityStubLlmClient(String... commands) {
            this.commands = List.of(commands);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls++;
            Matcher matcher = messages.stream()
                    .map(Message::content)
                    .filter(Objects::nonNull)
                    .map(DRAFT_ID::matcher)
                    .filter(Matcher::find)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("未找到 Draft ID"));
            String content = """
                    ---
                    schema: paicli/change-spec/v1
                    id: %s
                    revision: 1
                    title: 修复安全除法
                    intent:
                      goal: 除数为零时返回空值
                      non_goals: []
                    scope:
                      mode: bounded
                      include: [src/main/java/eval/SafeDivider.java]
                      exclude: []
                    acceptance:
                      - id: AC-1
                        kind: behavior
                        statement: 除数为零时返回空值
                        oracle:
                          type: deterministic
                          verifiers: [VT-TEST]
                      - id: AC-SCOPE
                        kind: scope
                        statement: 修改不得越界
                        oracle:
                          type: deterministic
                          verifiers: [VT-SCOPE]
                    verifiers:
                      - id: VT-SCOPE
                        type: path_scope
                      - id: VT-TEST
                        type: command
                        command: %s
                        expect:
                          exit_code: 0
                          junit_report_glob: target/surefire-reports/TEST-*.xml
                          minimum_tests: 1
                    ---
                    """.formatted(matcher.group(), commands.get(Math.min(calls - 1, commands.size() - 1)));
            return new ChatResponse("assistant", content, List.of(), 10, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override public String getModelName() { return "stub-model"; }
        @Override public String getProviderName() { return "stub"; }
    }

    private static List<String> javaVersionCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-version");
    }
}
