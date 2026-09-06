package com.paicli.change;

import com.paicli.tool.CommandExecutionResult;

import java.time.Duration;

/**
 * Per-task execution boundary. The control plane, LLM client, approvals and database stay outside this seam;
 * untrusted shell and verifier processes use the returned task-scoped session.
 */
public interface WorkerIsolation extends AutoCloseable {
    Session open(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace) throws Exception;

    /** Remove containers left by a previous process before recovered jobs are allowed to run. */
    default void recoverOrphans() throws Exception { }

    Capabilities capabilities();

    @Override
    default void close() { }

    interface Session extends AutoCloseable {
        boolean isolated();

        Duration taskTimeout();

        CommandExecutionResult executeCommand(String command, Duration timeout);

        /** Host-side web/MCP tools need a second explicit project egress decision. */
        NetworkDecision networkDecision(String toolName, String argumentsJson);

        /** Fail the task if the boundary disappeared before the control plane accepted its result. */
        default void ensureHealthy() throws Exception { }

        void abort(String reason);

        @Override
        void close();
    }

    record NetworkDecision(boolean allowed, String reason) {
        public static NetworkDecision allow() { return new NetworkDecision(true, ""); }
        public static NetworkDecision deny(String reason) { return new NetworkDecision(false, reason); }
    }

    record Capabilities(boolean enabled, String runtime, String image, double cpus,
                        int memoryMb, int pidsLimit, int taskTimeoutSeconds,
                        String networkMode, boolean secretsInjected) {
        public static Capabilities disabled() {
            return new Capabilities(false, "host", "", 0, 0, 0, 0,
                    "host-policy-only", false);
        }
    }

    static WorkerIsolation none() {
        return new WorkerIsolation() {
            @Override
            public Session open(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace) {
                return new Session() {
                    @Override public boolean isolated() { return false; }
                    @Override public Duration taskTimeout() { return Duration.ZERO; }
                    @Override public CommandExecutionResult executeCommand(String command, Duration timeout) {
                        return CommandExecutionResult.startError(command, "Docker 执行隔离未启用");
                    }
                    @Override public NetworkDecision networkDecision(String toolName, String argumentsJson) {
                        return NetworkDecision.allow();
                    }
                    @Override public void abort(String reason) { }
                    @Override public void close() { }
                };
            }

            @Override public Capabilities capabilities() { return Capabilities.disabled(); }
        };
    }
}
