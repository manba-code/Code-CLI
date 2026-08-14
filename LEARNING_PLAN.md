# PaiCLI 简历项目 · 一周面试掌握计划

> **面向**：Java 后端 / Agent 方向面试，简历「Code Cli (Java Agent Cli)」深挖  
> **周期**：7 天 × 每天 3～4 小时（约 24～28 小时）  
> **前提**：本机 Java 17+、Maven 可用；能跑通 `mvn test -Pquick`  
> **目标**：达到 **L3～L4**——能画架构、指到代码行、答追问、做 15 分钟白板演示

---

## 一、你要掌握到什么程度？

| 层级 | 标准 | 自测方式 |
|------|------|----------|
| **L1 名词** | 知道 ReAct / DAG / MCP / RAG / HITL 各解决什么问题 | 30 秒讲完六大块 |
| **L2 流程** | 能画主链路：LLM → tool_calls → executeTools → 回灌 | 白板画 ReAct 一轮 |
| **L3 实现** | 说出关键类、方法、常量（4 并行、90% 阈值、Reviewer 最多 2 次重试） | 不看代码答 10 个追问 |
| **L4 取舍** | 解释「为什么」：保序、环检测、尾部 3 个 user 轮、Schema 裁剪 | 每块至少 1 个 trade-off |

**一周结束时应稳定达到 L3，Day 6～7 冲刺 L4。** 不必背完整 `ToolRegistry`（1500+ 行），但要能在 30 秒内定位到关键方法。

---

## 二、简历六点 ↔ 代码地图（先存这张表）

| 简历表述 | 核心类 | 必读方法 / 常量 | 配套文档 |
|----------|--------|-------------------|----------|
| ReAct + Function Calling 状态机 | `Agent.java` | `run()`、`executeToolCalls()` | `AGENTS.md` 架构表 |
| 统一 ToolRegistry（11 内置 + MCP） | `ToolRegistry.java` | `executeTools()`、`MAX_PARALLEL_TOOLS=4` | `docs/agents-reference.md` |
| 三路径复用调度 | `Agent` / `PlanExecuteAgent` / `SubAgent` | 均调用 `toolRegistry.executeTools()` | 同上 |
| Multi-Agent 三角色 | `AgentOrchestrator.java` | `orchestrate()`、`runStep()`、`MAX_RETRIES_PER_STEP=2` | `docs/agents-reference.md` |
| DAG + 计划级 HITL | `ExecutionPlan.java`、`PlanExecuteAgent.java` | `topologicalSort()`、Plan 审阅 | `README.md` Plan 期 |
| 三层记忆 + 90% 压缩 | `MemoryManager`、`ConversationHistoryCompactor` | `compressionTriggerTokens()`、`DEFAULT_RETAIN_RECENT_ROUNDS=3` | `docs/phase-12-long-context.md` |
| MCP JSON-RPC | `JsonRpcClient`、`McpClient` | `initialize()`、`callTool()` | `docs/phase-10-mcp-core.md` |
| Schema 裁剪 + 工具 HITL | `McpSchemaSanitizer`、`HitlToolRegistry` | `sanitize()` | `docs/phase-11-mcp-advanced.md` |
| RAG AST 分块 + 混合检索 | `CodeChunker`、`CodeRetriever` | `hybridSearch()` | `README.md` RAG 期 |

**11 个核心内置工具**（与 `AGENTS.md` 一致）：

`read_file` · `write_file` · `list_dir` · `glob_files` · `grep_code` · `execute_command` · `create_project` · `search_code` · `web_search` · `web_fetch` · `revert_turn`

（另有 Skill / 浏览器 / `save_memory` 等扩展；面试时说「核心 11 + 扩展」即可。）

---

## 三、每日学习节奏（固定模板）

每天按 **4 段** 执行，不要打乱顺序：

