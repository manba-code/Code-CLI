package com.paicli.change;

public enum ChangeState {
    CREATED,
    DRAFTING_SPEC,
    SPEC_REVIEW,
    READY,
    QUEUED,
    RUNNING,
    VERIFYING,
    DELIVERY_REVIEW,
    PUBLISHING,
    COMPLETED,
    FAILED,
    REJECTED,
    CANCELED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED || this == CANCELED;
    }
}
