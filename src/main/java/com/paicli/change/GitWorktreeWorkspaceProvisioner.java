package com.paicli.change;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 本地 MVP 的 Git worktree Adapter；不会读取或覆盖原 checkout 的未提交内容。 */
public final class GitWorktreeWorkspaceProvisioner implements WorkspaceProvisioner {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private final Path workRoot;

    public GitWorktreeWorkspaceProvisioner(Path workRoot) {
        this.workRoot = Objects.requireNonNull(workRoot, "workRoot").toAbsolutePath().normalize();
    }

    public Path artifactRoot() { return workRoot.resolve("artifacts"); }

    public static GitWorktreeWorkspaceProvisioner createDefault() {
        String configured = System.getProperty("paichange.workspace.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICHANGE_WORKSPACE_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".paichange", "workspaces").toString();
        }
        return new GitWorktreeWorkspaceProvisioner(Path.of(configured));
    }

    @Override
    public WorkspaceLease prepare(ChangeTask task) throws Exception {
        Objects.requireNonNull(task, "task");
        Path configuredRepository = Path.of(task.repository().repository()).toAbsolutePath().normalize();
        if (!Files.isDirectory(configuredRepository)) {
            throw new IOException("ChangeTask repository 不是本地目录: " + configuredRepository);
        }
        Path repositoryRoot = Path.of(git(configuredRepository, Set.of(0),
                "rev-parse", "--show-toplevel").output().trim()).toRealPath();
        if (workRoot.startsWith(repositoryRoot)) {
            throw new IOException("PaiChange worktree 根目录不能位于源仓库内部: " + workRoot);
        }

        Files.createDirectories(workRoot.resolve("worktrees"));
        Files.createDirectories(workRoot.resolve("artifacts"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String workspaceId = task.id().value() + "_" + suffix;
        String branch = "paichange/" + task.id().value() + "/" + suffix;
        Path workspace = workRoot.resolve("worktrees").resolve(workspaceId).normalize();
        Path evidence = workRoot.resolve("artifacts").resolve(workspaceId).normalize();
        requireDirectChild(workRoot.resolve("worktrees"), workspace, "workspace");
        requireDirectChild(workRoot.resolve("artifacts"), evidence, "evidence");
        String baseSha = git(repositoryRoot, Set.of(0),
                "rev-parse", "--verify", task.repository().baseRef() + "^{commit}").output().trim();

        boolean prepared = false;
        try {
            git(repositoryRoot, Set.of(0),
                    "worktree", "add", "-b", branch, workspace.toString(), baseSha);
            Files.createDirectories(evidence.resolve("runs"));
            workspace = workspace.toRealPath();
            evidence = evidence.toRealPath();
            prepared = true;
            return new WorkspaceLease(
                    task.id(), workspaceId, repositoryRoot, workspace, evidence, branch, baseSha);
        } finally {
            if (!prepared && Files.exists(workspace)) {
                try {
                    git(repositoryRoot, Set.of(0), "worktree", "remove", "--force", workspace.toString());
                } catch (Exception ignored) {
                    // 保留原始 prepare 异常；残留目录可通过 git worktree prune 审计和清理。
                }
            }
        }
    }

    @Override
    public WorkspaceSnapshot seal(WorkspaceLease lease) throws Exception {
        Objects.requireNonNull(lease, "lease");
        requireRegisteredWorkspace(lease);
        git(lease.workspaceRoot(), Set.of(0), "add", "-A");
        GitResult diff = git(lease.workspaceRoot(), Set.of(0, 1), "diff", "--cached", "--quiet");
        if (diff.exitCode() == 1) {
            git(lease.workspaceRoot(), Set.of(0),
                    "-c", "user.name=PaiChange Worker",
                    "-c", "user.email=worker@paichange.local",
                    "commit", "--no-gpg-sign", "-m", "PaiChange " + lease.changeId().value());
        }
        String headSha = git(lease.workspaceRoot(), Set.of(0), "rev-parse", "HEAD").output().trim();
        return new WorkspaceSnapshot(lease.workspaceId(), lease.branch(), headSha, lease.evidenceRoot());
    }

    @Override
    public void release(WorkspaceLease lease) throws Exception {
        Objects.requireNonNull(lease, "lease");
        if (!Files.exists(lease.workspaceRoot())) {
            return;
        }
        git(lease.repositoryRoot(), Set.of(0),
                "worktree", "remove", "--force", lease.workspaceRoot().toString());
        git(lease.repositoryRoot(), Set.of(0), "worktree", "prune");
    }

    private static void requireRegisteredWorkspace(WorkspaceLease lease) throws IOException {
        if (!Files.isDirectory(lease.workspaceRoot())) {
            throw new IOException("Git worktree 不存在: " + lease.workspaceRoot());
        }
        Path actual = Path.of(git(lease.workspaceRoot(), Set.of(0),
                "rev-parse", "--show-toplevel").output().trim()).toRealPath();
        if (!actual.equals(lease.workspaceRoot().toRealPath())) {
            throw new IOException("Workspace lease 与实际 Git worktree 不一致");
        }
    }

    private static void requireDirectChild(Path parent, Path child, String name) throws IOException {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        if (!normalizedParent.equals(child.getParent())) {
            throw new IOException(name + " 路径超出 PaiChange 工作根目录");
        }
    }

    private static GitResult git(Path directory, Set<Integer> acceptedExitCodes, String... arguments)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed;
        try {
            completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("Git 命令被中断", e);
        }
        if (!completed) {
            terminate(process);
            throw new IOException("Git 命令超时: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!acceptedExitCodes.contains(process.exitValue())) {
            throw new IOException("Git 命令失败 (exit=" + process.exitValue() + "): "
                    + compact(output));
        }
        return new GitResult(process.exitValue(), output);
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }

    private static String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private record GitResult(int exitCode, String output) {
    }
}
