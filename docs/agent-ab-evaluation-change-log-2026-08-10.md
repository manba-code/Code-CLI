# PaiCLI Multi-Agent 三轮修改记录

> 日期：2026-08-10  
> 关联报告：[Agent A/B 质量评测 Pilot 测试报告](agent-ab-evaluation-pilot-report-2026-08-10.md)  
> 说明：当前改动尚未形成独立 Git commit，因此本文按实际修改轮次和代码文件记录，不填写虚构的 commit id。

## 1. 记录目的

本文记录围绕 Multi-Agent 和真实 LLM A/B 评测完成的三轮修改：

1. 第一轮：完善 Multi-Agent 基础编排、WorkerPool、Reviewer 状态语义，并建立可重复的真实 LLM 评测机制；
2. 第二轮：根据首轮 A/B 结果，用最小 P0 改动修复计划解析、角色工具提示、计划粒度和 Windows 命令执行问题；
3. 第三轮：根据第二轮暴露的“Worker 只有自述、Reviewer 仅凭自述放行”问题，引入结构化执行证据和基于证据的审查门槛。

它只记录“修改了什么、为什么修改、如何验证”。三轮真实评测结果、性能和问题分析以关联报告为准。

## 2. 第一轮：Multi-Agent 基础能力和 A/B 评测建设

### 2.1 修改背景

原有 Multi-Agent 已具备 Planner、Worker、Reviewer 的基本流程，但存在以下工程缺口：

- Worker 以固定列表管理，池的预热、扩容、独占租借和归还语义不完整；
- Reviewer 异常输出可能与业务拒绝混淆；
- 前置步骤失败后，后继步骤可能停留在 `PENDING`；
- 并行步骤可能共享 Reviewer 对话历史；
- CLI/TUI 调用方没有统一管理 Orchestrator 生命周期；
- 缺少能够用隐藏测试客观比较 ReAct、Plan 和 Multi-Agent 的真实评测工具。

### 2.2 新增 WorkerPool

新增文件：

- `src/main/java/com/paicli/agent/WorkerPool.java`
- `src/test/java/com/paicli/agent/WorkerPoolTest.java`

主要行为：

- 启动时预热 2 个 Worker；
- 当并行任务增加时按需扩容，最多 4 个；
- `acquire()` 返回独占 `Lease`，避免同一个 Worker 被并发任务同时使用；
- `Lease.close()` 清理 Worker 对话历史并归还池；
- `WorkerPool.close()` 关闭池并清理空闲 Worker；
- 暴露 `Stats(total, idle, busy, max, closed)` 供测试和诊断使用。

核心生命周期：

```java
try (WorkerPool.Lease lease = workerPool.acquire()) {
    runStep(step, steps, retryCount, lease.worker(), reviewer, context, out);
}
```

这样 Worker 的生命周期覆盖“首次执行 → Reviewer 审查 → 携带反馈重试”，整个步骤完成后才归还。

### 2.3 AgentOrchestrator 接入池化 Worker

修改文件：`src/main/java/com/paicli/agent/AgentOrchestrator.java`

主要修改：

- 固定 Worker 列表替换为 `WorkerPool`；
- 单步和并行批次统一通过 `Lease` 获取 Worker；
- 并行批次按任务压力使用最多 4 个 Worker；
- 并行步骤各自创建独立 Reviewer；
- 动态创建的 Worker/Reviewer 统一继承 external context 和 Skill 配置；
- `AgentOrchestrator` 实现 `AutoCloseable`，关闭时回收 WorkerPool。

并行 Reviewer 使用独立实例的原因是：如果共享同一个 `conversationHistory`，多个步骤会同时写入和清理历史，Reviewer 可能把 A 步骤的上下文带到 B 步骤。

### 2.4 收紧 Reviewer 状态语义

修改文件：`src/main/java/com/paicli/agent/AgentOrchestrator.java`

步骤状态扩展为：

```java
PENDING
RUNNING
COMPLETED
REVIEW_REJECTED
FAILED
BLOCKED
```

审查判决分为：

```java
APPROVED
REJECTED
INVALID
```

行为约束：

- 只有有效 JSON 且布尔字段 `approved=true` 才能完成步骤；
- 明确 `approved=false` 才触发 Worker 修正；
- 最多修正 2 次，即首次执行后最多再执行两次；
- 两次修正后仍拒绝，状态为 `REVIEW_REJECTED`；
- Reviewer 调用错误、空输出、非法 JSON、缺失或非布尔 `approved` 均为 `FAILED`；
- 依赖 `FAILED`、`REVIEW_REJECTED`、`BLOCKED` 的后继步骤标记为 `BLOCKED`；
- 独立分支不受其他失败分支影响，仍可继续执行。

