# PaiCLI Agent A/B 质量评测 Pilot 测试报告

> 更新记录：本报告第 1～11 节保留 2026-08-10 首轮基线；第 12 节起追加同日完成的 P0 修复后复测，不覆盖或美化首轮数据。

## 1. 报告摘要

本次测试使用真实 LLM，对 PaiCLI 的三条 Agent 执行路径进行小规模 A/B/C 对照：

- A 组：ReAct 单 Agent；
- B 组：Plan-and-Execute，无质量 Reviewer；
- C 组：完整 Multi-Agent（Planner / Worker / Reviewer）。

测试采用两个隔离的 Java 修复任务，每个任务在三种模式下各执行 1 次，共 6 次 Agent 运行。Agent 执行结束后才注入隐藏 JUnit 测试，并检查实际文件变更范围，因此最终是否成功由客观代码结果决定，而不是由模型的自然语言自评决定。

首轮结果为：ReAct 2/2 通过，Plan-and-Execute 1/2 通过，Multi-Agent 0/2 通过。但日志复核显示，两次 Multi-Agent 都没有完成有效的 Worker → Reviewer 闭环：一次在 Planner JSON 解析阶段结束，另一次在首个中间步骤的 Reviewer 协议阶段失败。因此，本轮数据可以证明评测框架有效并暴露了 Multi-Agent 集成缺陷，但不能据此宣称 Multi-Agent 架构的任务质量低于单 Agent。

## 2. 测试目标

本次 Pilot 主要回答以下问题：

1. A/B 评测基础设施能否使用真实 LLM 完成隔离运行、隐藏验证和指标采集；
2. 三种执行路径能否在相同模型、任务、工具和初始文件条件下完成代码修复；
3. Multi-Agent 是否真正执行了 Planner → Worker → Reviewer → 必要时重试的完整链路；
4. 不同模式的 LLM 调用量、Token 和耗时有何差异；
5. 当前失败来自模型实现质量、编排协议、工具环境，还是评测框架本身。

本次 Pilot 不用于形成“某种 Agent 架构一定更优”的统计结论。每个用例仅运行 1 次，样本量不足，且真实模型输出存在随机性。

## 3. 测试环境

| 项目 | 配置 |
|---|---|
| 测试日期 | 2026-08-10（Asia/Shanghai） |
| Provider | GLM |
| Model | `glm-4.6v-flashx` |
| 随机种子 | `20260810` |
| 重复次数 | 每个“用例 × 模式”1次 |
| Agent运行数 | 2个用例 × 3种模式 = 6次 |
| Java | Java 17+ |
| 构建与隐藏验证 | Maven + JUnit 5 |
| 工作区 | 每次运行使用独立的 `target/agent-eval/<run-id>/workspaces/` 目录 |
| 长期记忆 | 每次运行使用独立 `.eval-memory`，不读取其他组的评测记忆 |
| 价格估算 | 未配置模型单价，因此本报告只记录 Token，不估算费用 |

整次 Maven 评测用例耗时约 235.6 秒，包含测试编排和隐藏验证的 Maven 命令总耗时约 249.9 秒。

## 4. 对照组说明

| 组别 | 执行路径 | 观察目标 |
|---|---|---|
| A | `Agent` / ReAct | 单 Agent 在同一上下文内自主读取、修改和验证 |
| B | `PlanExecuteAgent` | 观察“先规划再执行”带来的质量与成本变化，不包含质量 Reviewer |
| C | `AgentOrchestrator` | 观察 Planner / Worker / Reviewer 分工、严格审查和反馈重试效果 |

三组使用相同 Provider、Model、任务描述、可见文件和 ToolRegistry 能力。执行顺序按固定 seed 随机化，避免始终由同一模式先运行。

## 5. 测试内容

### 5.1 用例一：safe-divider

任务目标：修复 `SafeDivider.divide(int dividend, int divisor)`。

验收要求：

1. `divisor == 0` 时返回 `OptionalInt.empty()`；
2. 其他情况返回 Java 整数除法结果；
3. 只允许修改 `src/main/java/eval/SafeDivider.java`；
4. 修改后应当能够通过编译和测试。

隐藏测试覆盖：

- 正数除法；
- 负数除法；
- 除数为 0 时不抛出 `ArithmeticException`，而是返回空值。

### 5.2 用例二：ascii-slugifier

任务目标：实现 `Slugifier.slugify(String input)`。

验收要求：

1. `null` 或全空白输入返回空字符串；
2. 使用 `Locale.ROOT` 转换为小写；
3. 连续的非 ASCII 字母或数字折叠为一个连字符；
4. 删除结果首尾的连字符；
5. 只允许修改 `src/main/java/eval/Slugifier.java`；
6. 修改后应当能够通过编译和测试。

隐藏测试覆盖：

- `null`、空白和大小写；
- 多种连续分隔符；
- 数字保留；
- 首尾分隔符删除；
- 非 ASCII 字符处理。

### 5.3 客观判定规则

每次运行包含两个独立检查：

1. **变更范围检查**：除白名单文件外，新增、修改或删除其他业务文件均判失败；
2. **隐藏验证检查**：Agent 运行结束后才写入隐藏测试，并执行 `mvn test`。

两个检查都通过，任务才记为成功。模型声称“已完成”“逻辑正确”或 Reviewer 返回批准，都不能替代隐藏验证。

## 6. 测试结果

### 6.1 总体结果

| 模式 | 通过/总数 | 成功率 | 平均检查通过率 | 平均 LLM 调用 | 平均输入 Token | 平均输出 Token | 平均 Agent 耗时 |
|---|---:|---:|---:|---:|---:|---:|---:|
| ReAct 单 Agent | 2/2 | 100.00% | 100.00% | 6.50 | 26,633.00 | 1,226.50 | 24.00秒 |
| Plan-and-Execute | 1/2 | 50.00% | 75.00% | 13.50 | 53,215.00 | 3,353.00 | 51.78秒 |
| 完整 Multi-Agent | 0/2 | 0.00% | 50.00% | 2.50 | 6,568.50 | 625.50 | 6.14秒 |

所有 6 次运行的变更范围检查均通过。失败均发生在隐藏功能验证，而不是越权修改文件。

