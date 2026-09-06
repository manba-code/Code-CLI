package com.paicli.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TrustedEvidenceStoreTest {
    @TempDir Path root;

    @Test
    void capturesHashesAndVerifiesAcrossRestart() throws Exception {
        Path database = root.resolve("changes.db");
        ChangeTask task = task();
        try (SqliteChangeStore changes = new SqliteChangeStore(database)) {
            changes.create(task, event(task));
        }
        Path source = evidence("run-one");
        Path archived;
        try (TrustedEvidenceStore store = new TrustedEvidenceStore(database, root.resolve("archive"))) {
            EvidenceStore.Capture capture = store.capture(task.id(), "run-one", source);
            archived = capture.path();
            assertEquals(2, capture.objects().size());
            assertTrue(Files.isRegularFile(archived.resolve("manifest.json")));
            store.verify(task.id(), "run-one", archived);
            Path replacement = evidence("run-one-replacement");
            Files.writeString(replacement.resolve("change.diff"), "must-not-overwrite\n");
            assertEquals(archived, store.capture(task.id(), "run-one", replacement).path());
            assertEquals("diff\n", Files.readString(archived.resolve("change.diff")));
        }
        try (TrustedEvidenceStore restarted = new TrustedEvidenceStore(database, root.resolve("archive"))) {
            restarted.verify(task.id(), "run-one", archived);
        }
    }

    @Test
    void tamperMissingObjectAndUnregisteredArchiveFailClosed() throws Exception {
        Path database = root.resolve("tamper.db");
        ChangeTask task = task();
        try (SqliteChangeStore changes = new SqliteChangeStore(database)) { changes.create(task, event(task)); }
        try (TrustedEvidenceStore store = new TrustedEvidenceStore(database, root.resolve("archive"))) {
            Path archived = store.capture(task.id(), "run-tamper", evidence("run-tamper")).path();
            try { Files.setPosixFilePermissions(archived.resolve("result.json"), Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE)); } catch (UnsupportedOperationException ignored) { }
            Files.writeString(archived.resolve("result.json"), "tampered");
            assertThrows(ChangeConflictException.class, () -> store.verify(task.id(), "run-tamper", archived));
            Path missing = store.capture(task.id(), "run-missing", evidence("run-missing")).path();
            try { Files.setPosixFilePermissions(missing, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
            catch (UnsupportedOperationException ignored) { }
            Files.delete(missing.resolve("change.diff"));
            assertThrows(ChangeConflictException.class, () -> store.verify(task.id(), "run-missing", missing));
            assertThrows(ChangeValidationException.class,
                    () -> store.verify(task.id(), "unknown-run", root.resolve("archive/unknown")));
        }
    }

    @Test
    void rejectsSymlinkAndIncompleteEvidenceBeforeDatabaseRegistration() throws Exception {
        Path database = root.resolve("invalid.db");
        ChangeTask task = task();
        try (SqliteChangeStore changes = new SqliteChangeStore(database)) { changes.create(task, event(task)); }
        try (TrustedEvidenceStore store = new TrustedEvidenceStore(database, root.resolve("archive"))) {
            Path incomplete = Files.createDirectories(root.resolve("incomplete"));
            Files.writeString(incomplete.resolve("result.json"), "{}");
            assertThrows(java.io.IOException.class, () -> store.capture(task.id(), "run-incomplete", incomplete));
            Path linked = evidence("run-linked");
            try {
                Files.createSymbolicLink(linked.resolve("escape"), root.resolve("outside"));
            } catch (UnsupportedOperationException error) {
                return;
            }
            assertThrows(java.io.IOException.class, () -> store.capture(task.id(), "run-linked", linked));
        }
    }

    private Path evidence(String name) throws Exception {
        Path path = Files.createDirectories(root.resolve("source-" + name));
        Files.writeString(path.resolve("result.json"), "{\"runId\":\"" + name + "\"}");
        Files.writeString(path.resolve("change.diff"), "diff\n");
        return path;
    }

    private ChangeTask task() {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        return new ChangeTask(new ChangeTaskId("change_evidence1234"), "evidence-key", 0,
                ChangeState.CREATED, new WorkItemRef("mock", "E-1", ""),
                new RepositoryRef(root.resolve("repo").toString(), "main"), "title", "requirement",
                "", "", null, null, now, now);
    }

    private static ChangeEvent event(ChangeTask task) {
        return new ChangeEvent(0, task.id(), "change.created", "HUMAN", "tester", null,
                ChangeState.CREATED, "{}", task.createdAt());
    }
}
