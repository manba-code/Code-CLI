package com.paicli.change;

/** Control-plane services and the task-scoped execution boundary made available to a Worker runtime. */
public record ChangeWorkerRuntimeContext(ToolApprovalCoordinator toolApprovals, WorkerIsolation.Session isolation) {
    public ChangeWorkerRuntimeContext(ToolApprovalCoordinator toolApprovals) { this(toolApprovals, null); }
    public static ChangeWorkerRuntimeContext none() { return new ChangeWorkerRuntimeContext(null, null); }
    public boolean toolGovernanceEnabled() { return toolApprovals != null; }
    public boolean executionIsolationEnabled() { return isolation != null && isolation.isolated(); }

    public ChangeWorkerRuntimeContext withIsolation(WorkerIsolation.Session value) {
        return new ChangeWorkerRuntimeContext(toolApprovals, value);
    }
}
