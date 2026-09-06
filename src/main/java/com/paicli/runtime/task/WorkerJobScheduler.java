package com.paicli.runtime.task;

/** Persistent reference-only job scheduler seam used by PaiChange. */
public interface WorkerJobScheduler extends AutoCloseable {
    void registerWorkerJobHandler(String type, WorkerJobRunner runner, WorkerJobLifecycleListener lifecycleListener);

    WorkerJob enqueueWorkerJob(String type, String referenceId);

    void start();

    void checkHealth();

    /** Aggregate-only metrics. Implementations must not expose job payloads, references, results, or errors. */
    WorkerQueueMetrics metrics();

    @Override
    void close();
}
