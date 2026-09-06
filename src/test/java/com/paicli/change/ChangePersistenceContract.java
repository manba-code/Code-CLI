package com.paicli.change;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Shared observable contract; every ChangePersistence adapter runs these assertions. */
final class ChangePersistenceContract {
    private ChangePersistenceContract() { }

    static void exercise(ChangePersistence store, Path root, String suffix) {
        Instant now = Instant.now();
        ChangeTask created = new ChangeTask(new ChangeTaskId("change_" + suffix.substring(0, 12)),
                "contract-" + suffix, 0, ChangeState.CREATED, new WorkItemRef("contract", "C-1", ""),
                new RepositoryRef(root.resolve("repo-" + suffix).toString(), "main"), "contract task",
                "persist atomically", "tester", "", "", null, null, null, null, null, null,
                null, null, null, now, now);
        ChangeEvent createdEvent = event(created, null, "change.created", now);
        assertEquals(created, store.create(created, createdEvent));
        assertEquals(created, store.find(created.id()).orElseThrow());
        assertEquals(created, store.findByIdempotencyKey(created.idempotencyKey()).orElseThrow());
        assertThrows(ChangeConflictException.class, () -> store.create(created, createdEvent));

        ChangeTask failed = created.transition(ChangeState.FAILED, now.plusMillis(1));
        store.update(created.version(), failed, event(failed, created.state(), "change.failed", now.plusMillis(1)));
        assertEquals(failed, store.find(created.id()).orElseThrow());
        assertEquals(2, store.events(created.id()).size());
        assertThrows(ChangeConflictException.class, () -> store.update(created.version(), failed,
                event(failed, created.state(), "change.failed", now.plusMillis(1))));
        assertEquals(failed.version(), store.find(created.id()).orElseThrow().version());
        assertEquals(2, store.events(created.id()).size(), "conflicting update must not append an event");
        store.checkHealth();
        assertTrue(store.schemaVersion() >= 1);
    }

    private static ChangeEvent event(ChangeTask task, ChangeState previous, String type, Instant now) {
        return new ChangeEvent(0, task.id(), type, "SYSTEM", "contract", previous,
                task.state(), "{}", now);
    }
}