### 6.2 逐次结果

| 用例 | 模式 | 结果 | LLM调用 | Token（输入/输出） | 耗时 | 主要表现 |
|---|---|---|---:|---:|---:|---|
| safe-divider | ReAct | 通过 | 8 | 33,308 / 1,408 | 35.37秒 | 正确修改除零逻辑，隐藏测试通过 |
| safe-divider | Plan-and-Execute | 通过 | 14 | 55,236 / 3,612 | 55.20秒 | 最终实现正确，但调用量和耗时明显更高 |
| safe-divider | Multi-Agent | 失败 | 1 | 1,914 / 465 | 4.00秒 | Planner生成了计划，但计划解析失败，源码未修改 |
| ascii-slugifier | ReAct | 通过 | 5 | 19,958 / 1,045 | 12.64秒 | 实现满足全部隐藏测试 |
| ascii-slugifier | Plan-and-Execute | 失败 | 13 | 51,194 / 3,094 | 48.36秒 | 修改了源码，但正则实现错误，隐藏测试失败 |
| ascii-slugifier | Multi-Agent | 失败 | 4 | 11,223 / 786 | 8.28秒 | 首个微步骤未形成完整结果，Reviewer返回错误协议，后继全部阻塞 |

### 6.3 总资源消耗

| 模式 | 总调用 | 总输入Token | 总输出Token | 两次Agent耗时合计 |
|---|---:|---:|---:|---:|
| ReAct | 13 | 53,266 | 2,453 | 48.01秒 |
| Plan-and-Execute | 27 | 106,430 | 6,706 | 103.56秒 |
| Multi-Agent | 5 | 13,137 | 1,251 | 12.28秒 |

Plan-and-Execute 的平均调用量约为 ReAct 的 2.08 倍，平均输入 Token 约为 2.00 倍，平均耗时约为 2.16 倍。在当前两个小任务上，这些额外成本没有形成稳定质量收益。

Multi-Agent 的调用量和耗时不能解释为效率优势，因为两次运行都提前失败，没有完成预期工作。

### 6.4 Reviewer表现

本轮没有形成有效 Reviewer 混淆矩阵：

- Reviewer明确批准且形成客观结果：0次；
- Reviewer明确拒绝且形成客观结果：0次；
- Reviewer反馈后修复成功：0次；
- 质量纠正重试：0次。

原因是两次 Multi-Agent 分别在计划解析和首次审查协议阶段失败，尚未进入能够衡量 Reviewer 误放行、误拒或修复能力的有效样本阶段。

## 7. 各模式测试表现

### 7.1 ReAct

表现：两个任务全部通过，且调用量、Token 和耗时均低于 Plan-and-Execute。

本轮优势：

- 小任务无需额外规划和审查调用；
- 单一 Agent 能够连续完成读取、修改和验证，不存在跨步骤结果传递损失；
- 两个实现均通过了执行后注入的隐藏测试。

结论边界：本轮只有两个小型、单文件任务，不能推导 ReAct 在复杂、多文件或可并行任务上仍然最优。

### 7.2 Plan-and-Execute

表现：`safe-divider`通过，`ascii-slugifier`失败；平均成本约为 ReAct 的两倍。

失败实现使用：

```java
.replaceAll("[^\\p{ASCII}\\p{Alnum}]", " ")
```

由于 `\p{ASCII}` 已经包含下划线、斜杠等 ASCII 字符，这些字符不会被替换。隐藏测试实际得到：

```text
api___v2-/-guide
```

预期结果为：

```text
api-v2-guide
```

这是模型生成代码的真实逻辑错误，而不是评测基础设施误判。

### 7.3 Multi-Agent

表现：两个任务都失败，但失败发生在不同的编排阶段。

#### safe-divider

Planner输出了包含 `steps` 的JSON计划，但在代码块前附加了自然语言说明。当前解析器只删除Markdown围栏，然后对完整字符串调用 `readTree()`。删除围栏后自然语言仍然存在，导致计划解析失败。最终只产生1次LLM调用，Worker和Reviewer均未执行，源码保持初始错误状态。

#### ascii-slugifier

Planner计划成功解析，但把简单修改拆成“查找 → 读取 → 分析 → 写入 → 验证”等细粒度步骤。首个Worker只执行了文件搜索并输出“接下来读取文件”的意图，没有形成可独立验收的步骤结果。Reviewer随后没有返回规定的审批JSON，而是返回了一个模拟工具调用JSON，因此被严格协议判定为 `FAILED`，其余步骤全部转为 `BLOCKED`。

因此，当前Multi-Agent的0%成功率主要反映编排协议和任务粒度问题，不代表完整Multi-Agent链路执行后的最终代码质量。

## 8. 当前问题及原因分析

### 8.1 Planner JSON提取对真实模型输出不够鲁棒

相关实现：`AgentOrchestrator.parsePlan()`。

当前逻辑：

```java
String cleaned = planJson
        .replaceAll("```json\\s*", "")
        .replaceAll("```\\s*", "")
        .trim();
JsonNode root = mapper.readTree(cleaned);
```

问题：只处理代码围栏，不处理合法JSON前后的说明文字。真实模型即使在提示词中被要求“只输出JSON”，仍可能输出一段引导语。

影响：Planner已经生成可用计划，但Orchestrator无法消费，整个任务在规划阶段提前结束。

原因性质：已由日志直接证实。

### 8.2 Planner/Reviewer的提示词工具开关与实际工具权限不一致

`SubAgent`组装提示词时按照模型能力设置：

```java
.toolsEnabled(llmClient == null || llmClient.supportsTools())
```

实际调用时则只有Worker获得工具定义：

```java
shouldUseTools() && llmClient.supportsTools()
        ? toolRegistry.getToolDefinitions()
        : null
