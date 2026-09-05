package com.paicli.change;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorktreeWorkspaceProvisionerTest {
    @TempDir
    Path tempDir;

    @Test
    void isolatesDirtyOriginalCheckoutAndSealsWorkerCommit() throws Exception {
        Assumptions.assumeTrue(gitAvailable());
        Path repository = createRepository();
        Files.writeString(repository.resolve("tracked.txt"), "user dirty\n");
        GitWorktreeWorkspaceProvisioner provisioner = new GitWorktreeWorkspaceProvisioner(
                tempDir.resolve("platform-workspaces"));

        WorkspaceProvisioner.WorkspaceLease lease = provisioner.prepare(task(
                "change_123456789abc", repository));
        assertEquals("committed\n", Files.readString(lease.workspaceRoot().resolve("tracked.txt")));
        Files.writeString(lease.workspaceRoot().resolve("worker.txt"), "worker output\n");

        WorkspaceProvisioner.WorkspaceSnapshot snapshot = provisioner.seal(lease);
        provisioner.release(lease);

        assertEquals("user dirty\n", Files.readString(repository.resolve("tracked.txt")));
        assertFalse(Files.exists(repository.resolve("worker.txt")));
        assertFalse(Files.exists(lease.workspaceRoot()));
        assertTrue(Files.isDirectory(snapshot.evidenceRoot().resolve("runs")));
        assertEquals("worker output\n", git(repository, "show", snapshot.headSha() + ":worker.txt"));
    }

    @Test
    void allocatesDifferentWorktreesAndBranchesPerTask() throws Exception {
        Assumptions.assumeTrue(gitAvailable());
        Path repository = createRepository();
        GitWorktreeWorkspaceProvisioner provisioner = new GitWorktreeWorkspaceProvisioner(
                tempDir.resolve("parallel-workspaces"));

        WorkspaceProvisioner.WorkspaceLease first = provisioner.prepare(task(
                "change_aaaaaaaaaaaa", repository));
        WorkspaceProvisioner.WorkspaceLease second = provisioner.prepare(task(
                "change_bbbbbbbbbbbb", repository));
        try {
            assertNotEquals(first.workspaceRoot(), second.workspaceRoot());
            assertNotEquals(first.branch(), second.branch());
            Files.writeString(first.workspaceRoot().resolve("only-first.txt"), "first\n");
            assertFalse(Files.exists(second.workspaceRoot().resolve("only-first.txt")));
        } finally {
            provisioner.release(first);
            provisioner.release(second);
        }
    }

    private Path createRepository() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("source-repo"));
        git(repository, "init");
        git(repository, "checkout", "-b", "main");
        Files.writeString(repository.resolve("tracked.txt"), "committed\n");
        git(repository, "add", "tracked.txt");
        git(repository,
                "-c", "user.name=Test",
                "-c", "user.email=test@example.invalid",
                "commit", "--no-gpg-sign", "-m", "initial");
        return repository;
    }

    private static ChangeTask task(String id, Path repository) {
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        return new ChangeTask(
                new ChangeTaskId(id),
                "key-" + id,
                0L,
                ChangeState.READY,
                new WorkItemRef("mock", id, ""),
                new RepositoryRef(repository.toString(), "main"),
                "test",
                "test",
                "",
                "",
                new SpecRef("CHANGE-TEST", 1, "digest", repository.resolve("draft"), repository.resolve("locked")),
                null,
                now,
                now);
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String git(Path directory, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("git failed: " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