| 时段 | 时长 | 做什么 |
|------|------|--------|
| **① 文档扫读** | 30～45 min | 读 `AGENTS.md` / phase 文档 / README 对应期，建立「问题 → 方案」叙事 |
| **② 代码精读** | 90～120 min | 按下方表格读源码，**边读边在纸上画数据流** |
| **③ 测试验证** | 20～30 min | 跑指定 `mvn test`，对照断言理解行为 |
| **④ 自讲 + 自测** | 30 min | 对着镜子讲 3 分钟 + 完成当日 5 题自测 |

**不要从 `Main.java` / TUI / JLine 开始**——那是产品壳，不是推理内核。Day 7 再补 CLI 演示路径。

---

## 四、七天日程（详细版）

### Day 1（周一）：全局架构 + ReAct 推理内核

**今日目标**：搞懂「一轮 ReAct」完整生命周期；建立三条执行路径的全局图。

#### ① 文档扫读（40 min）

| 顺序 | 文件 | 关注点 |
|------|------|--------|
| 1 | `AGENTS.md` 全文 | 三条路径表、11 工具、并行/HITL 约束 |
| 2 | `docs/agents-reference.md` → ReAct Mode | 退出条件、`AgentBudget` 兜底 |
| 3 | `README.md` 第 1～3 期 skim | 演进叙事：ReAct → 工具 → Memory |

#### ② 代码精读（2h）

**必画一张图（Day 1 交付物）**：

```
用户输入 → Agent.run()
    → buildSystemPrompt + conversationHistory
    → llmClient.call(tools=ToolRegistry schemas)
    → 有 tool_calls?
        否 → 结束，输出 content
        是 → executeToolCalls()
            → toolRegistry.executeTools()   // 最多 4 并行，结果保序
            → 回灌 assistant + tool 消息
            → compactIfNeeded（conversationHistory 压缩）
            → 下一轮
```

| 文件 | 读哪里 | 掌握什么 |
|------|--------|----------|
| `agent/Agent.java` | `run()` 主循环 | 迭代入口、预算检查、流式 reasoning |
| 同上 | `executeToolCalls()`（约 694～713 行） | tool 消息如何 append 到 history |
| `agent/AgentBudget.java` | 全文 | 三种保险阀：token（默认无限）/ 停滞 3 次 / 硬上限 50 轮 |
| `llm/LlmClient.java` + 任一 `*Client.java` skim | Message / tool_calls 结构 | Function Calling 协议字段 |
| `agent/PlanExecuteAgent.java` | 搜索 `executeTools` | 与 ReAct 同入口 |
| `agent/SubAgent.java` | 搜索 `executeTools` | Multi-Agent Worker 也走同一调度 |

**ReAct vs CoT（面试一句话）**：CoT 只推理；ReAct 是 **Reason → Act(tool) → Observe(result) → 再 Reason** 的闭环。

#### ③ 测试验证

```bash
mvn test -Dtest=AgentBudgetTest -DskipTests=false
```

若无专用测试，读 `AgentBudget.java` 类注释中的 `ExitReason` 枚举即可。

#### ④ 自测 5 题

1. ReAct 一轮里，LLM 返回 3 个 `tool_calls` 时，`conversationHistory` 增长几条消息？顺序是什么？
2. `AgentBudget` 默认 token 预算是多少？为什么不是 80% × context window？
3. 停滞检测如何判断「死循环」？默认连续几次相同签名触发？
4. DeepSeek thinking 模式下，`reasoning_content` 为什么要随 history 带回？
5. 三条执行路径在工具层唯一的汇合点是什么？

**Day 1 掌握标准**：脱稿讲 2 分钟「ReAct 状态机 + 三路径共用 ToolRegistry」。

---

### Day 2（周二）：ToolRegistry + 并行调度 + HITL / 策略层

**今日目标**：讲清 11 个内置工具如何注册；并行保序实现；HITL 拦截链。

#### ① 文档扫读（30 min）

| 文件 | 关注点 |
|------|--------|
| `docs/agents-reference.md` → HITL 段 | 拦截顺序、审批策略 |
| `AGENTS.md` → 关键行为约束 / HITL | PathGuard / CommandGuard 定位 |

