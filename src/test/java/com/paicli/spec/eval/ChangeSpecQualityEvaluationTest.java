package com.paicli.spec.eval;

import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "paicli.changeSpecEval.enabled", matches = "true")
class ChangeSpecQualityEvaluationTest {

    @Test
    void compareReactChangeSpecAndEvidenceRepairWithHiddenOracle() throws Exception {
        PaiCliConfig config = PaiCliConfig.load();
        String requestedProvider = System.getProperty("paicli.changeSpecEval.provider", "").trim();
        Supplier<LlmClient> clientFactory = () -> createClient(config, requestedProvider);
        LlmClient probe = clientFactory.get();

        int repetitions = boundedIntProperty("paicli.changeSpecEval.repetitions", 2, 1, 20);
        long seed = Long.getLong("paicli.changeSpecEval.seed", 20260820L);
        double inputCost = nonNegativeDoubleProperty("paicli.changeSpecEval.inputCostPerMillion", 0d);
        double outputCost = nonNegativeDoubleProperty("paicli.changeSpecEval.outputCostPerMillion", 0d);
        long censoredDurationMs = Duration.ofMinutes(
                boundedIntProperty("paicli.changeSpecEval.censorMinutes", 10, 1, 60)).toMillis();
        boolean costConfigured = inputCost > 0 || outputCost > 0;
        List<ChangeSpecEvaluationCase> cases = selectCases(ChangeSpecEvaluationCatalog.defaultCases());

        String runId = Instant.now().toString().replace(':', '-') + "-" + Long.toUnsignedString(seed);
        Path runRoot = Path.of("target", "change-spec-eval", runId).toAbsolutePath().normalize();
        Files.createDirectories(runRoot);
        ChangeSpecEvaluationRunner runner = new ChangeSpecEvaluationRunner(
                clientFactory, runRoot, inputCost, outputCost, censoredDurationMs);

        List<ChangeSpecEvaluationResult> results = new ArrayList<>();
        Random random = new Random(seed);
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (ChangeSpecEvaluationCase evaluationCase : cases) {
                ChangeSpecPairedDraft pairedDraft = runner.preparePairedDraft(evaluationCase);
                List<ChangeSpecEvaluationMode> modes = new ArrayList<>(List.of(ChangeSpecEvaluationMode.values()));
                Collections.shuffle(modes, random);
                for (ChangeSpecEvaluationMode mode : modes) {
                    results.add(runner.run(evaluationCase, mode, repetition, pairedDraft));
                }
            }
        }

        String report = ChangeSpecEvaluationReport.toMarkdown(
                results,
                probe.getProviderName(),
                probe.getModelName(),
                seed,
                repetitions,
                censoredDurationMs,
                costConfigured);
        Path reportFile = runRoot.resolve("report.md");
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        System.out.println("ChangeSpec A/B/C evaluation report: " + reportFile);

        assertEquals(cases.size() * ChangeSpecEvaluationMode.values().length * repetitions, results.size());
        assertTrue(results.stream().anyMatch(result -> result.llmCalls() > 0),
                "没有产生任何成功的 LLM 响应，请检查 API Key、provider 和网络；报告：" + reportFile);
    }

    private static LlmClient createClient(PaiCliConfig config, String requestedProvider) {
        LlmClient client = requestedProvider.isBlank()
                ? LlmClientFactory.createFromConfig(config)
                : LlmClientFactory.create(requestedProvider, config);
        if (client == null) {
            throw new IllegalStateException("没有可用的 LLM 配置。请配置 API Key，或通过 "
                    + "-Dpaicli.changeSpecEval.provider=<provider> 指定 provider");
        }
        return client;
    }

    private static List<ChangeSpecEvaluationCase> selectCases(List<ChangeSpecEvaluationCase> available) {
        String configured = System.getProperty("paicli.changeSpecEval.cases", "").trim();
        if (configured.isBlank()) return available;
        Set<String> requested = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        List<ChangeSpecEvaluationCase> selected = available.stream()
                .filter(value -> requested.contains(value.id())).toList();
        if (selected.size() != requested.size()) {
            Set<String> found = selected.stream().map(ChangeSpecEvaluationCase::id).collect(Collectors.toSet());
            requested.removeAll(found);
            throw new IllegalArgumentException("未知 ChangeSpec 评测用例: " + requested);
        }
        return selected;
    }

    private static int boundedIntProperty(String name, int defaultValue, int min, int max) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 " + min + ".." + max + " 之间");
        }
        return value;
    }

    private static double nonNegativeDoubleProperty(String name, double defaultValue) {
        String raw = System.getProperty(name);
        double value = raw == null || raw.isBlank() ? defaultValue : Double.parseDouble(raw);
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " 必须是非负有限数字");
        }
        return value;
    }
}
