package com.paicli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.browser.BrowserCheckResult;
import com.paicli.policy.AuditLog;
import com.paicli.tool.CommandExecutionResult;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.paicli/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        // HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return super.doExecuteTool(name, argumentsJson);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(name, argumentsJson, browserCheck.sensitiveNotice());
        }
        String mcpServer = ApprovalPolicy.mcpServerName(name);
        if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
            return super.doExecuteTool(name, argumentsJson);
        }

        return executeAfterExplicitApproval(name, argumentsJson, null);
    }

    @Override
    public CommandExecutionResult executeCommandForVerification(String command) {
        if (!hitlHandler.isEnabled() || hitlHandler.isApprovedAllByTool("execute_command")) {
            return super.executeCommandForVerification(command);
        }

        String argumentsJson = MAPPER.createObjectNode()
                .put("command", command == null ? "" : command)
                .toString();
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(
                "execute_command",
                argumentsJson,
                "运行锁定 ChangeSpec 的确定性 command Verifier",
                "ChangeSpec Verifier（锁定命令，不允许修改）");
        ApprovalResult approval = hitlHandler.requestApproval(request);
        if (approval.isRejected() || approval.isSkipped()) {
            String reason = approval.isSkipped()
                    ? "用户跳过"
                    : approval.reason() == null || approval.reason().isBlank()
                    ? "用户拒绝了此操作"
                    : approval.reason();
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    "execute_command", argumentsJson, reason, elapsedMillis(start)));
            return CommandExecutionResult.denied(
                    command,
                    CommandExecutionResult.Status.HITL_DENIED,
                    reason);
        }
        if (approval.decision() == ApprovalResult.Decision.MODIFIED) {
            String reason = "锁定的 Verifier 命令不可修改；请批准原命令或拒绝";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    "execute_command", argumentsJson, reason, elapsedMillis(start)));
            return CommandExecutionResult.denied(
                    command,
                    CommandExecutionResult.Status.HITL_DENIED,
                    reason);
        }
        return super.executeCommandForVerification(command);
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice) {
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null, null, sensitiveNotice);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, reason, elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "用户跳过", elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被跳过");
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数；父类执行路径会负责 allow audit
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.doExecuteTool(name, effectiveArgs);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