#### ② 代码精读（2h）

| 文件 | 读哪里 | 掌握什么 |
|------|--------|----------|
| `tool/ToolRegistry.java` | 类头 `MAX_PARALLEL_TOOLS = 4` | 并发上限常量 |
| 同上 | `executeTools()`（约 1232～1280 行） | `ExecutorService`、`Future.get(i)` **按原始下标保序** |
| 同上 | 各 `register*` / 工具 handler skim | 11 工具职责分工 |
| `hitl/HitlToolRegistry.java` | 包装逻辑 | HITL 在 ToolRegistry **之前** |
| `policy/PathGuard.java` | 路径校验 | 强制项目根内 |
| `policy/CommandGuard.java` | 命令黑名单 skim | 辅助防线，非主防线 |
| `tool/CodeSearchEngine.java` skim | grep 优先 ripgrep | 与 RAG 的定位分工预告 |

**并行保序（必背 trade-off）**：

- 为什么 4 而不是无限？IO 型工具可并行，但线程池 / 文件句柄 / MCP 连接有成本；4 是吞吐与资源平衡。
- 为什么 `Future.get(i)` 按 index 取？LLM 的 tool 消息顺序必须与 `tool_calls` 数组一致，否则协议错乱。

**HITL 拦截链（必画）**：

```
tool_call 请求
  → HitlToolRegistry（用户审批）
  → ToolRegistry（实际执行）
  → PathGuard / CommandGuard（策略拒绝，用户无法 override）
```

#### ③ 测试验证

```bash
mvn test -Dtest=ToolRegistryTest,ApprovalPolicyTest -DskipTests=false
```

重点看：并行执行测试、结果顺序是否与 invocations 一致。

#### ④ 自测 5 题

1. `grep_code` 和 `search_code` 分别适合什么场景？
2. 用户批准了 `write_file`，但 PathGuard 拒绝，最终会怎样？
3. `executeTools` 收到 6 个 invocation，实际并行度是多少？
4. MCP 动态工具命名规则是什么？
5. 工具返回超过 `max_chars` 时怎么处理？

**Day 2 掌握标准**：白板画 HITL 链 + 并行保序时序图。

---

### Day 3（周三）：DAG 任务编排 + 计划级 HITL

**今日目标**：DFS 拓扑排序与环检测；失败分支隔离；Plan 审阅交互。

#### ① 文档扫读（30 min）

| 文件 | 关注点 |
|------|--------|
| `README.md` Plan 相关期 | Plan-and-Execute 演进 |
| `docs/agents-reference.md` → Plan 段 | `/plan` 触发路径 |

#### ② 代码精读（2h）

| 文件 | 读哪里 | 掌握什么 |
|------|--------|----------|
| `plan/Task.java` | 全文 | id、description、dependencies、status |
| `plan/ExecutionPlan.java` | `topologicalSort()`、`visiting` 集合 | **DFS 同时做排序 + 环检测** |
| `plan/Planner.java` | `createPlan()` | LLM → JSON → ExecutionPlan |
| `agent/PlanExecuteAgent.java` | 分批执行、失败 SKIP | 依赖传播、失败分支隔离 |
| `cli/PlanReviewInputParser.java` | 全文 | Enter 执行 / Ctrl+O 展开 / ESC 取消 / I 补充重规划 |
| `cli/Main.java` | 搜索 `/plan` | 计划级 HITL 入口 |

**环检测一句话**：DFS 中节点已在 `visiting` 集 → 发现后向边 → 有环，拒绝执行。

**失败隔离一句话**：任务 B 失败 → 依赖 B 的下游 SKIP；与 B 无依赖关系的分支继续。

**白板练习（必做）**：画 DAG `A→B→D` / `A→C→E`，假设 B 失败，列出各任务最终 status。

#### ③ 测试验证

```bash
mvn test -Dtest=ExecutionPlanTest,PlanReviewInputParserTest -DskipTests=false
```

#### ④ 自测 5 题

