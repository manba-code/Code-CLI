package com.paicli.change;

import com.paicli.runtime.task.WorkerJob;
import com.paicli.runtime.task.WorkerJobLifecycleListener;
import com.paicli.runtime.task.WorkerJobRunner;
import com.paicli.runtime.task.WorkerJobScheduler;

import java.util.Objects;

/** 把 DurableTaskManager 的技术任务翻译成只以 changeId 驱动的 ChangeWorker 调用。 */
public final class ChangeWorkerJobHandler implements WorkerJobRunner, WorkerJobLifecycleListener {
    public static final String JOB_TYPE = "change.execute";

    private final ChangeExecutionControl executionControl;
    private final ChangeWorker worker;
    private final ToolApprovalCoordinator toolApprovals;
    private final boolean failClosedOnRecovery;

    public ChangeWorkerJobHandler(ChangeExecutionControl executionControl, ChangeWorker worker) {
        this(executionControl, worker, null, false);
    }

    public ChangeWorkerJobHandler(ChangeExecutionControl executionControl, ChangeWorker worker,
                                  ToolApprovalCoordinator toolApprovals) {
        this(executionControl, worker, toolApprovals, false);
    }

    public ChangeWorkerJobHandler(ChangeExecutionControl executionControl, ChangeWorker worker,
                                  ToolApprovalCoordinator toolApprovals, boolean failClosedOnRecovery) {
        this.executionControl = Objects.requireNonNull(executionControl, "executionControl");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.toolApprovals = toolApprovals;
        this.failClosedOnRecovery = failClosedOnRecovery;
    }

    public void register(WorkerJobScheduler manager) {
        Objects.requireNonNull(manager, "manager")
                .registerWorkerJobHandler(JOB_TYPE, this, this);
    }

    /** READY -> QUEUED 后投递技术任务；失败时保留 QUEUED，调用方可安全重试投递。 */
    public WorkerJob enqueue(WorkerJobScheduler manager, ChangeTaskId changeId, long expectedVersion) {
        executionControl.queueForExecution(changeId, expectedVersion);
        return Objects.requireNonNull(manager, "manager")
                .enqueueWorkerJob(JOB_TYPE, changeId.value());
    }

    @Override
    public String run(WorkerJob job) {
        ChangeTaskId changeId = changeId(job);
        worker.run(changeId);
        return changeId.value();
    }

    @Override
    public void recovered(WorkerJob job) {
        if (failClosedOnRecovery) {
            executionControl.cancelExecution(changeId(job),
                    "M6a 隔离 Worker 在进程重启前处于 RUNNING；执行结果未知，为避免重放外部动作而安全中止");
            return;
        }
        if (toolApprovals != null && toolApprovals.hadInterruptedApproval(changeId(job))) {
            executionControl.cancelExecution(changeId(job),
                    "进程重启时存在未决工具审批；原执行栈已丢失，为避免重放副作用调用而安全中止");
            return;
        }
        executionControl.recoverExecution(
                changeId(job),
                "Worker Job 从 RUNNING 恢复到 ENQUEUED，recoveryCount=" + job.recoveryCount());
    }

    @Override
    public void canceled(WorkerJob job, String reason) {
        try {
            executionControl.cancelExecution(changeId(job), reason);
        } catch (ChangeConflictException ignored) {
            // 已完成或已失败的 ChangeTask 不被迟到的技术取消覆盖。
        }
    }

    private static ChangeTaskId changeId(WorkerJob job) {
        Objects.requireNonNull(job, "job");
        if (!JOB_TYPE.equals(job.type())) {
            throw new IllegalArgumentException("不支持的 Worker Job type: " + job.type());
        }
        return new ChangeTaskId(job.referenceId());
    }
}
