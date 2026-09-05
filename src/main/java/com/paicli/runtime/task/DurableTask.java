package com.paicli.runtime.task;

import java.time.Instant;

public record DurableTask(
        String id,
        TaskStatus status,
        String prompt,
        String result,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String jobType,
        String referenceId,
        int recoveryCount
) {
    public DurableTask(
            String id,
            TaskStatus status,
            String prompt,
            String result,
            String error,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            long durationMs
    ) {
        this(id, status, prompt, result, error, createdAt, startedAt, finishedAt, durationMs,
                "prompt", null, 0);
    }

    public boolean terminal() {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELED;
    }

    public String shortPrompt() {
        if (prompt == null) {
            return "";
        }
        String normalized = prompt.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ').trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    public boolean workerJob() {
        return jobType != null && !jobType.isBlank() && !"prompt".equals(jobType);
    }

    public WorkerJob toWorkerJob() {
        if (!workerJob()) {
            throw new IllegalStateException("DurableTask 不是 Worker Job: " + id);
        }
        return new WorkerJob(id, jobType, referenceId, WorkerJobStatus.from(status), recoveryCount);
    }
}