1. `ExecutionPlan` 如何用 DFS 同时完成拓扑排序和环检测？
2. Plan 模式下，用户按 ESC 和 Enter 分别发生什么？
3. 计划级 HITL 和工具级 HITL 有什么区别？各拦截什么？
4. 为什么不直接用 ReAct 一步步做，而要 Plan + DAG？
5. Planner 输出的 JSON 结构包含哪些字段？

**Day 3 掌握标准**：白板画「B 失败时的 DAG 执行结果」+ 讲清 Plan 审阅四键交互。

---

### Day 4（周四）：Multi-Agent 协作架构

**今日目标**：Planner / Worker / Reviewer 三角色；Reviewer 反馈重试；上下文隔离与生命周期。

#### ① 文档扫读（30 min）

| 文件 | 关注点 |
|------|--------|
| `docs/agents-reference.md` → Multi-Agent | `/team` 触发、角色分工 |
| `README.md` Multi-Agent 期 skim | 与 Plan 模式的对比 |

#### ② 代码精读（2h）

| 文件 | 读哪里 | 掌握什么 |
|------|--------|----------|
| `agent/AgentRole.java` | 全文 | PLANNER / WORKER / REVIEWER |
| `agent/AgentOrchestrator.java` | `orchestrate()`、`runStep()` | 生命周期管理 |
| 同上 | `runBatchParallel()` | 同 batch 无依赖 step 并行 |
| 同上 | Reviewer 不通过分支（约 511～570 行） | `retryCount`、`MAX_RETRIES_PER_STEP = 2` |
| `agent/SubAgent.java` | `executeWithContext()`、`review()`、`clearHistory()` | **审查后 clearHistory 隔离上下文** |
| `agent/AgentMessage.java` | Type 枚举 | ERROR / 正常结果区分 |

**Reviewer 反馈状态机（Day 4 交付物，必背）**：

```
Worker 执行 step
  → Reviewer.review(description, result)
  → approved?
      是 → 写入 context，标记完成
      否 → retries < MAX_RETRIES_PER_STEP(2)?
          是 → 把 issues 拼进 feedback → Worker 重试 → 再 Review
          否 → 记录失败，按 DAG 规则影响下游
```

**Multi-Agent vs Plan（面试对比）**：

| 维度 | Plan 模式 | Multi-Agent (`/team`) |
|------|-----------|------------------------|
| 拆分 | Planner 一次出 DAG | Planner 逐步拆 step |
| 执行 | PlanExecuteAgent 按 DAG 批跑 | Worker SubAgent 逐步做 |
| 质检 | 无自动 Reviewer | Reviewer 逐步验收 + 重试 |
| 适用 | 结构化多步改造 | 需要逐步审查的复杂协作 |

#### ③ 测试验证

```bash
mvn test -Dtest=AgentOrchestratorTest,AgentRoleTest,AgentMessageTest -DskipTests=false
```

#### ④ 自测 5 题

1. Multi-Agent 里 Worker 和 Reviewer 是否共享 `conversationHistory`？如何隔离？
2. Reviewer 最多重试几次？重试时 Worker 的 prompt 多了什么？
3. 同一 batch 内无依赖 step 如何并行？
4. SubAgent 销毁时清理了哪些状态？
5. 简历写「状态机」— 更准确的说法是什么？（答：重试循环 + retryCount 上限，不是独立 State 类）

**Day 4 掌握标准**：白板画 Reviewer 重试状态机 + 讲清 `clearHistory()` 的原因。

---

### Day 5（周五）：三层记忆 + Token 预算 + 两道压缩

**今日目标**：分清 shortTermMemory 压缩 vs conversationHistory 压缩；90% 阈值与尾部 3 轮保留。

#### ① 文档扫读（40 min）

| 文件 | 关注点 |
|------|--------|
| `docs/phase-12-long-context.md`（前 100 行） | 长上下文工程背景 |
| `AGENTS.md` → Memory 约束 | 长期记忆仅 `/save`，不自动提取 |

#### ② 代码精读（2h）

