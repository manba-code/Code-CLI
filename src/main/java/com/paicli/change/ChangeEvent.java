package com.paicli.change;

import java.time.Instant;
import java.util.Objects;

public record ChangeEvent(
        long sequence,
        ChangeTaskId changeId,
        String type,
        String actorType,
        String actorId,
        ChangeState previousState,
        ChangeState newState,
        String payloadJson,
        Instant createdAt
) {
    public ChangeEvent {
        changeId = Objects.requireNonNull(changeId, "changeId");
        type = requireText(type, "type");
        actorType = requireText(actorType, "actorType");
        actorId = actorId == null ? "" : actorId.trim();
        payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public ChangeEvent withSequence(long value) {
        return new ChangeEvent(
                value, changeId, type, actorType, actorId, previousState, newState, payloadJson, createdAt);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
