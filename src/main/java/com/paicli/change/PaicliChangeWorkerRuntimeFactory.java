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
        return createEngine(task, workspace, null, null);
    }

    @Override
    public SpecExecutionEngine create(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace,
                                      ChangeWorkerRuntimeContext context) {
        return createEngine(task, workspace,
                context != null && context.toolGovernanceEnabled() ? context.toolApprovals() : null,
                context == null ? null : context.isolation());
    }

    private SpecExecutionEngine createEngine(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace,
                                             ToolApprovalCoordinator approvals, WorkerIsolation.Session isolation) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(workspace, "workspace");
        ExecutionRoute route = Objects.requireNonNull(task.route(), "ChangeTask.route");
        LlmClient client = clients.apply(route);
        if (client == null) {
            throw new IllegalStateException(
                    "没有可用于 PaiChange Worker route 的 LLM 配置: "
                            + route.provider() + "/" + route.model());
        }
        ToolRegistry tools = approvals == null
                ? new ToolRegistry()
                : new GovernedToolRegistry(approvals, task, workspace.workspaceRoot(), isolation);
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
        String governancePrompt = approvals == null ? "" : """
                [PaiChange Worker 工具治理]
                当前 route 的工具策略 profile 为 %s。每次工具调用都可能被组织策略允许、要求后台人工审批或拒绝；
                工具返回拒绝/超时/取消时不得声称操作已执行，也不得通过替代工具绕过。Evidence 修复与锁定 command Verifier 使用同一权限边界。

                """.formatted(route.toolPolicy().name());
        return new SpecRunCoordinator(
                workspace.workspaceRoot(),
                workspace.evidenceRoot().resolve("runs"),
                unusedDraftSession,
                request -> request,
                (phase, input, lockedSpec) -> toExecutionResult(agent.runDetailed(governancePrompt + input)),
                new SpecVerifier(workspace.workspaceRoot(), tools::executeCommandForVerification),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped(
                        "平台 Worker 不在后台任务中执行人工 Criterion"),
                new SpecRunCoordinator.RunOptions(
                        route.repairEnabled()
                                ? SpecRunCoordinator.RepairPolicy.ENABLED
                                : SpecRunCoordinator.RepairPolicy.DISABLED,
                        attempt -> { },
                        identity -> {
                            if (tools instanceof GovernedToolRegistry governed) {
                                governed.approvalHandler().bindRunIdentity(identity);
                            }
                        }));
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
