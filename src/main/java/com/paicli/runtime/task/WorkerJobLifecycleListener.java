package com.paicli.runtime.task;

public interface WorkerJobLifecycleListener {
    WorkerJobLifecycleListener NO_OP = new WorkerJobLifecycleListener() { };

    default void recovered(WorkerJob job) {
    }

    default void canceled(WorkerJob job, String reason) {
    }
}
