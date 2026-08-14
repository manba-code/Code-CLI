package com.paicli.agent.eval;

import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

@EnabledIfSystemProperty(named = "paicli.eval.enabled", matches = "true")
class AgentQualityEvaluationTest {

    @Test
    void compareReactPlanAndMultiAgentWithHiddenTests() throws Exception {
        PaiCliConfig config = PaiCliConfig.load();
        String requestedProvider = System.getProperty("paicli.eval.provider", "").trim();
        Supplier<LlmClient> clientFactory = () -> createClient(config, requestedProvider);
        LlmClient probe = clientFactory.get();

        int repetitions = boundedIntProperty("paicli.eval.repetitions", 1, 1, 20);
        long seed = Long.getLong("paicli.eval.seed", 20260810L);
        double inputCost = nonNegativeDoubleProperty("paicli.eval.inputCostPerMillion", 0d);
        double outputCost = nonNegativeDoubleProperty("paicli.eval.outputCostPerMillion", 0d);
        boolean costConfigured = inputCost > 0 || outputCost > 0;
        List<AgentEvaluationCase> cases = selectCases(AgentEvaluationCatalog.defaultCases());

        String runId = Instant.now().toString().replace(':', '-') + "-" + Long.toUnsignedString(seed);
        Path runRoot = Path.of("target", "agent-eval", runId).toAbsolutePath().normalize();
        Path workspaceRoot = runRoot.resolve("workspaces");
        Files.createDirectories(workspaceRoot);
        AgentEvaluationRunner runner = new AgentEvaluationRunner(
                clientFactory, workspaceRoot, inputCost, outputCost);

        List<AgentEvaluationResult> results = new ArrayList<>();
        Random random = new Random(seed);
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (AgentEvaluationCase evaluationCase : cases) {
                List<EvaluationMode> modes = new ArrayList<>(List.of(EvaluationMode.values()));
                Collections.shuffle(modes, random);
                for (EvaluationMode mode : modes) {
                    results.add(runner.run(evaluationCase, mode, repetition));
                }
            }
        }

        String report = AgentEvaluationReport.toMarkdown(results,
                probe.getProviderName(), probe.getModelName(), seed, repetitions, costConfigured);
        Path reportFile = runRoot.resolve("report.md");
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        System.out.println("Agent A/B evaluation report: " + reportFile);

        assertEquals(cases.size() * EvaluationMode.values().length * repetitions, results.size());
        assertTrue(results.stream().anyMatch(result -> result.llmCalls() > 0),
                "没有产生任何成功的 LLM 响应，请检查 API Key、provider 和网络；报告：" + reportFile);
    }

    private static LlmClient createClient(PaiCliConfig config, String requestedProvider) {
        LlmClient client = requestedProvider.isBlank()
                ? LlmClientFactory.createFromConfig(config)
                : LlmClientFactory.create(requestedProvider, config);
        if (client == null) {
            throw new IllegalStateException("没有可用的 LLM 配置。请配置 API Key，或通过 "
                    + "-Dpaicli.eval.provider=<provider> 指定 provider");
        }
        return client;
    }

    private static List<AgentEvaluationCase> selectCases(List<AgentEvaluationCase> available) {
        String configured = System.getProperty("paicli.eval.cases", "").trim();
        if (configured.isBlank()) return available;
        Set<String> requested = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        List<AgentEvaluationCase> selected = available.stream()
                .filter(value -> requested.contains(value.id())).toList();
        if (selected.size() != requested.size()) {
            Set<String> found = selected.stream().map(AgentEvaluationCase::id).collect(Collectors.toSet());
            requested.removeAll(found);
            throw new IllegalArgumentException("未知评测用例: " + requested);
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
