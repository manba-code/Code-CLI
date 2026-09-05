package com.paicli.change;

import java.time.Instant;
import java.util.Objects;

/** 持久化的执行领取凭据；只有持有当前 claimId 的 Worker 才能推进该次执行。 */
public record WorkerClaimRef(String claimId, Instant claimedAt) {
    public WorkerClaimRef {
        claimId = Objects.requireNonNull(claimId, "claimId").trim();
        if (claimId.isEmpty()) {
            throw new IllegalArgumentException("claimId 不能为空");
        }
        claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
    }
}