这解决了“Reviewer 故障被误报为质量拒绝”和“后继步骤永久显示等待”的问题。

### 2.5 CLI/TUI 生命周期接入

修改文件：

- `src/main/java/com/paicli/cli/Main.java`
- `src/main/java/com/paicli/tui/TuiSessionController.java`

调用方改为 try-with-resources：

```java
try (AgentOrchestrator orchestrator = createTeamAgent(...)) {
    result = orchestrator.run(task);
}
```

目的：保证正常结束、异常和取消路径都能关闭 WorkerPool，不留下可继续持有共享 `ToolRegistry` 的 Worker。

### 2.6 建立真实 LLM A/B 评测

修改或新增：

- `pom.xml`：新增 `agent-eval` Profile；
- `src/test/java/com/paicli/agent/eval/`：评测模式、用例、运行器、指标统计和 Markdown 报告；
- `src/main/java/com/paicli/agent/Agent.java`：增加可注入独立 `MemoryManager` 的构造入口；
- `docs/agent-quality-ab-evaluation.md`：评测使用说明。

评测设计：

- 相同任务分别交给 ReAct、Plan-and-Execute、Multi-Agent；
- 每次运行使用独立临时工作区和长期记忆；
- Agent 退出后才注入隐藏 JUnit 测试；
- 同时检查允许修改文件白名单；
- 记录成功率、检查通过率、LLM 调用、Token、耗时、Reviewer 判决和纠正恢复；
- 默认不进入 CI，只有显式运行 `mvn test -Pagent-eval` 才会调用真实 API。

### 2.7 第一轮验证

确定性测试覆盖：

- WorkerPool 预热、扩容、独占租借、归还和关闭；
- 四个独立步骤并行扩容到四个 Worker；
- Reviewer 明确拒绝后的两次修正；
- Reviewer 调用异常和协议异常；
- 失败、拒绝和阻塞状态传播；
- 独立 Reviewer 和动态上下文配置。

首轮真实评测结果：

| 模式 | 结果 |
|---|---:|
| ReAct | 2/2 |
| Plan-and-Execute | 1/2 |
| Multi-Agent | 0/2 |

首轮 Multi-Agent 的两次失败主要发生在完整质量闭环之前，由此产生第二轮 P0 修复。

## 3. 第二轮：首轮 A/B 后的最小 P0 修复

### 3.1 Planner JSON 提取

修改文件：`src/main/java/com/paicli/agent/AgentOrchestrator.java`

首轮 `safe-divider` 中，Planner 返回“说明文字 + JSON 代码块”，旧逻辑删除反引号后把说明文字与 JSON 一起交给 Jackson，导致计划解析失败。

第二轮增加 `extractPlanJson()`：

- 优先提取 Markdown JSON 代码块；
- 没有代码块时，从文本中提取第一个括号平衡的 JSON 对象；
- 正确处理 JSON 字符串中的引号和转义字符；
- 提取后使用 `FAIL_ON_TRAILING_TOKENS` 严格解析对象。

这里只对 Planner 做有限容错。Reviewer 仍然必须返回纯审批 JSON，避免把协议异常误判为通过。

对应测试：

- `shouldParsePlanWithExplanationAroundMarkdownCodeBlock`
- `shouldParsePlanWithPlainTextPrefix`

### 3.2 角色工具提示与真实权限一致

修改文件：

- `src/main/java/com/paicli/agent/SubAgent.java`
- `src/main/java/com/paicli/prompt/PromptAssembler.java`
- `src/test/java/com/paicli/agent/SubAgentTest.java`
- `src/test/java/com/paicli/prompt/PromptAssemblerTest.java`

修改前，Planner/Reviewer 的请求没有 tool definitions，但 system prompt 仍可能出现“你可以使用以下工具”。

修改后：

```java
.toolsEnabled(shouldUseTools()
        && (llmClient == null || llmClient.supportsTools()))
```

只有 Worker 同时获得工具提示和实际工具定义。Prompt 工具段裁剪同时兼容 Windows CRLF 行尾。

### 3.3 调整 Planner 拆步原则

修改文件：`src/main/resources/prompts/modes/team-planner.md`

新增原则：

