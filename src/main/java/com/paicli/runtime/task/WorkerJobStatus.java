package com.paicli.runtime.task;

public enum WorkerJobStatus {
    ENQUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED;

    static WorkerJobStatus from(TaskStatus status) {
        return WorkerJobStatus.valueOf(status.name());
    }
}
