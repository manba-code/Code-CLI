package com.paicli.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceChangeTrackerTest {

    @TempDir
    Path projectRoot;

    @Test
    void comparesAgainstRunBaselineAndIgnoresRuntimeArtifacts() throws Exception {
        write("src/Existing.java", "before\n");
        write("src/Deleted.java", "delete me\n");
        write("preexisting-dirty.txt", "already dirty\n");
        WorkspaceChangeTracker tracker = new WorkspaceChangeTracker(projectRoot);
        WorkspaceChangeTracker.Baseline baseline = tracker.captureBaseline();

        write("src/Existing.java", "after\n");
        Files.delete(projectRoot.resolve("src/Deleted.java"));
        write("src/Added.java", "new\n");
        write("target/generated.txt", "ignored\n");
        write(".paicli/specs/CHANGE-1-r1.md", "runtime\n");
        write(".paicli/runs/run-1/result.json", "runtime\n");

        WorkspaceChangeTracker.WorkspaceChanges changes = tracker.collectChanges(baseline);

        assertEquals(
                List.of("src/Added.java", "src/Deleted.java", "src/Existing.java"),
                changes.changedFiles());
        assertFalse(changes.changedFiles().contains("preexisting-dirty.txt"));
        assertTrue(changes.diff().contains("diff --git a/src/Existing.java b/src/Existing.java"));
        assertTrue(changes.diff().contains("+after"));
        assertTrue(changes.diff().contains("-before"));
        assertFalse(changes.diffTruncated());
    }

    @Test
    void countsFurtherEditsToAFileThatWasDirtyBeforeTheRun() throws Exception {
        write("dirty.txt", "user state\n");
        WorkspaceChangeTracker tracker = new WorkspaceChangeTracker(projectRoot);
        WorkspaceChangeTracker.Baseline baseline = tracker.captureBaseline();

        write("dirty.txt", "agent state\n");

        assertEquals(List.of("dirty.txt"), tracker.collectChanges(baseline).changedFiles());
    }

    private void write(String relative, String content) throws Exception {
        Path target = projectRoot.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
