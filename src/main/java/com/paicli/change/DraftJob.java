package com.paicli.change;

import com.paicli.spec.ChangeSpecModule.ChangeContext;
import java.util.UUID;

/** Durable scheduling intent, stored atomically with ChangeTask and its event.
 * Claim count includes attempts interrupted before invoking the provider. */
public record DraftJob(String generation, ChangeContext input, Status status, int attempts,
                       String lease, long availableAt, long deadlineAt, String error) {
    public enum Status { PENDING, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, CANCELED }
    public static final int MAX_ATTEMPTS = 3;
    public static final long TIMEOUT_MS = 600_000;

    public static DraftJob pending(ChangeContext input, long now) {
        return new DraftJob(UUID.randomUUID().toString(), input, Status.PENDING, 0, "", now, 0, "");
    }
    public DraftJob claim(long now, long timeoutMs) {
        return new DraftJob(generation, input, Status.RUNNING, attempts + 1,
                UUID.randomUUID().toString(), availableAt, now + timeoutMs, error);
    }
    public DraftJob finish(Status next, long available, String reason) {
        return new DraftJob(generation, input, next, attempts, "", available, 0, reason);
    }
    public boolean due(long now) {
        return (status == Status.PENDING || status == Status.RETRY_WAIT) && availableAt <= now;
    }
    public ChangeContext attemptInput() {
        return new ChangeContext(input.changeId(), input.specId(), input.revision(), input.request(),
                input.projectContext(), input.referencedContext(), generation + "-" + attempts + "-" + lease);
    }
}
