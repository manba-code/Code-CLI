package com.paicli.agent;

/**
 * Agent 角色定义 - Multi-Agent 系统中的角色分工。
 *
 * <p>角色用于选择系统提示词和工具权限，不承担实例调度或上下文存储；生命周期和
 * conversationHistory 隔离由 AgentOrchestrator、WorkerPool 和 SubAgent 负责。</p>
 */
public enum AgentRole {
    /** 只负责拆分 DAG 计划，不调用执行工具。 */
    PLANNER("规划者", "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),
    /** 执行单个步骤，可调用 ToolRegistry 中允许的工具。 */
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),
    /** 审查步骤结果并提供反馈，不直接执行工具。 */
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
