package com.paicli.change;

import java.nio.file.Path;
import java.util.Objects;

/** 为一次 ChangeTask 执行准备、封存并释放隔离工作区。 */
public interface WorkspaceProvisioner {
    WorkspaceLease prepare(ChangeTask task) throws Exception;

    WorkspaceSnapshot seal(WorkspaceLease lease) throws Exception;

    void release(WorkspaceLease lease) throws Exception;

    record WorkspaceLease(
            ChangeTaskId changeId,
            String workspaceId,
            Path repositoryRoot,
            Path workspaceRoot,
            Path evidenceRoot,
            String branch,
            String baseSha
    ) {
        public WorkspaceLease {
            changeId = Objects.requireNonNull(changeId, "changeId");
            workspaceId = requireText(workspaceId, "workspaceId");
            repositoryRoot = normalize(repositoryRoot, "repositoryRoot");
            workspaceRoot = normalize(workspaceRoot, "workspaceRoot");
            evidenceRoot = normalize(evidenceRoot, "evidenceRoot");
            branch = requireText(branch, "branch");
            baseSha = requireText(baseSha, "baseSha");
        }
    }

    record WorkspaceSnapshot(String workspaceId, String branch, String headSha, Path evidenceRoot) {
        public WorkspaceSnapshot {
            workspaceId = requireText(workspaceId, "workspaceId");
            branch = requireText(branch, "branch");
            headSha = requireText(headSha, "headSha");
            evidenceRoot = normalize(evidenceRoot, "evidenceRoot");
        }
    }

    private static Path normalize(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