| 文件 | 读哪里 | 掌握什么 |
|------|--------|----------|
| `context/ContextProfile.java` | `compressionTriggerRatio = 0.90` | `compressionTriggerTokens()` = window × 90% |
| `memory/MemoryManager.java` | 门面方法 | 短期 / 长期 / 压缩协调 |
| `memory/ConversationHistoryCompactor.java` | `compactIfNeeded()`、`DEFAULT_RETAIN_RECENT_ROUNDS = 3` | **第二道压缩**：真正发给 LLM 的历史 |
| `memory/ContextCompressor.java` skim | 压缩 shortTermMemory | **第一道压缩** |
| `memory/TokenBudget.java` | `needsCompression()` | 估算 token 是否达阈值 |
| `memory/LongTermMemory.java` skim | `/save` 持久化 | 跨会话 JSON，可审计删除 |

**三层记忆（面试版表格）**：

| 层 | 载体 | 作用 | 生命周期 |
|----|------|------|----------|
| 对话历史 | `conversationHistory` | 完整多轮 tool 协议消息 | 会话内，超 90% 触发 Compactor |
| 短期摘要 | `shortTermMemory` | 第一道压缩目标 | 会话内 |
| 长期事实 | `LongTermMemory` JSON | 跨会话稳定事实 | `/save` 写入，项目级默认 |

**触发与保留（必背）**：

1. 估算 token ≥ `ContextProfile.compressionTriggerTokens()`（窗口 × **90%**）
2. 调用 LLM 摘要旧消息
3. **保留最近 3 个 user 消息起的尾部完整不压**（分割点在 user 边界，避免切断 tool_call / tool_result 对）

**两道压缩混淆 = 面试翻车点**：

| 压缩 | 压缩对象 | 触发类 | 不混淆后果 |
|------|----------|--------|------------|
| 第一道 | `shortTermMemory` | `ContextCompressor` | 以为 history 已压，实际 window 仍爆 |
| 第二道 | `conversationHistory` | `ConversationHistoryCompactor` | 以为 shortTerm 够了，实际 LLM 请求仍超长 |

#### ③ 测试验证

```bash
mvn test -Dtest=MemoryManagerTest,ContextProfileTest -DskipTests=false
```

#### ④ 自测 5 题

1. 「3 轮」是 3 条消息还是 3 个 user 轮次？
2. 90% 阈值在哪个类定义？200k 窗口对应多少 trigger tokens？
3. 为什么长期记忆不能自动从对话里抽取？
4. `/clear` 清什么？不清什么？
5. `Agent.run()` 里哪一行触发 conversationHistory 压缩？

**Day 5 掌握标准**：不看代码讲清「两道压缩 + 三层记忆 + 90%/3 轮」。

---

### Day 6（周六）：MCP 协议集成 + Schema 裁剪 + 安全扩展

**今日目标**：JSON-RPC 2.0 客户端；stdio/HTTP 双传输；动态工具注册全链路。

#### ① 文档扫读（40 min）

| 文件 | 关注点 |
|------|--------|
| `docs/phase-10-mcp-core.md` | stdio / HTTP、配置合并、`${VAR}` |
| `docs/phase-11-mcp-advanced.md` skim | Schema 裁剪、resources |

#### ② 代码精读（2h）

| 文件 | 读哪里 | 掌握什么 |
|------|--------|----------|
| `mcp/jsonrpc/JsonRpcClient.java` | request id、pending map、超时 | 异步请求-响应匹配 |
| `mcp/transport/StdioTransport.java` | 子进程 stdin/stdout | stdio 模式 |
| `mcp/transport/StreamableHttpTransport.java` skim | HTTP 模式 | 双传输 |
| `mcp/McpClient.java` | `initialize()` → `tools/list` → `tools/call` | MCP 生命周期 |
| `mcp/protocol/McpSchemaSanitizer.java` | `sanitize()` | 裁剪 MCP schema 适配 LLM token 限制 |
| `mcp/McpServerManager.java` skim | 8s 启动超时、后台续连 | 不阻塞首屏 |
| `hitl/HitlToolRegistry.java` | MCP 工具同样走审批 | 外部工具安全 |
| `tool/ToolRegistry.java` | 搜索 `mcp__` | 动态注册命名 `mcp__{server}__{tool}` |