```

Planner和Reviewer可能在系统提示词中看到工具相关说明，却没有真实工具定义。`ascii-slugifier`的Reviewer最终返回了 `{"tool":"glob_files", ...}`，而不是 `{"approved":...}`。

影响：严格Reviewer协议正确地拒绝了非法格式，但上游提示词制造了不一致条件。

原因性质：代码与运行日志共同支持的高可信推断。真实模型也可能存在未完全遵循Reviewer模式提示词的因素，因此不能归因于单一原因。

### 8.3 Planner把Worker内部工具过程拆成了DAG步骤

当前计划出现了“搜索文件”“读取文件”“分析代码”“修改代码”等步骤。这样的步骤不是独立交付单元，却会分别进入Reviewer质量门禁。

影响：

- Worker容易只输出下一步意图，而不是可审查结果；
- Reviewer在中间过程上进行质量判定；
- 一个早期微步骤失败会阻塞真正有价值的写入和验证步骤；
- LLM调用和Token成本增加。

原因性质：已由Planner计划与Worker日志直接证实。

### 8.4 Windows执行命令路径依赖`bash`

Plan-and-Execute尝试运行 `mvn test` 时出现：

```text
execvpe(/bin/bash) failed: No such file or directory
```

当前 `ToolRegistry` 使用 `new ProcessBuilder("bash", "-c", command)`。在没有可用bash的Windows环境中，Agent无法自行完成命令验证。

影响：三个Agent模式的隐藏Oracle仍由评测器通过Windows命令执行，因此最终客观结果有效；但Agent自身缺少测试反馈，无法根据测试失败继续修正实现，削弱了评测中的工具能力公平性。

原因性质：已由代码和运行日志直接证实。

### 8.5 Plan组的Slugifier实现存在模型逻辑错误

Plan模式虽然完成写入，但正则表达式错误地把整个ASCII集合加入允许字符，导致下划线和斜杠残留。

影响：变更范围通过，但隐藏功能验证失败。

原因性质：已由最终源码和隐藏JUnit失败直接证实。

### 8.6 样本量不足

每个任务和模式只运行1次，共6次Agent运行。当前数据容易受到一次采样、模型波动和执行顺序影响。

影响：可以定位确定性协议缺陷，但不能形成稳定的架构质量排名或成功率置信结论。

## 9. 后续优化方向

### P0：先修复影响评测有效性的集成问题

1. **增强Planner计划提取**
   - 优先读取明确的 `json` fenced block；
   - 没有围栏时提取第一个完整JSON对象；
   - 提取后仍使用严格结构校验，必须存在非空 `steps` 或兼容的 `tasks` 数组；
   - 不降低Reviewer的严格JSON门禁，Planner容错和Reviewer批准是两类不同风险。

2. **让提示词工具开关与角色权限一致**
   - `toolsEnabled`应同时检查`shouldUseTools()`和`llmClient.supportsTools()`；
   - Planner和Reviewer的系统提示词不得包含工具执行说明；
   - 增加测试，断言Worker提示词包含工具说明，而Planner/Reviewer不包含。

3. **约束Planner生成可独立验收的步骤**
   - 明确禁止把搜索、读取、分析、写入拆成不同步骤；
   - 搜索和读取属于Worker完成一个交付步骤时的内部工具循环；
   - 简单单文件任务通常拆成“实现并自测”1步，最多再增加独立验证步骤；
   - 增加计划粒度评测，检查简单任务步骤数和步骤是否包含完整交付结果。

4. **修复Windows命令Shell选择**
   - Windows使用`cmd.exe /d /s /c`或经过明确设计的PowerShell路径；
   - Linux/macOS使用`bash -c`或可配置shell；
   - 补充Windows命令执行测试，确保项目工作目录、超时、取消和输出截断保持一致。

### P1：修复后进行同条件回归Pilot

保持以下条件不变：

- Model：`glm-4.6v-flashx`；
- 用例：`safe-divider`、`ascii-slugifier`；
- 重复次数：1；
- seed：`20260810`；
- 三组执行顺序仍由同一seed随机化。

回归Pilot验收标准：

1. 两个Multi-Agent任务都成功解析计划；
2. 每个Multi-Agent任务至少实际调用1次Worker和1次Reviewer；
3. Reviewer必须形成`APPROVED`或`REJECTED`有效判决，不再全部为`NOT_OBSERVED`；
4. Worker能够在Windows隔离工作区运行验证命令；
5. 报告能够产生至少两个可进入Reviewer混淆矩阵的样本；
6. 若任务仍失败，应当是隐藏测试发现的实现质量问题，而不是协议提前终止。

### P2：扩大为有意义的质量评测

完成P0/P1后再扩大：

- 每个用例重复3～5次；
- 扩展到至少20个分层任务；
- 覆盖单文件修复、多文件修改、DAG依赖、独立并行分支、错误恢复和Reviewer反馈修复；
- 统计成功率、误放行率、误拒率、重试修复率、P50/P95耗时、Token和可选费用；
- 区分简单任务和复杂任务，避免用小任务结果否定Multi-Agent在复杂任务上的潜在价值。

## 10. 结论

本次真实LLM Pilot已经证明A/B评测机制可以工作：它成功隔离了6次运行、隐藏了验收测试、检测了变更范围，并用客观JUnit结果识别了真实实现错误。

当前小样本下ReAct表现最好，Plan-and-Execute成本约为ReAct的两倍且成功率较低；但Multi-Agent的两次失败主要发生在完整协作链路之前。最重要的测试发现不是“Multi-Agent质量差”，而是当前编排器仍存在Planner输出容错、角色提示词权限一致性、任务拆分粒度和Windows命令环境四个会破坏实际执行的问题。

因此，后续应先完成P0修复，再用相同条件进行回归Pilot。只有当Multi-Agent能够稳定进入Worker/Reviewer闭环后，扩大重复次数和任务规模得到的质量、成本与时延比较才具有解释意义。

## 11. 原始证据

- 原始机器生成报告：`target/agent-eval/2026-08-10T03-19-46.774424600Z-20260810/report.md`
- safe-divider / ReAct：`target/agent-eval/2026-08-10T03-19-46.774424600Z-20260810/workspaces/safe-divider-r1-react-bc4b5e44/`
- safe-divider / Plan：`target/agent-eval/2026-08-10T03-19-46.774424600Z-20260810/workspaces/safe-divider-r1-plan_execute-4ac0261e/`
- safe-divider / Multi-Agent：`target/agent-eval/2026-08-10T03-19-46.774424600Z-20260810/workspaces/safe-divider-r1-multi_agent-afa94ab8/`
- ascii-slugifier / ReAct：`target/agent-eval/2026-08-10T03-19-46.774424600Z-20260810/workspaces/ascii-slugifier-r1-react-b4f202b0/`
- ascii-slugifier / Plan：`target/agent-eval/2026-08-10T03-19-46.774424600Z-20260810/workspaces/ascii-slugifier-r1-plan_execute-157d0552/`
- ascii-slugifier / Multi-Agent：`target/agent-eval/2026-08-10T03-19-46.774424600Z-20260810/workspaces/ascii-slugifier-r1-multi_agent-e231f672/`

`target/`属于构建产物，不应提交到Git。需要长期保留时，以本报告中的汇总、原因和路径为准，或将原始报告单独归档到外部制品存储。

---

## 12. P0 修复后回归 Pilot

### 12.1 复测目的

首轮 Pilot 暴露了四个会阻断 Multi-Agent 主链路的问题：

1. Planner 在 JSON 代码块前输出说明时，计划解析失败；
2. Planner / Reviewer 的提示词包含工具说明，但实际请求没有给它们工具；
3. Planner 把读取、分析、修改、验证机械拆成多个步骤；
4. Windows 下 `execute_command` 固定调用 `bash`，导致验证命令无法运行。

本轮在完成对应的最小 P0 修复后，使用完全相同的模型、用例、重复次数和随机顺序进行复测。目的不是证明 Multi-Agent 已经优于单 Agent，而是验证它能否进入真实的 Worker / Reviewer 链路，并观察下一层质量问题。

### 12.2 本轮已包含的最小修复

- 计划解析可以从 Markdown 代码块或前后说明中提取完整 JSON 对象；
- Planner 按可独立验收的交付物拆步，提示其不要把工具动作拆成流水账步骤；
- 只有 Worker 接收工具提示和实际 tool definitions；
- `execute_command` 在 Windows 使用 `cmd.exe`，在 Unix-like 系统使用 `bash`；
- 命令超时时同时终止 Shell 及其子进程。

确定性回归结果：

- Multi-Agent、WorkerPool、角色权限和跨平台命令相关测试：45/45 通过；
- Prompt 组装测试：4/4 通过；
- `git diff --check` 通过。

这些结果证明代码入口和状态逻辑满足预期，但不代表真实 LLM 生成质量已经达标，因此仍需本轮真实评测。

### 12.3 复测环境与命令

| 项目 | 配置 |
|---|---|
| 测试时间 | 2026-08-10（Asia/Shanghai） |
| Provider | GLM |
| Model | `glm-4.6v-flashx` |
| 用例 | `safe-divider`、`ascii-slugifier` |
| 模式 | ReAct、Plan-and-Execute、Multi-Agent |
| 重复次数 | 每个“用例 × 模式”1次 |
| 随机种子 | `20260810` |
| Agent 运行数 | 6 |
| 判定方式 | 运行后注入隐藏 JUnit 测试，并检查文件变更白名单 |
| 价格估算 | 未配置单价，只记录 Token |

执行命令：

```powershell
mvn test -Pagent-eval '-Dpaicli.eval.provider=glm' '-Dpaicli.eval.cases=safe-divider,ascii-slugifier' '-Dpaicli.eval.repetitions=1' '-Dpaicli.eval.seed=20260810'
```

Maven 评测总耗时约 360.3 秒，其中评测测试本身约 345.8 秒。Maven `BUILD SUCCESS` 只表示评测程序完整执行并生成报告，不表示六个 Agent 任务通过验收。

## 13. 复测结果

### 13.1 总览

| 模式 | 通过/总数 | 成功率 | 平均检查通过率 | 平均 LLM 调用 | 平均输入 Token | 平均输出 Token | 平均耗时 |
|---|---:|---:|---:|---:|---:|---:|---:|
| ReAct 单 Agent | 0/2 | 0.00% | 25.00% | 11.00 | 47,071.00 | 1,267.50 | 37.49s |
| Plan-and-Execute | 0/2 | 0.00% | 50.00% | 14.00 | 55,936.50 | 3,417.00 | 58.18s |
| 完整 Multi-Agent | 0/2 | 0.00% | 50.00% | 11.00 | 34,190.00 | 3,199.00 | 43.58s |

六次运行均未同时通过隐藏测试和变更范围检查，所以客观成功率为 0%。这不是理想结果，但它提供了比首轮更深入的执行证据：Multi-Agent 已经能够解析两个计划并进入 Worker / Reviewer，新的主要瓶颈转移到了步骤完成语义、执行证据和 Reviewer 判断质量。

### 13.2 Reviewer 表现

| 客观结果 | Reviewer 批准 | Reviewer 拒绝 |
|---|---:|---:|
| 隐藏验证通过 | 0 | 0 |
| 隐藏验证失败 | 1 | 0 |

- 可进入混淆矩阵的样本：1；
- 误放行：1；
- 本轮观测误放行率：100%；
- 明确拒绝后纠正执行：0次；
- Reviewer 反馈后恢复：0次。

该百分比只有一个样本，不能外推为长期真实误放行率，但足以证明 Reviewer 当前会在没有客观代码证据的情况下相信 Worker 的自然语言自述。

### 13.3 逐用例结果

| 用例 | ReAct | Plan-and-Execute | Multi-Agent |
|---|---|---|---|
| `safe-divider` | 失败：创建未授权嵌套项目，目标源码未修复，隐藏测试 0/2 | 代码逻辑和隐藏测试通过，但 `javac` 在源码目录生成未授权 `.class`，范围检查失败 | 计划解析成功；首个 Worker 只描述“将读取文件”，Reviewer 返回伪工具 JSON，协议失败；源码未修改，隐藏测试 1/2 |
| `ascii-slugifier` | 实现逻辑通过隐藏测试，但创建嵌套项目并留下源码目录 `.class`，范围检查失败 | 修改了目标文件，但正则只处理非 ASCII 字符，没有把空格、下划线、斜杠折叠为连字符，隐藏测试 1/2 | 完整执行四个步骤并获 Reviewer 批准，但实现同样错误，隐藏测试 1/2，形成误放行 |

## 14. 首轮与复测对比

### 14.1 指标变化

| 模式 | 首轮成功率 | 复测成功率 | 首轮平均调用 | 复测平均调用 | 首轮平均输入 Token | 复测平均输入 Token | 首轮平均耗时 | 复测平均耗时 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| ReAct | 100% | 0% | 6.50 | 11.00 | 26,633.00 | 47,071.00 | 24.00s | 37.49s |
| Plan-and-Execute | 50% | 0% | 13.50 | 14.00 | 53,215.00 | 55,936.50 | 51.78s | 58.18s |
| Multi-Agent | 0% | 0% | 2.50 | 11.00 | 6,568.50 | 34,190.00 | 6.14s | 43.58s |

同一 seed 只固定三种模式的运行顺序，不能固定模型采样，因此 ReAct 和 Plan 的单轮成功率波动说明两个用例、一次重复远不足以形成架构优劣结论。

Multi-Agent 的调用量、Token 和耗时明显增加，不是无效回归本身，而是因为本轮不再全部在入口提前退出：

- 首轮 `safe-divider` 在 Planner JSON 解析阶段结束；
- 复测两个 Multi-Agent 都进入 Worker；
- `ascii-slugifier` 完整执行了读取、分析、写入、验证和四次 Reviewer 审查。

因此，本轮证明了最小修复对“链路可运行性”有效，但没有证明对“最终代码质量”有效。

### 14.2 对首轮回归验收标准的核对

| 验收标准 | 结果 | 证据 |
|---|---|---|
| 两个 Multi-Agent 任务都成功解析计划 | 通过 | 两个 `run.log` 都打印了完整执行计划 |
| 每个任务至少调用一次 Worker 和 Reviewer | 通过 | `safe-divider` 进入首个 Worker/Reviewer；`ascii-slugifier` 完成四组 Worker/Reviewer |
| Reviewer 都形成有效批准或拒绝 | 未通过 | `safe-divider` Reviewer 返回 `read_file` 伪工具 JSON，被判协议无效 |
| Windows Worker 能运行验证命令 | 部分通过 | `ascii-slugifier` 成功执行 Maven 命令；`safe-divider` 在验证步骤前已阻塞 |
| 至少两个样本进入 Reviewer 混淆矩阵 | 未通过 | 只有 `ascii-slugifier` 的最终 APPROVED 进入矩阵 |
| 失败来自实现质量而非协议提前终止 | 部分通过 | `ascii-slugifier` 是实现质量失败；`safe-divider` 仍是协议提前终止 |

## 15. 当前问题及原因

### 15.1 Planner 仍然过度拆步

虽然提示词明确要求简单单文件修改通常只规划一个“实现并验证”步骤，但本轮两个计划仍被拆成四个步骤：读取、分析、写入、验证。

问题不只是步骤多，而是把“过程动作”误当成“可独立交付物”。这样会产生三个副作用：

1. Worker 容易在读取或分析步骤只返回过程性描述；
2. Reviewer 会审查无业务价值的中间文本；
3. 后续写入依赖前面每个脆弱步骤，任何协议失败都会阻塞真正修改。

结论：只依靠 Prompt 约束步骤粒度不够，需要在编排器中增加确定性的计划规范化或校验。

### 15.2 Worker 的“完成”缺少证据门槛

`safe-divider` 的 Worker 在调用 `glob_files` 后，说“我将读取文件”，但没有实际完成读取和修改。编排器仍把这段自然语言当成候选步骤结果交给 Reviewer。

当前步骤成功条件主要是“LLM 返回非空内容”，没有要求：

- FILE_READ 必须出现真实 `read_file` 结果；
- FILE_WRITE 必须出现成功写入和目标文件变更；
- COMMAND / VERIFICATION 必须附带命令、退出码和关键输出；
- 交付型步骤必须证明验收条件已经满足。

因此 Worker 可能把“准备做什么”误当成“已经做完什么”。

### 15.3 Reviewer 独立了上下文，但没有独立证据

独立 Reviewer 上下文解决的是并发历史污染，并不自动提高审查质量。本轮出现两种失败：

- `safe-divider` Reviewer 认为自己需要调用 `read_file`，输出了伪工具 JSON，而不是审批协议；
- `ascii-slugifier` Reviewer 明确意识到 Worker 没有提供具体测试输出，却仍根据“BUILD SUCCESS”的自述批准。

更严重的是，`ascii-slugifier` 的可见 Maven 项目当时没有相关测试，因此 `BUILD SUCCESS` 只能证明编译或空测试集成功，不能证明验收规则正确。隐藏测试随后发现两个断言失败。

根因是 Reviewer 只看到 Worker 摘要，没有得到可信的文件 diff、工具调用结果、测试数量、退出码和验收规则逐项映射。它审查的是“说法是否合理”，不是“代码是否正确”。

### 15.4 验证命令与变更范围约束没有统一

Plan 的 `safe-divider` 实现实际通过隐藏测试，但 Worker 使用：

```text
javac src/main/java/eval/SafeDivider.java
```

这会把 `SafeDivider.class` 写进源码目录，触发未授权变更。ReAct 组还调用 `create_project` 创建了嵌套项目，违反“只允许修改指定文件”的任务约束。

说明 Agent 知道要验证，却没有选择与工作区约束兼容的验证方式。应优先使用项目构建命令，让产物进入已忽略的 `target/`，并在执行前理解“只能修改哪些文件”。

### 15.5 单轮小样本具有明显随机性

首轮 ReAct 为 2/2，本轮为 0/2；本轮 ReAct 的两个源码实现中，一个未修改目标文件，另一个实现逻辑正确但越界创建文件。这种波动说明当前结果高度依赖模型当次工具选择。

因此不能用本轮 0/2 宣称所有模式都无效，也不能用首轮 2/2 宣称 ReAct 稳定优于其他模式。当前两轮更适合作为故障发现 Pilot，而不是产品质量排名。

## 16. 后续优化方向

### P0：让步骤完成和审查建立在证据上

1. **计划规范化**：解析计划后合并连续的 FILE_READ / ANALYSIS / FILE_WRITE / VERIFICATION 流水账步骤；简单单文件任务默认形成一个“实现并验证”交付步骤。
2. **Worker 完成契约**：记录每步工具调用和结果，按步骤类型要求最低证据；只有满足证据门槛才进入 Reviewer。
3. **Reviewer Evidence Bundle**：向 Reviewer 提供原始需求、步骤目标、实际 diff、修改文件列表、命令及 exit code、测试数量和关键输出，而不是只提供 Worker 自述。
4. **Reviewer 保守规则**：没有代码/命令证据时必须 `approved=false`；“准备执行”“将要修改”“BUILD SUCCESS”但测试数为0都不能批准。
5. **约束感知验证**：把允许修改文件列表加入 Worker 提示和工具执行上下文，推荐 Maven/Gradle 项目命令，禁止在源码目录直接生成 `.class`。

### P1：提高纠错能力和可观测性

1. 在最终汇总和评测结果中分别记录计划解析、Worker 执行、Reviewer 协议、Reviewer 质量、隐藏验证和范围检查失败；
2. 将 Reviewer 的批准对象从自然语言结果升级为结构化 `StepEvidence`；
3. 对 Reviewer 非法输出允许一次“只修复协议格式”的重试，不重复 Worker 业务执行；
4. 给 Planner 增加最大步骤数和交付物校验，拒绝明显的工具动作型计划；
5. 在评测报告中记录实际 tool calls、写入文件和验证命令，自动区分“未执行”“执行错误”“实现错误”“越界修改”。

### P2：形成可用于架构比较的数据

1. 先新增 5～8 个覆盖单文件修复、边界条件和约束遵循的用例；
2. P0 稳定后扩展到至少20个分层任务；
3. 每个用例每种模式重复3～5次；
4. 报告成功率置信区间、P50/P95耗时、Token、范围违规率、Reviewer误放行/误拒和纠正恢复率；
5. 简单任务与复杂、多文件、可并行任务分层统计，避免平均值掩盖 Multi-Agent 的适用边界。

## 17. 复测结论

P0 最小修复达成了一个明确目标：Multi-Agent 不再全部死在计划入口，两个任务都成功进入 Worker / Reviewer，其中一个完整跑完协作链路和 Windows 命令验证。

但它没有改善本轮客观成功率。新的主要问题已经从“系统接线错误”转变为“步骤完成没有证据、Reviewer 审查自然语言而不是代码事实”。`ascii-slugifier` 的误放行是当前最重要的质量风险；`safe-divider` 则说明 Planner 过度拆步和 Worker 只输出行动意图仍会让链路提前终止。

所以当前最准确的项目判断是：

> Multi-Agent MVP 的基础编排、池化、严格状态和跨平台执行已经可运行；真实代码质量闭环尚未完善，下一阶段应优先建立 Worker Evidence → Reviewer Verification，而不是继续增加 Agent 数量或复杂角色。

面试中可以把这次结果描述为一次有效的迭代验证：先用最小改动消除入口阻断，再通过同条件真实评测把问题推进到更深一层，并据此调整下一阶段优先级。不能把它描述成“Multi-Agent 已经优于单 Agent”。

## 18. 复测原始证据

- 原始机器报告：`target/agent-eval/2026-08-10T12-36-03.456259800Z-20260810/report.md`
- safe-divider / ReAct：`target/agent-eval/2026-08-10T12-36-03.456259800Z-20260810/workspaces/safe-divider-r1-react-15751a0f/`
- safe-divider / Plan：`target/agent-eval/2026-08-10T12-36-03.456259800Z-20260810/workspaces/safe-divider-r1-plan_execute-89179eae/`
- safe-divider / Multi-Agent：`target/agent-eval/2026-08-10T12-36-03.456259800Z-20260810/workspaces/safe-divider-r1-multi_agent-77c564fa/`
- ascii-slugifier / ReAct：`target/agent-eval/2026-08-10T12-36-03.456259800Z-20260810/workspaces/ascii-slugifier-r1-react-f4a33369/`
- ascii-slugifier / Plan：`target/agent-eval/2026-08-10T12-36-03.456259800Z-20260810/workspaces/ascii-slugifier-r1-plan_execute-696eb20c/`
- ascii-slugifier / Multi-Agent：`target/agent-eval/2026-08-10T12-36-03.456259800Z-20260810/workspaces/ascii-slugifier-r1-multi_agent-6bc86e0a/`

复测原始产物同样位于 `target/`，不会提交到 Git；长期结论以本报告的追加章节为准。

## 19. 第三轮真实 LLM 评测：Evidence-based Review P0

### 19.1 评测目的

第三轮在前两轮相同的两个隐藏用例上验证 Evidence-based Review P0，重点不是观察编排链路能否启动，而是回答：

1. Worker 没有真实执行工具时，证据门槛能否阻止其进入 Reviewer；
2. Reviewer 获得工具调用证据后，能否降低误放行；
3. 新证据链对 LLM 调用、Token 和耗时有什么影响；
4. 当前质量瓶颈是否已经从“缺少证据”转移到新的层次。

评测配置：

| 项目 | 值 |
|---|---|
| Provider / Model | `glm / glm-4.6v-flashx` |
| 用例 | `safe-divider`、`ascii-slugifier` |
| 模式 | ReAct、Plan-and-Execute、完整 Multi-Agent |
| 每种组合重复次数 | 1 |
| 随机种子 | `20260810` |
| 判定方式 | Agent 退出后注入隐藏测试，并检查未授权文件变更 |
| 原始报告 | `target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/report.md` |

执行时外层命令观察器在 120 秒达到超时，但 Maven/Surefire 的 Java 子进程没有被终止，继续完成了全部六组任务并生成报告。最终 `AgentQualityEvaluationTest` 自身为 1/1 通过，耗时 217.8 秒；这里的“测试类通过”表示评测框架完整运行，不表示六个 Agent 样本通过隐藏验收。

## 20. 第三轮测试结果

### 20.1 总体结果

| 模式 | 通过/总数 | 成功率 | 平均检查通过率 | 平均 LLM 调用 | 平均输入 Token | 平均输出 Token | 平均耗时 |
|---|---:|---:|---:|---:|---:|---:|---:|
| ReAct 单 Agent | 0/2 | 0.00% | 50.00% | 4.00 | 15,968.50 | 755.50 | 8.08s |
| Plan-and-Execute | 0/2 | 0.00% | 50.00% | 8.50 | 32,726.50 | 2,212.00 | 32.72s |
| 完整 Multi-Agent | 0/2 | 0.00% | 50.00% | 13.00 | 46,451.00 | 3,206.00 | 37.37s |

六个样本合计 51 次 LLM 调用，输入 Token 190,292，输出 Token 12,347，无缓存 Token。六个工作区的修改范围检查都通过，但所有实现均未通过隐藏功能测试，所以每个模式仍为 0/2。

### 20.2 Reviewer 表现

| 客观结果 | Reviewer 批准 | Reviewer 拒绝 |
|---|---:|---:|
| 隐藏验证通过 | 0 | 0 |
| 隐藏验证失败 | 1 | 0 |

- Reviewer 误放行率：100%；
- 误拒率：无法计算；
- 触发业务纠正执行：0 次；
- Reviewer 反馈后恢复：0 次；
- `safe-divider` 在证据门槛处 `FAILED`，没有进入 Reviewer，因此不进入混淆矩阵；
- `ascii-slugifier` 被 Reviewer 批准，但隐藏测试 0/2，构成一次误放行。

### 20.3 分用例结果

| 用例 | ReAct | Plan-and-Execute | Multi-Agent |
|---|---|---|---|
| `safe-divider` | 目标文件未正确修复；隐藏测试 1/2 | 仍执行原始除法；零除抛异常，隐藏测试 1/2 | Planner 拆成 5 步；首个 FILE_READ 步骤连续三次没有产生 `read_file` 证据，被证据门槛终止；Reviewer 未调用，隐藏测试 1/2 |
| `ascii-slugifier` | 基本保持错误行为，隐藏测试 0/2 | 修改不符合规则，空格、下划线和斜杠没有折叠，隐藏测试 0/2 | 完成读取、写入、分析和命令步骤并获 Reviewer 批准，但正则实现错误，隐藏测试 0/2，形成误放行 |

## 21. Evidence-based Review 实际表现

### 21.1 有效之处：无证据结果不再进入 Reviewer

`safe-divider` 的 Worker 首次只调用了 `glob_files`，随后三次声称“我将调用 readfile”，却始终没有产生真实 `read_file` tool call。新的证据门槛每次都给出：

```text
步骤要求读取文件，但没有成功的 read_file 证据
```

最终步骤标记为 `FAILED`，下游四步全部 `BLOCKED`，Reviewer 没有机会根据自然语言意图错误批准。与上一轮“把未执行结果送进 Reviewer，随后发生协议错误”相比，本轮失败位置更准确，也符合 fail-closed 原则。

但这只是防止错误状态进入下一阶段，并没有完成用户任务。证据门槛能识别“没做”，不能迫使模型“去做”。该用例为获得同一个缺失证据结论消耗了 5 次 LLM 调用。

### 21.2 无效之处：工具证据不能证明代码语义正确

`ascii-slugifier` 的 Worker 确实产生了 `read_file`、`write_file` 和 `execute_command` 证据，但写入的核心正则为：

```java
String slug = lowerCase.replaceAll("[^\\p{ASCII}\\p{Alnum}]", "-");
```

它存在两个直接问题：

1. 字符类包含 `\p{ASCII}`，导致空格、下划线、斜杠等 ASCII 非字母数字字符不会被匹配替换；
2. 表达式没有 `+`，即便匹配到连续非法字符，也不能一次折叠为一个连字符。

隐藏测试因此得到：

```text
expected: <hello-world> but was: <  hello world  >
expected: <api-v2-guide> but was: <api___v2 / guide>
```

Reviewer 虽然看到了写入证据，却错误地判断该正则满足“连续非法字符折叠”要求。说明 `StepEvidence` 证明的是“工具调用发生过”，不是“代码语义正确”。

### 21.3 步骤类型由 Planner 决定，存在证据策略绕过

`ascii-slugifier` 的第三步描述是“验证修改后的代码是否满足所有验收规则”，但 Planner 将它标记为 `ANALYSIS`。当前策略允许 `ANALYSIS` 纯文本完成，因此 Worker 只读取源码并进行自然语言推断，没有执行任何边界用例或测试。

这表明仅按 `step.type` 选择证据门槛不够可靠：Planner 的类型输出本身也是 LLM 结果。步骤描述具有验证语义时，应由代码级 PlanValidator 纠正为 `VERIFICATION`，或者直接要求可执行验证证据。

### 21.4 exit code 0 仍可能是空验证

最终 COMMAND 步骤执行了 `mvn test`，结果为 `BUILD SUCCESS`，但同时明确显示 `No tests to run`。当前最低证据策略只检查 `execute_command` 的 exit code 0，因此证据门槛通过。

Reviewer prompt 已写明“只有 BUILD SUCCESS 但没有测试数量，不能证明业务验收规则全部通过”，但本次 Reviewer 仍然批准。原因有两层：

1. 测试数量和构建状态仍藏在非结构化文本中，依赖 Reviewer 自己理解；
2. Reviewer 审查的是当前步骤描述“运行测试或编译验证”，而不是始终对照最初完整用户需求进行最终验收。

因此 exit code 0 只能证明命令成功结束，不能证明测试真的覆盖了业务条件。

## 22. 第二轮与第三轮对比

| 模式 | 第二轮成功率 | 第三轮成功率 | 第二轮平均调用 | 第三轮平均调用 | 第二轮平均输入 Token | 第三轮平均输入 Token | 第二轮平均耗时 | 第三轮平均耗时 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| ReAct | 0% | 0% | 11.00 | 4.00 | 47,071.00 | 15,968.50 | 37.49s | 8.08s |
| Plan-and-Execute | 0% | 0% | 14.00 | 8.50 | 55,936.50 | 32,726.50 | 58.18s | 32.72s |
| Multi-Agent | 0% | 0% | 11.00 | 13.00 | 34,190.00 | 46,451.00 | 43.58s | 37.37s |

第三轮 Multi-Agent 的平均调用从 11 增至 13、平均输入 Token 增加约 35.9%，但成功率仍为 0%。这部分新增成本主要来自证据不足重试和更完整的证据上下文。

同时，平均耗时从 43.58 秒降至 37.37 秒，不能解释为性能优化：`safe-divider` 在第一步快速失败，拉低了平均值；`ascii-slugifier` 单次仍耗时 65.42 秒、调用 21 次、输入 76,171 Token。

因此第三轮的客观改善不是成功率或效率，而是把一类“无实际操作的候选结果”更早、更准确地判为失败。Reviewer 误放行率仍为 100%，质量收益尚未建立。

## 23. 第三轮暴露的根因

### 23.1 证据是动作事实，不是验收事实

当前证据能回答“调用了什么工具、参数和输出是什么”，不能直接回答“每条验收条件是否成立”。`write_file` 成功只证明文件被写入，不能证明算法正确；`mvn test` exit code 0 只证明命令成功，不能证明存在有效测试。

### 23.2 Reviewer 仍然依赖模型推理

把真实证据交给 Reviewer 消除了纯自述问题，但 Reviewer 仍需自行理解 Java 正则、构建日志和业务边界。当前模型在明确看到错误实现时仍给出错误推理，因此证据质量提升没有自动转化为判定质量提升。

### 23.3 缺少完整任务级最终验收

Orchestrator 当前逐步调用 Reviewer，传入的是 `step.description()`。对于“读取文件”“运行测试或编译”等局部步骤，Reviewer 容易只判断局部动作完成，而没有在 DAG 结束前重新对照最初用户任务和全部证据进行一次整体判定。

### 23.4 Planner 仍制造过多脆弱边界

`safe-divider` 被拆为 5 步，真正的代码修复依赖一个没有业务交付价值的 FILE_READ 步骤。证据门槛让失败更准确，却也让过度拆步的每个中间动作成为新的阻塞点。

### 23.5 当前模型的工具遵循和代码推理能力不稳定

同一 Worker 收到两次明确的 `read_file` 缺失反馈后仍只输出未来时态文本；Reviewer 又误读简单正则。ReAct 和 Plan 同样为 0/2，说明问题不只属于 Multi-Agent 编排，也与本次模型采样和模型能力有关。

## 24. 后续优化方向

### P0：补齐“验收事实”

1. **传递根任务**：Reviewer 输入同时包含完整用户任务、当前步骤和前序关键证据，避免把局部动作完成误当成整体任务完成。
2. **最终整体 Reviewer**：所有 DAG 步骤完成后，再基于完整 diff、命令和验收规则做一次任务级审批；未通过时只重开相关交付步骤。
3. **PlanValidator / 规范化**：合并单文件任务中的读取、分析、写入、验证流水账；根据步骤描述纠正错误类型，含“验证、测试、编译”的步骤不能标为纯 `ANALYSIS`。
4. **结构化命令结果**：ToolExecutionResult 增加 success、exitCode、测试数量和 `No tests to run` 标识，不再让策略和 Reviewer解析自然语言日志。
5. **验收规则映射**：要求 Reviewer 对每条用户验收条件输出 `criterion / evidence / pass`，任何条件缺证据都不得批准。

### P1：提高纠错而不是重复同一行为

1. Worker 连续缺少同一种工具证据时，第二次重试应清理失败轨迹或切换 Worker，而不是在同一会话里重复“我将调用”；
2. 对简单读取步骤可考虑由 Orchestrator 确定性执行，或把读取合并到真正的交付步骤；
3. Reviewer 拒绝实现语义时，反馈应定位到具体验收条件和代码证据；
4. 为 Reviewer 单独配置更擅长代码审查的模型，评测时同时记录模型差异，避免把角色数量和模型能力混在一起。

### P2：扩大评测后再下架构结论

本轮仍只有两个用例、每种模式一次采样。下一阶段至少应让每个用例重复 3 次，并增加“有可见测试”“无可见测试”“多文件约束”“可并行子任务”等分层用例，再比较成功率、误放行率、Token 和 P50/P95 耗时。

## 25. 第三轮结论

Evidence-based Review P0 达成了一个有限但真实的目标：Worker 没有最低工具证据时，系统不再相信其自然语言结果，也不会调用 Reviewer。这提高了失败关闭的准确性。

但本轮所有模式仍为 0/2，Multi-Agent Reviewer 误放行率仍为 100%。`ascii-slugifier` 证明了当前最关键的差距：

> 系统已经能证明 Worker “做过什么”，还不能证明 Worker “做对了什么”。

因此目前不能宣称 Evidence-based Review 提高了最终任务质量，更不能宣称 Multi-Agent 优于单 Agent。下一轮重点应从“收集工具日志”升级为“完整任务级验收事实”：根任务透传、计划规范化、结构化测试结果和最终整体审查。

## 26. 第三轮原始证据

- 原始机器报告：`target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/report.md`
- safe-divider / ReAct：`target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/workspaces/safe-divider-r1-react-053ef06f/`
- safe-divider / Plan：`target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/workspaces/safe-divider-r1-plan_execute-3ca465f2/`
- safe-divider / Multi-Agent：`target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/workspaces/safe-divider-r1-multi_agent-300098e1/`
- ascii-slugifier / ReAct：`target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/workspaces/ascii-slugifier-r1-react-c8c3ad64/`
- ascii-slugifier / Plan：`target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/workspaces/ascii-slugifier-r1-plan_execute-43d16610/`
- ascii-slugifier / Multi-Agent：`target/agent-eval/2026-08-10T14-38-19.920704800Z-20260810/workspaces/ascii-slugifier-r1-multi_agent-dddf7b72/`

原始产物位于 `target/`，不提交 Git；第三轮长期结论以本报告第 19～26 节为准。

## 27. 评测后的工程决策

三轮实验均未证明 Multi-Agent 最终成功率提升，第三轮仍为 0/2 且 Reviewer 误放行率为 100%。因此没有继续在主运行链路叠加修改，而是将 WorkerPool、严格状态扩展、Evidence-based Review、跨平台命令等三轮实验运行时代码恢复到 Git HEAD 基线。

保留内容包括：

- 本报告和三轮修改记录；
- `agent-eval` Profile 与隐藏测试评测代码；
- `target/agent-eval` 下三轮可审计原始产物；
- 回退前的 tracked patch 和新增类归档。

这次回退不否定实验价值，但明确区分“得到诊断证据”和“产品质量获得提升”。在没有新的、可提前验收的成功率门槛之前，不再把这些实验性编排改动视为当前已交付能力。
