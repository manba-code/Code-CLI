package com.paicli.tool;

/**
 * 一次本地命令执行的结构化结果。调用方不需要从面向 LLM 的展示文本中反解析退出码或超时状态。
 */
public record CommandExecutionResult(
        Status status,
        String command,
        Integer exitCode,
        String output,
        String reason
) {
    public CommandExecutionResult {
        output = output == null ? "" : output;
        reason = reason == null ? "" : reason;
    }

    public static CommandExecutionResult completed(String command, int exitCode, String output) {
        return new CommandExecutionResult(Status.COMPLETED, command, exitCode, output, "");
    }

    public static CommandExecutionResult timedOut(String command, String output) {
        return new CommandExecutionResult(Status.TIMED_OUT, command, null, output, "命令执行超时");
    }

    public static CommandExecutionResult startError(String command, String reason) {
        return new CommandExecutionResult(Status.START_ERROR, command, null, "", reason);
    }

    public static CommandExecutionResult canceled(String command, String reason) {
        return new CommandExecutionResult(Status.CANCELED, command, null, "", reason);
    }

    public static CommandExecutionResult denied(String command, Status status, String reason) {
        if (status != Status.POLICY_DENIED && status != Status.HITL_DENIED) {
            throw new IllegalArgumentException("denied status 必须是 POLICY_DENIED 或 HITL_DENIED");
        }
        return new CommandExecutionResult(status, command, null, "", reason);
    }

    public enum Status {
        COMPLETED,
        TIMED_OUT,
        START_ERROR,
        CANCELED,
        POLICY_DENIED,
        HITL_DENIED
    }
}