- 每个步骤必须交付可独立验收的结果；
- 搜索、读取、分析、修改和测试通常是 Worker 步骤内部动作；
- 简单单文件修改通常只生成一个“实现并验证”步骤；
- 复杂任务按交付物拆解，而不是为了凑数量拆解。

本次只修改 Prompt，没有增加代码级 PlanValidator。第二轮真实评测表明模型仍会生成“读取 → 分析 → 写入 → 验证”四步计划，因此这一项只部分生效，后续需要确定性计划规范化。

### 3.4 Windows 命令执行支持

修改文件：

- `src/main/java/com/paicli/tool/ToolRegistry.java`
- `src/test/java/com/paicli/tool/ToolRegistryTest.java`

修改前固定使用：

```java
new ProcessBuilder("bash", "-c", command)
```

修改后按系统选择：

```java
if (isWindows()) {
    return new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command);
}
return new ProcessBuilder("bash", "-c", command);
```

命令超时时还会终止 Shell 的所有后代进程，避免 Windows 子进程继续占用临时工作区。

### 3.5 第二轮验证

确定性验证：

| 验证范围 | 结果 |
|---|---:|
| Multi-Agent、WorkerPool、角色与跨平台命令 | 45/45 |
| PromptAssembler | 4/4 |
| `git diff --check` | 通过 |

第二轮真实评测结果：

| 模式 | 结果 |
|---|---:|
| ReAct | 0/2 |
| Plan-and-Execute | 0/2 |
| Multi-Agent | 0/2 |

第二轮证明最小修复改善了链路深度：两个 Multi-Agent 都成功解析计划并进入 Worker/Reviewer，其中 `ascii-slugifier` 完整执行了四个步骤和 Windows Maven 命令。

但最终质量没有改善：

- `safe-divider` Worker 只描述“将读取”，Reviewer 又返回伪工具 JSON，协议失败；
- `ascii-slugifier` 代码实现错误，但 Reviewer 根据 Worker 的 `BUILD SUCCESS` 自述批准，形成误放行；
- ReAct/Plan 还出现嵌套项目、源码目录 `.class` 等未授权变更；
- 说明下一层瓶颈是执行证据和审查质量，不再是 WorkerPool 或计划入口接线。

## 4. 第三轮：Evidence-based Review P0

### 4.1 修改目标

第二轮已经让 Multi-Agent 进入完整 Worker/Reviewer 链路，但真实评测证明“走完整条链”不等于“完成正确”：

- Worker 可能只说“我将读取/修改”，实际上没有调用工具；
- Worker 可能只报告 `BUILD SUCCESS`，没有提供 Reviewer 可核对的原始命令结果；
- Reviewer 只能看到 Worker 的自然语言总结，因此可能把自述当成事实并误放行；
- Reviewer 偶发输出非法协议时，重新执行 Worker 会造成不必要的成本和副作用。

本轮只做最小闭环：记录真实工具调用、按步骤类型设置最低证据门槛、把证据交给 Reviewer，并为 Reviewer 协议增加一次独立重试。计划规范化、执行期文件白名单和真实 LLM 复测留到后续。

### 4.2 新增单次执行证据模型

新增文件：

- `src/main/java/com/paicli/agent/StepEvidence.java`
- `src/main/java/com/paicli/agent/WorkerExecution.java`
- `src/main/java/com/paicli/agent/StepEvidencePolicy.java`

`SubAgent.executeWithEvidence(...)` 在每次 Worker 执行内部收集 `ToolRegistry.ToolExecutionResult`，返回 Worker 消息和本次执行证据：

```java
record WorkerExecution(AgentMessage message, StepEvidence evidence) {}
```

证据包含工具名、截断后的参数和结果，不使用共享全局监听器。这样并行 Worker 各自持有自己的证据，下一次执行也不会读到上一次的记录。单项参数最多保留 2 KB，单项结果最多保留 6 KB，传给 Reviewer 的证据文本总量最多 16 KB，避免证据反过来挤占上下文。

### 4.3 增加步骤类型的最低证据门槛

`StepEvidencePolicy` 当前规则为：

| 步骤类型 | 最低证据 |
|---|---|
| `FILE_READ` | 至少一次成功的 `read_file` |
| `FILE_WRITE` | 至少一次成功的 `write_file` 或 `create_project` |
| `COMMAND` / `VERIFICATION` | 至少一次 `execute_command` 且结果包含 exit code 0 |
| `ANALYSIS` | 允许纯文本结果 |

Orchestrator 在调用 Reviewer 前先检查证据：