**MCP 四层栈（Day 6 交付物）**：

```
Agent tool_call
  → ToolRegistry（统一入口）
  → HitlToolRegistry（可选审批）
  → McpClient.callTool()
  → JsonRpcClient（JSON-RPC 2.0）
  → StdioTransport / HttpTransport
  → 外部 MCP Server 进程
```

**MCP vs REST（面试一句话）**：统一 `tools/list` 发现、标准 JSON Schema、stdio 子进程隔离；REST 每个 API 一套集成。

#### ③ 测试验证

```bash
mvn test -Dtest=McpToolRegistrationTest -DskipTests=false
```

#### ④ 自测 5 题

1. JSON-RPC 请求 ID 用来解决什么问题？
2. MCP 启动慢时 CLI 如何处理？（8s 超时 + 后台 STARTING）
3. `McpSchemaSanitizer` 不裁剪会有什么风险？
4. 用户级和项目级 MCP 配置如何合并？
5. `${STEP_API_KEY}` 从哪里解析？

**Day 6 掌握标准**：画 MCP 四层栈 + 讲清从 `tools/list` 到 Agent 可见工具的注册流程。

---

### Day 7（周日）：RAG 全链路 + 总复盘 + 模拟面试

**今日目标**：AST 分块 + SQLite 混合检索；串讲六大块；模拟面试 + 实操验收。

#### 上午（2h）— RAG 全链路

| 顺序 | 文件 | 关键点 |
|------|------|--------|
| 1 | `rag/CodeChunker.java` | JavaParser AST：类 / 方法级 chunk |
| 2 | `rag/CodeAnalyzer.java` skim | import / 依赖关系抽取 |
| 3 | `rag/CodeIndex.java` | 索引构建、`ProgressListener` |
| 4 | `rag/VectorStore.java` | SQLite 持久化、向量表 + 关键词表 |
| 5 | `rag/CodeRetriever.java` | `semanticSearch` / `keywordSearch` / `hybridSearch` |
| 6 | `tool/ToolRegistry.java` 中 `search_code` | RAG 如何暴露给 Agent |

**混合检索流程（必背）**：

```
query
  → semanticSearch(topK×2) 写入 merged map
  → tokenize query → 每个 keyword → keywordSearch
  → boostKeywordMatch 加分
  → dual match bonus → 排序 → topK
```

**定位分工（别和 grep 混淆）**：

- `glob_files` + `grep_code` + `read_file` = Claude Code 式精确探索，**首选**
- `search_code` = 语义辅助 / 模糊自然语言 / 巨型仓库跨文件检索

**测试**：

```bash
mvn test -Dtest=CodeChunkerTest,VectorStoreTest,CodeRetrieverTest -DskipTests=false
```

#### 下午（1.5h）— 15 分钟总串讲

**讲稿结构**（对着镜子 / 录音练 ≥ 2 遍）：

| 段落 | 时长 | 内容 |
|------|------|------|
| 开场 | 1 min | PaiCLI 定位、技术栈、你负责的六大块 |
| ReAct | 2 min | 状态机 + ToolRegistry + 4 并行保序 + 三路径共用 |
| DAG + HITL | 2 min | DFS 环检测、失败分支、Plan 审阅 |
| Multi-Agent | 2 min | 三角色、Reviewer 重试、clearHistory 隔离 |
| 记忆 | 2 min | 三层、90%、尾部 3 user 轮、两道压缩 |
| MCP | 2 min | JSON-RPC、双传输、Schema 裁剪、动态工具名 |
| RAG | 2 min | AST 分块、SQLite、混合检索 |
| 收尾 | 30 s | 与 Claude Code 对标、21 期交付 |

#### 傍晚（1h）— 模拟面试 + 实操

