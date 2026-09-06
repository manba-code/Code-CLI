package com.paicli.change;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.policy.AuditLog;
import com.paicli.policy.CommandGuard;
import com.paicli.tool.CommandExecutionResult;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Worker-only registry enforcing project policy before the existing PathGuard/CommandGuard path. */
public final class GovernedToolRegistry extends ToolRegistry {
    private final ToolApprovalCoordinator approvals;
    private final PersistentToolApprovalHandler handler;
    private final ChangeTask task;
    private final Path cwd;
    private final WorkerIsolation.Session isolation;

    public GovernedToolRegistry(ToolApprovalCoordinator approvals, ChangeTask task, Path cwd) {
        this(approvals, task, cwd, null);
    }

    public GovernedToolRegistry(ToolApprovalCoordinator approvals, ChangeTask task, Path cwd,
                                WorkerIsolation.Session isolation) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.task = Objects.requireNonNull(task, "task");
        this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        this.isolation = isolation;
        this.handler = approvals.handler(task, cwd);
    }

    public PersistentToolApprovalHandler approvalHandler() { return handler; }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        long started = System.nanoTime();
        ProjectToolPolicy.Decision decision;
        try {
            decision = approvals.decision(task, name, argumentsJson, cwd);
        } catch (RuntimeException error) {
            return deny(name, argumentsJson, fallbackDecision(), "组织工具策略评估失败");
        }
        if (isolation != null) {
            WorkerIsolation.NetworkDecision network = isolation.networkDecision(name, argumentsJson);
            if (!network.allowed()) return deny(name, argumentsJson, decision, network.reason());
        }
        String guardDenial = commandGuardDenial(name, argumentsJson);
        if (guardDenial != null) return deny(name, argumentsJson, decision, "底层 CommandGuard 拒绝: " + guardDenial);
        if (decision.effect() == ProjectToolPolicy.Effect.DENY) {
            return deny(name, argumentsJson, decision, decision.reason());
        }
        if (decision.effect() == ProjectToolPolicy.Effect.REQUIRE_APPROVAL) {
            ApprovalResult result = handler.requestApproval(ApprovalRequest.of(name, argumentsJson,
                    "PaiChange Worker 工具调用", "ChangeTask " + task.id().value()));
            if (!result.isApproved()) {
                String reason = result.reason() == null || result.reason().isBlank() ? "工具调用未获批准" : result.reason();
                getAuditLog().record(AuditLog.AuditEntry.denyByHitl(name, argumentsJson, reason,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
                return ToolOutput.text("[HITL] 操作已被拒绝：" + reason);
            }
        }
        if (isolation != null && isolation.isolated()) {
            try {
                isolation.ensureHealthy();
            } catch (Exception error) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                String reason = "执行隔离不可用: " + error.getMessage();
                getAuditLog().record(AuditLog.AuditEntry.error(name, argumentsJson, reason, elapsed, null));
                return ToolOutput.text("执行失败: " + reason);
            }
        }
        if ("execute_command".equals(name) && isolation != null && isolation.isolated()) {
            try {
                String command = ChangeJson.MAPPER.readTree(argumentsJson).path("command").asText("").trim();
                CommandExecutionResult result = isolation.executeCommand(command, Duration.ofSeconds(60));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (result.status() == CommandExecutionResult.Status.START_ERROR) {
                    getAuditLog().record(AuditLog.AuditEntry.error(name, argumentsJson, result.reason(), elapsed, null));
                } else {
                    getAuditLog().record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsed, null));
                }
                return ToolOutput.text(format(result));
            } catch (Exception error) {
                return ToolOutput.text("执行命令失败: " + error.getMessage());
            }
        }
        // Existing ToolRegistry performs PathGuard, CommandGuard, size limits and the actual host-side call.
        return super.doExecuteTool(name, argumentsJson);
    }

    @Override public String executeTool(String name, String argumentsJson) { return executeToolOutput(name, argumentsJson).text(); }

    @Override
    public CommandExecutionResult executeCommandForVerification(String command) {
        long started = System.nanoTime();
        String normalized = command == null ? "" : command.trim();
        String arguments = ChangeJson.MAPPER.createObjectNode().put("command", normalized).toString();
        ProjectToolPolicy.Decision decision;
        try { decision = approvals.decision(task, "execute_command", arguments, cwd); }
        catch (RuntimeException error) {
            return deniedVerification(normalized, arguments, fallbackDecision(), "组织工具策略评估失败",
                    CommandExecutionResult.Status.POLICY_DENIED);
        }
        String guard = CommandGuard.check(normalized);
        if (guard != null) {
            return deniedVerification(normalized, arguments, decision, "底层 CommandGuard 拒绝: " + guard,
                    CommandExecutionResult.Status.POLICY_DENIED);
        }
        if (decision.effect() == ProjectToolPolicy.Effect.DENY) {
            return deniedVerification(normalized, arguments, decision, decision.reason(),
                    CommandExecutionResult.Status.POLICY_DENIED);
        }
        if (decision.effect() == ProjectToolPolicy.Effect.REQUIRE_APPROVAL) {
            ApprovalResult result = handler.requestApproval(ApprovalRequest.of("execute_command", arguments,
                    "运行锁定 ChangeSpec 的确定性 command Verifier", "ChangeSpec Verifier（锁定命令，不允许修改）"));
            if (!result.isApproved()) {
                String reason = result.reason() == null || result.reason().isBlank() ? "Verifier 命令未获批准" : result.reason();
                return CommandExecutionResult.denied(normalized, CommandExecutionResult.Status.HITL_DENIED, reason);
            }
        }
        if (isolation == null || !isolation.isolated()) return super.executeCommandForVerification(normalized);
        try {
            isolation.ensureHealthy();
        } catch (Exception error) {
            String reason = "执行隔离不可用: " + error.getMessage();
            getAuditLog().record(AuditLog.AuditEntry.error("execute_command", arguments, reason,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null));
            return CommandExecutionResult.startError(normalized, reason);
        }
        CommandExecutionResult result = isolation.executeCommand(normalized, Duration.ofSeconds(60));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (result.status() == CommandExecutionResult.Status.START_ERROR) {
            getAuditLog().record(AuditLog.AuditEntry.error("execute_command", arguments, result.reason(), elapsed, null));
        } else {
            getAuditLog().record(AuditLog.AuditEntry.allow("execute_command", arguments, elapsed, null));
        }
        return result;
    }

    private ToolOutput deny(String name, String arguments, ProjectToolPolicy.Decision decision, String reason) {
        approvals.recordDenied(handler, name, arguments, decision, reason);
        getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, arguments, reason, 0));
        return ToolOutput.text("🛡️ 组织策略拒绝: " + reason);
    }

    private CommandExecutionResult deniedVerification(String command, String arguments,
                                                       ProjectToolPolicy.Decision decision, String reason,
                                                       CommandExecutionResult.Status status) {
        approvals.recordDenied(handler, "execute_command", arguments, decision, reason);
        getAuditLog().record(AuditLog.AuditEntry.denyByPolicy("execute_command", arguments, reason, 0));
        return CommandExecutionResult.denied(command, status, reason);
    }

    private ProjectToolPolicy.Decision fallbackDecision() {
        return new ProjectToolPolicy.Decision(ProjectToolPolicy.Effect.DENY,
                "组织工具策略评估失败", 1, "policy-error");
    }

    private static String commandGuardDenial(String name, String arguments) {
        if (!"execute_command".equals(name)) return null;
        try { return CommandGuard.check(ChangeJson.MAPPER.readTree(arguments).path("command").asText("")); }
        catch (Exception e) { return "命令参数不是有效 JSON"; }
    }

    private static String format(CommandExecutionResult result) {
        return switch (result.status()) {
            case COMPLETED -> "命令执行完成 (exit code: " + result.exitCode() + ")\n" + result.output();
            case TIMED_OUT -> "命令执行超时，隔离容器已强制清理";
            case CANCELED -> "Worker 执行已取消，隔离容器已清理";
            case START_ERROR -> "执行命令失败: " + result.reason();
            case POLICY_DENIED -> "🛡️ 策略拒绝: " + result.reason();
            case HITL_DENIED -> "[HITL] 操作已被拒绝：" + result.reason();
        };
    }
}