```java
StepEvidencePolicy.Assessment evidenceAssessment =
        StepEvidencePolicy.assess(step.type(), execution.evidence());
if (!evidenceAssessment.satisfied()) {
    // 不进入 Reviewer，携带缺失证据反馈重试 Worker
}
```

证据不足重试与 Reviewer 明确拒绝后的修正共用原有的最多 2 次 Worker 重试预算，避免叠加出两套重试循环。三次执行后仍没有最低证据，步骤标记为 `FAILED`。

### 4.4 Reviewer 改为基于证据审查

修改文件：

- `src/main/java/com/paicli/agent/SubAgent.java`
- `src/main/java/com/paicli/agent/AgentOrchestrator.java`
- `src/main/resources/prompts/modes/team-reviewer.md`

Reviewer 现在同时收到：

1. 原始步骤要求；
2. Worker 的候选结果总结；
3. 本次执行的真实工具调用证据。

Reviewer prompt 明确要求不能只凭 Worker 的“已完成”“准备执行”或 `BUILD SUCCESS` 等文本批准，也不能自行调用工具。只有有效 JSON 且 `approved` 为布尔值 `true` 才能完成步骤。

### 4.5 Reviewer 协议独立重试

首次 Reviewer 返回空文本、非法 JSON、缺少 `approved` 或类型错误时，只重试一次 Reviewer：

- 不重复执行 Worker；
- 第二次仍不合法则步骤 `FAILED`；
- 明确 `approved=false` 仍走原有业务修正流程，不与协议重试混淆。

### 4.6 确定性测试

新增或扩展测试覆盖：

- 各步骤类型的最低证据规则；
- Worker 首次只有自述、补做 `read_file` 后才进入 Reviewer；
- 连续三次缺少证据时 Reviewer 从未被调用，步骤失败；
- Reviewer 首次协议非法时只重试审查，不重跑 Worker；
- Reviewer 两次协议均非法时失败；
- 并行或连续执行间的 Worker 证据不串线；
- 既有 WorkerPool、状态传播、严格审查和 PromptAssembler 行为不回归。

本轮针对性测试结果：57/57 通过；`AgentEvaluationInfrastructureTest` 2/2 通过。该结果只证明代码级状态机、证据策略和评测基础设施符合预期，不代表真实 LLM 质量已经改善；真实复测必须再次显式运行 `agent-eval` 后才能下结论。

同时执行了 `mvn test -Pquick`：共运行 765 个测试，1 个真实评测用例按设计跳过，10 个测试失败。失败分布在图片 file URI、Memory、RAG 索引/检索、inline 换行、Windows 代码搜索路径和 Markdown 表格宽度，均不属于第三轮 Evidence-based Review 的修改文件；本轮 Multi-Agent 相关测试全部通过。由于没有第三轮修改前、同一工作树状态下的 quick 基线，这 10 个失败只记录为待单独诊断项，不在本轮顺带修复，也不把它们直接归因于本轮改造。

### 4.7 第三轮真实 LLM 复测

运行 `mvn test -Pagent-eval`，使用 `glm / glm-4.6v-flashx` 对两个用例、三种模式各执行一次：

| 模式 | 结果 | 平均调用 | 平均输入 Token | 平均耗时 |
|---|---:|---:|---:|---:|
| ReAct | 0/2 | 4.00 | 15,968.50 | 8.08s |
| Plan-and-Execute | 0/2 | 8.50 | 32,726.50 | 32.72s |
| Multi-Agent | 0/2 | 13.00 | 46,451.00 | 37.37s |

本轮证据门槛对 `safe-divider` 生效：Worker 连续三次没有产生 `read_file`，步骤在进入 Reviewer 前 `FAILED`，下游正确 `BLOCKED`。但它没有促使模型真正执行工具，任务仍失败。

`ascii-slugifier` 产生了真实读取、写入和命令证据，但实现正则错误；Reviewer 误判代码语义，并批准了显示 `No tests to run` 的 Maven 结果。隐藏测试 0/2，Reviewer 误放行率仍为 100%。

复测证明第三轮提高的是 fail-closed 的准确性，而不是最终成功率。新的关键缺口是：Reviewer 只收到步骤描述而不是始终对照完整根任务；Planner 可把验证步骤标成 `ANALYSIS` 绕过命令证据；工具证据只能证明“做过”，不能证明“做对”。详细分析见关联报告第 19～26 节。

## 5. 三轮修改后的当前系统状态

已经完成：

