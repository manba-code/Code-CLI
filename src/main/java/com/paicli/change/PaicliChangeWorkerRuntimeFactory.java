package com.paicli.change;

import com.paicli.agent.Agent;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.spec.SpecDraftSession;
import com.paicli.spec.SpecExecutionEngine;
import com.paicli.spec.SpecRunCoordinator;
import com.paicli.spec.SpecRunResult;
import com.paicli.spec.SpecVerifier;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/** 生产 Worker Runtime Adapter。每个 ChangeTask 都从工厂创建全新的运行态对象。 */
public final class PaicliChangeWorkerRuntimeFactory implements ChangeWorkerRuntimeFactory {
    private final Function<ExecutionRoute, LlmClient> clients;

    public PaicliChangeWorkerRuntimeFactory(PaiCliConfig config) {
        Objects.requireNonNull(config, "config");
        this.clients = route -> LlmClientFactory.create(route.provider(), route.model(), config);
    }

    PaicliChangeWorkerRuntimeFactory(Function<ExecutionRoute, LlmClient> clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    @Override
    public SpecExecutionEngine create(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(workspace, "workspace");
        ExecutionRoute route = Objects.requireNonNull(task.route(), "ChangeTask.route");
        LlmClient client = clients.apply(route);
        if (client == null) {
            throw new IllegalStateException(
                    "没有可用于 PaiChange Worker route 的 LLM 配置: "
                            + route.provider() + "/" + route.model());
        }
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(workspace.workspaceRoot().toString());
        tools.setCurrentModel(route.provider(), route.model());
        Agent agent = new Agent(client, tools);
        SpecDraftSession unusedDraftSession = new SpecDraftSession(
                request -> {
                    throw new IOException("Worker 不生成 ChangeSpec Draft");
                },
                document -> {
                    throw new IllegalStateException("Worker 不执行同步 Spec 审批");
                });
        return new SpecRunCoordinator(
                workspace.workspaceRoot(),
                workspace.evidenceRoot().resolve("runs"),
                unusedDraftSession,
                request -> request,
                (phase, input, lockedSpec) -> toExecutionResult(agent.runDetailed(input)),
                new SpecVerifier(workspace.workspaceRoot(), tools::executeCommandForVerification),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped(
                        "平台 Worker 不在后台任务中执行人工 Criterion"),
                new SpecRunCoordinator.RunOptions(
                        route.repairEnabled()
                                ? SpecRunCoordinator.RepairPolicy.ENABLED
                                : SpecRunCoordinator.RepairPolicy.DISABLED,
                        attempt -> { }));
    }

    private static SpecRunCoordinator.ReActExecutionResult toExecutionResult(Agent.RunResult result) {
        SpecRunResult.LlmUsage usage = new SpecRunResult.LlmUsage(
                result.llmCalls(),
                result.inputTokens(),
                result.outputTokens(),
                result.cachedInputTokens());
        return switch (result.outcome()) {
            case COMPLETED -> SpecRunCoordinator.ReActExecutionResult.completed(
                    result.response(), usage, result.elapsedMs());
            case CANCELED -> SpecRunCoordinator.ReActExecutionResult.canceled(
                    result.response(), usage, result.elapsedMs());
            case FAILED -> SpecRunCoordinator.ReActExecutionResult.failed(
                    result.response(), usage, result.elapsedMs());
        };
    }
}
