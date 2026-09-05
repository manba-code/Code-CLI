package com.paicli.change;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryChangeStore implements ChangeStore, ChangeEventStore {
    private final Map<ChangeTaskId, ChangeTask> tasks = new LinkedHashMap<>();
    private final Map<String, ChangeTaskId> idempotencyKeys = new LinkedHashMap<>();
    private final Map<ChangeTaskId, List<ChangeEvent>> eventLog = new LinkedHashMap<>();
    private long nextSequence = 1L;

    @Override
    public synchronized List<ChangeTask> list() {
        return List.copyOf(tasks.values());
    }

    @Override
    public synchronized Optional<ChangeTask> find(ChangeTaskId id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public synchronized Optional<ChangeTask> findByIdempotencyKey(String idempotencyKey) {
        ChangeTaskId id = idempotencyKeys.get(normalizeKey(idempotencyKey));
        return id == null ? Optional.empty() : find(id);
    }

    @Override
    public synchronized ChangeTask create(ChangeTask task, ChangeEvent event) {
        validateEvent(task, event);
        if (tasks.containsKey(task.id())) {
            throw new ChangeConflictException("ChangeTask 已存在: " + task.id().value());
        }
        String key = normalizeKey(task.idempotencyKey());
        if (idempotencyKeys.containsKey(key)) {
            throw new ChangeConflictException("幂等键已存在: " + key);
        }
        tasks.put(task.id(), task);
        idempotencyKeys.put(key, task.id());
        append(event);
        return task;
    }

    @Override
    public synchronized ChangeTask update(long expectedVersion, ChangeTask task, ChangeEvent event) {
        validateEvent(task, event);
        ChangeTask current = tasks.get(task.id());
        if (current == null) {
            throw new ChangeNotFoundException(task.id());
        }
        if (current.version() != expectedVersion) {
            throw new ChangeConflictException(
                    "ChangeTask version 已过期，expected=" + expectedVersion + ", actual=" + current.version());
        }
        if (task.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("新版本必须等于 expectedVersion + 1");
        }
        tasks.put(task.id(), task);
        append(event);
        return task;
    }

    @Override
    public synchronized List<ChangeEvent> events(ChangeTaskId id) {
        return List.copyOf(eventLog.getOrDefault(id, List.of()));
    }

    private void append(ChangeEvent event) {
        eventLog.computeIfAbsent(event.changeId(), ignored -> new ArrayList<>())
                .add(event.withSequence(nextSequence++));
    }

    private static void validateEvent(ChangeTask task, ChangeEvent event) {
        if (event == null) {
            throw new NullPointerException("event");
        }
        if (!task.id().equals(event.changeId()) || task.state() != event.newState()) {
            throw new IllegalArgumentException("Change Event 必须描述同一 ChangeTask 的新状态");
        }
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        return value.trim();
    }
}
