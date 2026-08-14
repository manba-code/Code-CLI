package com.paicli.agent.eval;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.tool.ToolRegistry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AgentEvaluationRunner {
    private static final Pattern REJECTED_COUNT = Pattern.compile("审查未通过\\s+([0-9]+)");

    private final Supplier<LlmClient> clientFactory;
    private final Path workspaceRoot;
    private final double inputCostPerMillion;
    private final double outputCostPerMillion;

    AgentEvaluationRunner(Supplier<LlmClient> clientFactory, Path workspaceRoot,
                          double inputCostPerMillion, double outputCostPerMillion) {
        this.clientFactory = clientFactory;
        this.workspaceRoot = workspaceRoot;
        this.inputCostPerMillion = inputCostPerMillion;
        this.outputCostPerMillion = outputCostPerMillion;
    }

    AgentEvaluationResult run(AgentEvaluationCase evaluationCase,
                              EvaluationMode mode, int repetition) throws Exception {
        Path workspace = workspaceRoot.resolve(evaluationCase.id() + "-r" + repetition + "-"
                + mode.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        evaluationCase.materialize(workspace);
        AgentEvaluationCase.WorkspaceSnapshot baseline = evaluationCase.snapshot(workspace);

        CountingLlmClient client = new CountingLlmClient(clientFactory.get());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toAbsolutePath().normalize().toString());
        MemoryManager memory = isolatedMemory(client, workspace);
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        String finalOutput = "";
        String error = null;

        long started = System.nanoTime();
        try (PrintStream out = new PrintStream(transcript, true, StandardCharsets.UTF_8)) {
            finalOutput = switch (mode) {
                case REACT -> runReact(evaluationCase.task(), client, registry, workspace, out);
                case PLAN_EXECUTE -> runPlan(evaluationCase.task(), client, registry, memory, out);
                case MULTI_AGENT -> runMultiAgent(evaluationCase.task(), client, registry, memory, out);
            };
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long durationMillis = (System.nanoTime() - started) / 1_000_000;

        String log = transcript.toString(StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("run.log"), log, StandardCharsets.UTF_8);
        AgentEvaluationCase.ValidationResult validation = evaluationCase.verify(workspace, baseline);
        int retries = countOccurrences(log, "审查未通过，正在重新执行");
        boolean recovered = log.contains("重试后审查通过");
        AgentEvaluationResult.ReviewDecision decision = reviewDecision(mode, finalOutput);
        double estimatedCost = client.inputTokens() * inputCostPerMillion / 1_000_000d
                + client.outputTokens() * outputCostPerMillion / 1_000_000d;

        return new AgentEvaluationResult(
                evaluationCase.id(), mode, repetition,
                validation.passed(), validation.passedChecks(), validation.totalChecks(),
                client.calls(), client.inputTokens(), client.outputTokens(), client.cachedInputTokens(),
                durationMillis, estimatedCost, decision, retries, recovered,
                validation.detail(), abbreviate(finalOutput, 2_000), error, workspace);
    }

    private static String runReact(String task, CountingLlmClient client, ToolRegistry registry,
                                   Path workspace, PrintStream out) {
        // 原始 Agent API 不支持注入 MemoryManager，通过存储目录属性保持评测记忆隔离。
        String property = "paicli.memory.dir";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, workspace.resolve(".eval-react-memory").toString());
            Agent agent = new Agent(client, registry);
            agent.setRenderer(new EvaluationRenderer(out));
            agent.setReturnFinalResponseWhenStreamed(true);
            return agent.run(task);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static String runPlan(String task, CountingLlmClient client, ToolRegistry registry,
                                  MemoryManager memory, PrintStream out) {
        PlanExecuteAgent agent = new PlanExecuteAgent(client, registry, memory,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), out);
        return agent.run(task);
    }

    private static String runMultiAgent(String task, CountingLlmClient client, ToolRegistry registry,
                                        MemoryManager memory, PrintStream out) {
        AgentOrchestrator orchestrator = new AgentOrchestrator(client, registry, memory, out);
        return orchestrator.run(task);
    }

    private static MemoryManager isolatedMemory(LlmClient client, Path workspace) {
        LongTermMemory longTermMemory = new LongTermMemory(workspace.resolve(".eval-memory").toFile());
        return new MemoryManager(client, 32_768, client.maxContextWindow(), longTermMemory);
    }

    private static AgentEvaluationResult.ReviewDecision reviewDecision(EvaluationMode mode, String output) {
        if (mode != EvaluationMode.MULTI_AGENT || output == null) {
            return AgentEvaluationResult.ReviewDecision.NOT_OBSERVED;
        }
        if (output.contains("✅ 多 Agent 协作任务完成")) {
            return AgentEvaluationResult.ReviewDecision.APPROVED;
        }
        Matcher matcher = REJECTED_COUNT.matcher(output);
        if (matcher.find() && Integer.parseInt(matcher.group(1)) > 0) {
            return AgentEvaluationResult.ReviewDecision.REJECTED;
        }
        return AgentEvaluationResult.ReviewDecision.NOT_OBSERVED;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (text != null && (from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private static final class EvaluationRenderer implements Renderer {
        private final PrintStream out;

        private EvaluationRenderer(PrintStream out) {
            this.out = out;
        }

        @Override public void start() { }
        @Override public void close() { }
        @Override public PrintStream stream() { return out; }
        @Override public boolean rendersReasoning() { return false; }
        @Override public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
            if (toolCalls != null) {
                toolCalls.forEach(call -> out.println("[tool] " + call.function().name()));
            }
        }
        @Override public void appendDiff(String filePath, String before, String after) {
            out.println("[diff] " + filePath);
        }
        @Override public void updateStatus(StatusInfo status) { }
        @Override public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("非交互评测禁止人工审批");
        }
        @Override public int openPalette(String title, List<String> items) { return -1; }
    }
}