**模拟面试 10 题**（找人或自问自答，每题 2～3 min）：

1. 介绍这个项目的架构，从用户输入到工具执行。
2. ReAct 和 Plan 模式怎么选？
3. 并行工具执行如何保证正确性？
4. DAG 有环怎么办？
5. Reviewer 为什么要有重试上限？
6. 上下文快满了怎么办？两道压缩分别干什么？
7. MCP 工具和内置工具有什么区别？怎么统一调度？
8. RAG 和 grep 怎么配合？
9. 说一个你设计时的 trade-off。
10. 如果让你加 OAuth MCP，你会改哪几层？（开放题，结合 ROADMAP 答）

**实操清单**（有 API Key 时做，无 Key 则看测试）：

```bash
mvn clean package -q
java -jar target/paicli-1.0-SNAPSHOT.jar

# CLI 内
/plan 给一个小需求          # 观察 DAG + 审阅
/team 同一个需求            # 观察 Multi-Agent
/memory list
/mcp
/index                      # RAG 索引（需 Embedding 环境）
```

**总回归**：

```bash
mvn test -Pquick
```

---

## 五、按模块的文档阅读顺序（速查）

| 模块 | 必读 | 选读 |
|------|------|------|
| 全局 | `AGENTS.md` | `ROADMAP.md` |
| 实现细节 | `docs/agents-reference.md` | — |
| ReAct | `README` 第 1 期 | `AgentBudget.java` |
| Plan/DAG | `README` Plan 期 | `ExecutionPlanTest.java` |
| Multi-Agent | `agents-reference` Multi-Agent 段 | `AgentOrchestratorTest.java` |
| 记忆 | `docs/phase-12-long-context.md` | `TokenBudget.java` |
| MCP | `docs/phase-10-mcp-core.md` | `docs/phase-11-mcp-advanced.md` |
| RAG | `README` RAG 期 | `docs/code-search-golden-set.md` |

**一周内不必深读**：TUI/JLine（phase-16/22）、Chrome CDP、Runtime API、LSP——与简历六点无直接关系，面试有余力再补。

---

## 六、高频追问 & 标准答法

### ReAct / ToolRegistry

| 追问 | 答法要点 |
|------|----------|
| 和 Chain-of-Thought 区别？ | CoT 只推理；ReAct 推理 + **可执行 action** + observation 闭环 |
| 并行安全吗？ | 独立 IO 可并行；写同一文件靠 HITL + 模型约束 |
| 工具结果太大？ | ToolRegistry 内截断 / `max_chars`；RAG 走 `suggested_reads` |
| 和 LangChain 区别？ | 自研状态机 + 统一 ToolRegistry + 三路径复用 + MCP 原生，非框架拼装 |

### DAG / Plan

| 追问 | 答法要点 |
|------|----------|
| 为什么不一步步 ReAct？ | 多文件改造要全局依赖；Plan 先结构化再执行，减少漏步 |
| 环来了怎么办？ | 构建 ExecutionPlan 时 DFS 检测，直接失败 |
| HITL 几层？ | 计划级（审 Plan）+ 工具级（write/execute 审批） |

### Multi-Agent

| 追问 | 答法要点 |
|------|----------|
| 为什么 Reviewer clearHistory？ | 避免审查上下文污染下一 step |
| 和 Plan 区别？ | Plan = DAG 批执行；Team = 逐步拆 + Worker 做 + Reviewer 验 |

### 记忆

| 追问 | 答法要点 |
|------|----------|
| 3 轮是什么？ | **3 个 user 消息** 起的尾部完整保留 |
| long 模式还压缩吗？ | `ContextProfile` 策略不同，见 phase-12 |

### MCP

| 追问 | 答法要点 |
|------|----------|
| 和 REST 集成区别？ | 统一 tools/list、Schema 标准、stdio 子进程隔离 |
| 启动慢？ | 8s 超时先出首屏，后台 STARTING 续连 |

### RAG

