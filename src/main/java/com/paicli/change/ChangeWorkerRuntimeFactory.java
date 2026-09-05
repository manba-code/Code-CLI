package com.paicli.change;

import com.paicli.spec.SpecExecutionEngine;

/** 每次调用都必须创建绑定当前 workspace 的全新 LlmClient、ToolRegistry、Agent 和执行引擎。 */
@FunctionalInterface
public interface ChangeWorkerRuntimeFactory {
    SpecExecutionEngine create(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace);
}
