package com.paicli.change;

import java.util.Optional;

public interface ChangeStore {
    java.util.List<ChangeTask> list();

    Optional<ChangeTask> find(ChangeTaskId id);

    Optional<ChangeTask> findByIdempotencyKey(String idempotencyKey);

    ChangeTask create(ChangeTask task, ChangeEvent event);

    ChangeTask update(long expectedVersion, ChangeTask task, ChangeEvent event);
}
