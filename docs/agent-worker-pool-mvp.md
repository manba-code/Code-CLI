# Agent Worker Pool 核心 MVP 开发计划

> 历史实验文档：该 WorkerPool / Evidence-based Review 运行时代码已于 2026-08-10 在三轮真实 A/B 均未提升成功率后回退。本文仅保留设计与复盘价值，不代表当前已交付行为。

## 1. 背景

当前 `AgentOrchestrator` 在构造时固定创建两个 Worker。并行批次通过临时
`BlockingQueue` 租借这两个实例，能够避免同一 Worker 被同时占用，但不支持按批次压力
动态创建更多 Worker，也没有统一的池关闭入口。

本次在不引入分布式调度、容器沙箱和工具上下文隔离的前提下，实现一个单 JVM、单次
`/team` 运行范围内的轻量 Worker 生命周期池，让“创建、独占租借、会话重置、复用和关闭”
形成最小闭环。

## 2. 目标

- 每次创建 `AgentOrchestrator` 时预热 2 个 Worker。
- 同一批次没有空闲 Worker 时，按需扩容，最多创建 4 个 Worker。
- 达到上限后等待已有 Worker 归还，不重复占用同一个实例。
- Worker 完成整个步骤（包括 Reviewer 反馈重试）后清理对话历史并归还池。
- 通过 `Lease implements AutoCloseable` 保证正常、异常和中断路径都能归还 Worker。
- `AgentOrchestrator.close()` 显式关闭 Worker 池，关闭后拒绝新租借。
- 保留现有并行输出缓冲、依赖调度和独立 Reviewer 行为。

## 3. 非目标

- 不实现跨进程或跨节点的 Agent 调度。
- 不实现容器、VM 或独立文件系统沙箱。
- 不实现空闲定时缩容；本次的“缩容/销毁”发生在 Orchestrator 关闭时。
- 不拆分或隔离共享 `ToolRegistry`、`LlmClient`、MCP Server。
- 不持久化 Worker 状态，不实现 Agent 断点恢复。
- 不把 Planner 和 Reviewer 纳入 Worker 池。

## 4. 设计

### 4.1 WorkerPool

新增 `WorkerPool`，封装以下职责：

- 保存空闲 Worker 队列。
- 维护已创建 Worker 数量。
- 在短临界区内完成“取空闲实例或判断是否扩容”。
- 达到上限时在锁外等待归还，避免阻塞创建/归还操作。
- 返回幂等的 `Lease`；`Lease.close()` 负责清理历史并归还。
- 池关闭后清理空闲实例，已租出的实例在归还时直接关闭，不再入队。

核心接口：

```java
final class WorkerPool implements AutoCloseable {
    WorkerPool(int minWorkers, int maxWorkers, IntFunction<SubAgent> workerFactory);

    Lease acquire() throws InterruptedException;
    int maxWorkers();
    Stats stats();
    void forEachWorker(Consumer<SubAgent> action);
    void close();
}
```

### 4.2 并发不变量

- `createdWorkers <= maxWorkers`。
- 一个 Worker 同一时刻只能位于空闲队列或一个有效 Lease 中。
- Worker 必须先清理会话，才能重新进入空闲队列。
- `Lease.close()` 幂等，重复关闭不会重复入队。
- 池关闭后不能创建或租借 Worker。
- 关闭时仍在执行的 Worker 不被并发清理，而是在 Lease 归还时关闭。

### 4.3 AgentOrchestrator 集成

- 用 `WorkerPool` 替换固定 `List<SubAgent> workers`。
- 单步骤路径和并行批次统一通过 `WorkerPool.acquire()` 获取 Worker。
- 并行执行线程数改为 `min(batch.size(), workerPool.maxWorkers())`。
- 动态创建的 Worker 通过统一工厂注入当前 external context 和 Skill 系统。
- 并行 Reviewer 继续按步骤创建独立实例，避免 `conversationHistory` 竞争。
- `AgentOrchestrator` 实现 `AutoCloseable`，只关闭自己拥有的 Worker 池，不关闭共享
  `ToolRegistry`、`LlmClient` 或 `MemoryManager`。

