package com.paicli.change;

import com.paicli.spec.SpecRunResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** ChangeTask 对一次已持久化 Spec Run 和最终代码身份的引用。 */
public record RunRef(
        String runId,
        String specDigest,
        SpecRunResult.Status status,
        SpecRunResult.Verdict verdict,
        String workspaceId,
        String branch,
        String headSha,
        Path evidencePath,
        Instant completedAt
) {
    public RunRef {
        runId = requireText(runId, "runId");
        specDigest = requireText(specDigest, "specDigest");
        status = Objects.requireNonNull(status, "status");
        verdict = Objects.requireNonNull(verdict, "verdict");
        workspaceId = requireText(workspaceId, "workspaceId");
        branch = requireText(branch, "branch");
        headSha = requireText(headSha, "headSha");
        evidencePath = Objects.requireNonNull(evidencePath, "evidencePath").toAbsolutePath().normalize();
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
