package com.paicli.runtime.task;

public final class WorkerJobCanceledException extends RuntimeException {
    public WorkerJobCanceledException(String message) {
        super(message);
    }
}
