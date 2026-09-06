package com.paicli.runtime.task;

/** Bounded operational projection; it contains no prompts, references, results, or errors. */
public record WorkerQueueMetrics(
        long enqueued,
        long running,
        long completed,
        long failed,
        long canceled,
        long recoveries,
        long oldestEnqueuedAgeSeconds
) {
    public static WorkerQueueMetrics empty() {
        return new WorkerQueueMetrics(0, 0, 0, 0, 0, 0, 0);
    }
}
