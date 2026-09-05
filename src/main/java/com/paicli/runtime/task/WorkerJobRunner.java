package com.paicli.runtime.task;

@FunctionalInterface
public interface WorkerJobRunner {
    String run(WorkerJob job) throws Exception;
}
