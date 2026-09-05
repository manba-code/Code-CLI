package com.paicli.change;

import com.paicli.runtime.task.DurableTaskManager;
import com.paicli.runtime.task.WorkerJob;
import com.paicli.runtime.task.WorkerJobLifecycleListener;
import com.paicli.runtime.task.WorkerJobRunner;

import java.util.Objects;

/** 把 DurableTaskManager 的技术任务翻译成只以 changeId 驱动的 ChangeWorker 调用。 */
public final class ChangeWorkerJobHandler implements WorkerJobRunner, WorkerJobLifecycleListener {
    public static final String JOB_TYPE = "change.execute";

    private final ChangeExecutionControl executionControl;
    private final ChangeWorker worker;

    public ChangeWorkerJobHandler(ChangeExecutionControl executionControl, ChangeWorker worker) {
        this.executionControl = Objects.requireNonNull(executionControl, "executionControl");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public void register(DurableTaskManager manager) {
        Objects.requireNonNull(manager, "manager")
                .registerWorkerJobHandler(JOB_TYPE, this, this);
    }

    /** READY -> QUEUED 后投递技术任务；失败时保留 QUEUED，调用方可安全重试投递。 */
    public WorkerJob enqueue(DurableTaskManager manager, ChangeTaskId changeId, long expectedVersion) {
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