### 4.4 调用方关闭

`Main` 和 `TuiSessionController` 当前都是每次 TEAM 任务创建一个 Orchestrator。本次使用
try-with-resources 包裹该实例，保证任务结束后确定性关闭 Worker 池。

## 5. 文件范围

### 新增

- `src/main/java/com/paicli/agent/WorkerPool.java`
- `src/test/java/com/paicli/agent/WorkerPoolTest.java`
- `docs/agent-worker-pool-mvp.md`

### 修改

- `src/main/java/com/paicli/agent/AgentOrchestrator.java`
- `src/main/java/com/paicli/cli/Main.java`
- `src/main/java/com/paicli/tui/TuiSessionController.java`
- `src/test/java/com/paicli/agent/AgentOrchestratorTest.java`
- `README.md`
- `AGENTS.md`
- `docs/agents-reference.md`

## 6. 实施步骤

1. 实现 `WorkerPool` 的预热、按需扩容、阻塞租借、幂等归还和关闭。
2. 为 `WorkerPool` 补充并发、上限、归还和关闭测试。
3. 将 `AgentOrchestrator` 的单步及并行路径统一接入 Lease。
4. 保持动态 Worker 的 external context 和 Skill 配置与预热 Worker 一致。
5. 在 CLI/TUI 的 TEAM 路径使用 try-with-resources 关闭 Orchestrator。
6. 扩展现有 Multi-Agent 测试，验证 4 个独立步骤可以并行扩容到 4 个 Worker。
7. 同步行为文档并运行定向回归。

## 7. 验收标准

- 构造 WorkerPool 后恰好预热 2 个 Worker。
- 4 个并发租借会按需创建到 4 个不同 Worker。
- 第 5 个租借必须等待已有 Lease 归还。
- 同一 Worker 不会被两个 Lease 同时持有。
- Lease 关闭后 Worker 可以再次租借，且只保留系统提示词。
- Lease 重复关闭不会把同一个 Worker 重复放入队列。
- WorkerPool 关闭后 `acquire()` 明确失败。
- 关闭期间仍在执行的 Worker 在归还时不再进入空闲队列。
- 依赖调度和并行输出顺序不回归；Reviewer 仅接受有效 JSON `approved=true`，持续拒绝、执行/审查异常和被阻塞后继分别形成明确终态。
- 定向测试全部通过：

```bash
mvn test -Dtest=WorkerPoolTest,AgentOrchestratorTest,SubAgentTest,AgentRoleTest,AgentMessageTest -DskipTests=false
```

## 8. 简历与面试边界

完成后可以描述为：

> 在 Multi-Agent 编排中实现单 JVM 轻量级 Worker 生命周期池，通过租约机制保证实例独占，
> 支持按并行任务压力从 2 个 Worker 动态扩容至 4 个，任务后重置会话，并在 Orchestrator
> 关闭时显式回收池内实例。

不能描述为分布式弹性伸缩、容器资源调度或完整 Agent 云平台。

## 9. 实施结果

核心 MVP 已按本计划完成：Worker 池预热 2 个并按需扩容至 4 个，单步与并行路径统一使用
Lease，任务后清理会话，CLI/TUI 在 TEAM 任务结束时显式关闭 Orchestrator。Reviewer 改为严格
JSON 审查，持续拒绝、执行/审查失败和依赖阻塞均保留独立终态。定向测试结果以本次实际验证记录为准。

本次定向 Multi-Agent 测试共 41 个，TUI smoke 测试共 105 个，均通过。常规 `mvn test -Pquick`
共运行 750 个测试，其中 12 个既有/环境相关测试失败，集中在 Web Search 预检、图片 URI、
Memory/RAG、Prompt、代码搜索 Golden Set、Inline 渲染和 Markdown 表格；Multi-Agent 相关用例通过。
