package com.paicli.change;

/** PaiCLI Worker 的最小业务入口；所有业务上下文都通过 changeId 从 ChangeStore 加载。 */
@FunctionalInterface
public interface ChangeWorker {
    void run(ChangeTaskId changeId);
}