- Planner → ExecutionStep → DAG 可执行步骤 → WorkerPool → Reviewer → 最多两次修正 → 状态汇总；
- Worker 预热2个、扩容到4个、Lease独占和自动归还；
- 并行步骤使用独立 Reviewer；
- Worker 单次执行生成局部结构化工具证据；
- 读取、写入、命令/验证步骤有最低证据门槛；
- Reviewer 基于原始要求、候选总结和工具证据审查；
- Reviewer 非法协议只重试审查一次，不重复执行 Worker；
- 严格 Reviewer JSON 和失败关闭；
- `FAILED` / `REVIEW_REJECTED` / `BLOCKED` 状态传播；
- CLI/TUI 生命周期关闭；
- Windows/Unix-like 命令执行；
- 可审计的真实 LLM A/B 评测。

尚未完成：

- 代码级计划合并或 PlanValidator；
- 独立文件 diff / 快照证据，以及结构化测试数量；
- 更可靠的工具成功状态，替代当前对工具结果文本的有限解析；
- 修改文件白名单的执行期约束；
- 根任务透传和 DAG 完成后的整体 Reviewer；
- 足够规模和重复次数的稳定质量结论。

## 6. 后续修改建议

下一轮不建议继续增加角色或扩大 Worker 数量，优先顺序应为：

1. 将完整用户根任务传给每次 Reviewer，并在 DAG 结束后增加一次整体任务验收；
2. 简单任务由计划规范化器合并成一个“实现并验证”步骤，并纠正带验证语义却标为 `ANALYSIS` 的步骤；
3. 为工具执行结果增加结构化 success / exitCode / testCount / noTests，不再依赖结果字符串；
4. 增加文件 diff、修改文件清单和逐条验收规则证据；
5. 将允许修改文件作为执行期约束；
6. 每个真实用例至少重复 3 次，再比较成功率、Reviewer 误放行率、Token 和耗时。

## 7. 面试表述建议

可以这样概括这三轮修改：

> 第一轮我先完善了 Multi-Agent 的工程底座，包括 WorkerPool、独占租借、独立 Reviewer、严格审查状态和失败传播，并建立真实 LLM 隐藏测试评测。首轮评测发现 Multi-Agent 主要死在计划解析和环境接线上。第二轮我采用最小 P0 改动修复 JSON 提取、角色权限提示、计划拆分原则和 Windows Shell，使任务能够进入完整 Worker/Reviewer 链路。复测没有得到更高成功率，但进一步定位到 Worker 缺少执行证据、Reviewer 依赖自然语言自述。第三轮因此加入 StepEvidence、按步骤类型设置证据门槛，并把协议错误与业务拒绝拆成两种重试，让“执行过什么”成为 Reviewer 可检查的数据，而不是继续堆 Agent 数量。

第三轮真实复测后，仍不能表述为“Multi-Agent 已经比单 Agent 更好”。能够证明的是：基础编排链路已可运行，真实评测体系能够客观暴露失败，代码层已经堵住“无工具证据仍进入审查”和“一次协议错误重跑 Worker”两类漏洞；但 0/2 成功率和 100% Reviewer 误放行率也证明工具证据尚未形成完整的业务质量闭环。

## 8. 评测后回退记录

三轮真实 A/B 没有证明运行时改动提高最终成功率，第三轮还出现 Multi-Agent 平均调用和输入 Token 上升。因此在用户确认后，将三轮实验涉及的正常运行路径恢复到仓库 Git HEAD 基线：

- 恢复原始 `AgentOrchestrator`、`SubAgent`、`Agent`、CLI/TUI 调用方式和团队角色 prompt；
- 恢复原始 `PromptAssembler` 与 `ToolRegistry` 行为；
- 移出编译路径：`WorkerPool`、`StepEvidence`、`StepEvidencePolicy`、`WorkerExecution` 及对应测试；
- 恢复原始 Multi-Agent、SubAgent、PromptAssembler 和 ToolRegistry 单测；
- 保留 `agent-eval` Maven Profile、评测代码、三轮报告和 `target/agent-eval` 原始产物；
- 调整评测 Runner 使用原始 Agent API，并通过 `paicli.memory.dir` 保持 ReAct 评测记忆隔离。

回退前已保存：

- 跟踪文件补丁：`docs/agent-runtime-experiment-backup-2026-08-10.patch`；
- 新增运行时/测试类归档：`docs/agent-runtime-experiment-untracked-2026-08-10.zip`。

因此本文第 2～4 节描述的是已回退的历史实验，不代表当前运行时状态；当前 Multi-Agent 行为以 Git HEAD 基线代码为准。
