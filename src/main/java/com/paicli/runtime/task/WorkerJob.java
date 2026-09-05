package com.paicli.runtime.task;

import java.util.Objects;

/** 技术队列记录。referenceId 是唯一业务载荷；ChangeTask 上下文不复制到队列。 */
public record WorkerJob(
        String id,
        String type,
        String referenceId,
        WorkerJobStatus status,
        int recoveryCount
) {
    public WorkerJob {
        id = requireText(id, "id");
        type = requireText(type, "type");
        referenceId = requireText(referenceId, "referenceId");
        status = Objects.requireNonNull(status, "status");
        recoveryCount = Math.max(0, recoveryCount);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