| 追问 | 答法要点 |
|------|----------|
| 还要 grep 吗？ | 要；精确符号定位 grep 优先，RAG 补召回 |
| 为什么 AST 分块？ | 保持方法/类语义完整，固定 512 字符会切断逻辑 |
| BM25？ | 实现是 **SQLite 关键词 + 语义向量 + 融合打分**，不必强行说 BM25 |

---

## 七、常见简历陷阱（别踩）

| 简历写法 | 更准确的说法 |
|----------|----------------|
| 「从零构建 LLM」 | 「从零构建 **Agent 推理循环与工具调度**」，LLM 调商用 API |
| 「11 个工具」 | 核心 11 + Skill/浏览器/`save_memory` 等扩展 |
| 「Map-Reduce 压缩」 | 「LLM 摘要压缩 conversationHistory，保留尾部 3 user 轮」 |
| 「Reviewer 状态机」 | **重试循环 + retryCount 上限**，不是独立状态机类 |
| 「BM25 混合检索」 | SQLite 关键词匹配 + 向量语义 + 融合打分 |

---

## 八、一周结束验收清单

- [ ] 不看文档画出：ReAct 循环、MCP 栈、混合检索、Reviewer 重试、HITL 链
- [ ] 说出 11 个核心工具名及各自场景
- [ ] 30 秒内定位：`executeTools`、`topologicalSort`、`hybridSearch`、`compressionTriggerTokens`
- [ ] `mvn test -Pquick` 通过或知晓失败用例原因
- [ ] 15 分钟讲稿练过 ≥ 2 遍（建议录音回听）
- [ ] 模拟面试 10 题能流畅答 ≥ 8 题
- [ ] 每块能答 1 个「为什么这样设计」（trade-off）
- [ ] 能白板演示：DAG 失败分支 + 并行保序时序

---

## 九、时间不够时的裁剪方案

### 48 小时急救版（最低可面试）

| 优先级 | 内容 | 时间 |
|--------|------|------|
| P0 | Day 1 + Day 2 全部 | 6h |
| P0 | Day 3 DAG 上午 + Day 5 记忆上午 | 4h |
| P1 | Day 4 `runStep` 重试段 | 2h |
| P1 | Day 6 `McpClient` + `JsonRpcClient` | 2h |
| P1 | Day 7 `hybridSearch` + 串讲 1 遍 | 2h |
| 弃 | TUI、浏览器 MCP、LSP、Snapshot | — |

### 有余力加时（面试前 +1～2 天）

| 加时内容 | 价值 |
|----------|------|
| `docs/agents-reference.md` 全文精读 | 追问细节 |
| `prompt/PromptAssembler.java` | system prompt 分层设计 |
| `snapshot/SideGitManager.java` | 「改动可回退」加分项 |
| 手写简化版 `executeTools`（50 行） | 证明真懂并行保序 |

---

## 十、每日交付物汇总

| 天 | 交付物 |
|----|--------|
| Day 1 | ReAct 主循环数据流图 |
| Day 2 | HITL 拦截链 + 并行保序时序图 |
| Day 3 | DAG 失败分支白板图 |
| Day 4 | Reviewer 重试状态机 |
| Day 5 | 两道压缩对比表 + 三层记忆表 |
| Day 6 | MCP 四层栈 |
| Day 7 | 15 分钟讲稿录音 + 模拟面试笔记 |

---

## 十一、与仓库其他资料的关系

| 文件 | 说明 |
|------|------|
| [`docs/resume-4day-mastery-plan.md`](docs/resume-4day-mastery-plan.md) | 同一目标的 **4 天压缩版**，时间紧时用 |
| [`AGENTS.md`](AGENTS.md) | 仓库协作入口，代码行为 > 文档 |
| [`docs/agents-reference.md`](docs/agents-reference.md) | 实现细节参考 |

---

**最后一句**：面试不是背源码，是 **「业务问题 → 技术方案 → 关键实现 → 设计取舍」**。按本计划走满一周，你应能在 15 分钟内讲清整条链，并在追问时指到具体类与方法。
