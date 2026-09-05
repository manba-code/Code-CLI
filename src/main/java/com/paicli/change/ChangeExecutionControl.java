package com.paicli.change;

/**
 * Worker 使用的内部执行 seam。它把 version、claim ownership 和状态迁移留在 ChangeWorkflow 模块内。
 */
public interface ChangeExecutionControl {
    ChangeTask queueForExecution(ChangeTaskId id, long expectedVersion);

    ExecutionLease claimExecution(ChangeTaskId id);

    void recoverExecution(ChangeTaskId id, String reason);

    void cancelExecution(ChangeTaskId id, String reason);

    interface ExecutionLease {
        ChangeTask task();

        void started(String workspaceId);

        void verificationStarted();

        void complete(RunRef run);

        void fail(String reason);
    }
}
