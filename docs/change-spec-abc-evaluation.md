# ChangeSpec V1 A/B/C 评测协议

> 状态：评测框架、修复后的代表性小样和 GLM 同模型 36 次真实 LLM Pilot 均已完成；DeepSeek 代表性小样 A/B/C 均为 100%，出现成功率天花板。新报告使用四态分维度结论，不再把天花板、零基线、没有修复机会或未测人工时间解释成“没有价值”。
> 运行入口：`mvn test -Pchange-spec-eval`（会产生网络请求和 Token 费用）。

## 1. 要回答的问题

本评测分别回答三个产品问题：

1. A→B：ChangeSpec 契约和确定性 Evidence Gate 是否比普通 ReAct 更可靠；
2. B→C：在同一份锁定 ChangeSpec 下，一次 Evidence 驱动修复是否带来可测的条件增益；
3. A→C：完整 ChangeSpec 产品路径相对普通 ReAct 的综合质量、时间和成本变化。

它不比较 Plan-and-Execute 或 Multi-Agent；现有 `agent-eval` Profile 继续负责三条 Agent 架构的质量实验。

## 2. 三组定义

| 组 | 输入与机制 | 公开接受信号 |
|---|---|---|
| A | 原始自然语言任务 + 普通 ReAct | ReAct 正常完成 |
| B | 配对 ChangeSpec + ReAct + 公开 Verifier + Criterion/Verdict，关闭自动修复 | `Verdict=PASSED` |
| C | 与 B 相同的配对 ChangeSpec，允许最多一次 Evidence 驱动修复 | `Verdict=PASSED` |

每个“任务 × 重复轮次”只生成一次 Draft。B/C 使用同一个 `ChangeSpecDocument`，锁定后 digest 必须一致。Draft 的耗时和 Token 作为相同的产品开销分别计入 B/C；真实评测账单只发生一次 Draft 调用，报告会明确提醒不能把逐行产品成本直接相加为 API 账单。

生产默认行为保持允许一次修复。B 通过 `SpecRunCoordinator.RunOptions` 关闭修复，不复制或改写生产验收逻辑。

## 3. 任务集

默认任务集包含 12 个隔离 Java 17 Maven fixture，每个层级 4 个：

| 层级 | 任务 | 主要风险 |
|---|---|---|
| 小型 | `safe-divider` | 边界条件与既有语义 |
| 小型 | `ascii-slugifier` | 字符分类、Locale 与折叠规则 |
| 小型 | `email-canonicalizer` | 格式校验、规范化与显式非目标 |
| 小型 | `inclusive-clamp` | 包含式边界与非法区间 |
| 中型 | `login-retry-policy` | 多分支重试次数和异常传播 |
| 中型 | `timeout-config-compat` | 新旧配置兼容、优先级和输入校验 |
| 中型 | `feature-flag-precedence` | 多来源兼容优先级与严格布尔解析 |
| 中型 | `order-state-machine` | 状态转换矩阵与非法事件 |
| 高风险 | `workspace-path-safety` | 路径逃逸和安全边界 |
| 高风险 | `operation-result-api-compat` | 公共 API 源兼容和新增语义 |
| 高风险 | `secret-redactor` | 多种凭证语法与日志脱敏边界 |
| 高风险 | `token-expiry-cross-file` | 跨文件 API、时钟偏移和溢出边界 |

每个任务固定包含：

- 原始任务描述和模型可见源码/测试；
- `bounded` Scope 所需的允许修改文件集合；
- 唯一允许的公开命令 `mvn -q -DskipTests=false test`；
- 模型不可见的隐藏 JUnit Oracle；
- 两分钟隐藏验证命令超时；
- 独立 workspace、长期记忆和运行产物。
- 一个基于参考实现的确定性单点错误突变；公开 Verifier 必须拒绝该突变。

公开测试用于给 Agent 和 Evidence 修复提供可行动的失败证据；隐藏测试覆盖额外边界，只在候选完成后写入首次/最终候选的副本或 workspace。Agent 永远看不到隐藏测试内容和输出。

## 4. 自动交互策略

- 第一个同时通过结构和语义资格检查的配对 Draft 自动确认；结构或语义资格失败都进入 Draft Generator 最多两次生成的同一纠错链路；语义资格要求 command、JUnit report glob 和任务最低公开测试数满足该 fixture 的证据契约，且每条非 scope deterministic Criterion 都引用至少一个合格 command；
- 公开 Verifier 只有命令完全等于任务预声明命令时才执行，其他命令记为 `HITL_DENIED`；
- fixture 位于隔离 workspace，Agent 继续受 PathGuard 和 CommandGuard 约束；
- 自动评测不替代 Human Criterion：若 Draft 生成 Human Criterion，评测器选择 `SKIPPED`，最终通常为 `NEEDS_HUMAN`；
- 因为没有真实用户，本 Pilot 的 `total_human_effort` 固定报告为 `NOT_MEASURED`，不能写成 0；自动确认也不能冒充真人投入。

## 5. 指标定义

### `task_success_rate`

最终 workspace 同时满足：

1. 隐藏 Oracle 命令通过；
2. 实际 changed files 没有超出任务允许范围。

### `first_pass_success_rate`

初始 ReAct 和第一次公开 VerificationAttempt 完成后、任何 Evidence 修复开始前复制候选 workspace，并在副本中注入隐藏测试。该候选同时通过隐藏 Oracle 和 Scope 才算首次成功。

A/B 没有自动修复，因此首次候选通常就是最终候选；C 必须使用 Coordinator observer 捕获真实修复前快照，不能用最终结果反推。

### `acceptance_pass_rate`

- B/C：`Verdict=PASSED` 的运行数 / B/C 总运行数；
- A：没有结构化 Verdict，报告为 `N/A`。

### `false_completion_rate`

- A 的完成信号是 ReAct 正常结束；
- B/C 的完成信号是 `Verdict=PASSED`；
- 完成信号存在但最终隐藏 Oracle/Scope 未通过，记为一次虚假完成；
- 分母是形成该组完成信号的运行数。分母为 0 时报告 `N/A`。

为了避免严格系统通过“从不声明完成”获得好看的虚假完成率，报告还必须同时给出：

- `completion_claim_rate`：形成完成信号的运行数 / 全部运行数；
- `false_completion_all_run_rate`：虚假完成数 / 全部运行数。

A 的“ReAct 正常结束”和 B/C 的 `Verdict=PASSED` 是不同强度的产品信号，报告必须显式说明，不能把两者当成完全同质的分类器输出。

### `scope_violation_rate`

最终 changed files 出现任何不在 `allowedChangedFiles` 中的业务文件，即为一次越界。`target`、`.paicli`、评测日志和记忆目录不作为业务变化。

### 时间指标

- `time_to_objectively_correct_candidate`：成功运行记录 `产品运行总耗时 + 最终隐藏 Oracle 耗时`；只回答多久得到客观正确候选。
- `time_to_trusted_product_decision`：B/C 客观成功且产品 `Verdict=PASSED` 时记录产品运行总耗时；A 没有等价结构化可信决定，报告 `N/A`。
- `observed_failure_time`：失败运行记录实际 `产品运行总耗时 + 最终隐藏 Oracle 耗时`。
- `penalized_tta`：失败运行替换为统一 `censorMinutes` 固定值，用于与历史工程评分对照；该值不是实际失败耗时，也不是严格统计删失时间。

报告分开展示产品耗时、客观正确候选 TTA、可信产品决策 TTA、失败实际耗时、惩罚 TTA、失败数，以及 Draft、ReAct、ReAct LLM 请求、ReAct 工具批次、公开 Verifier 和隐藏 Oracle P50。B/C 的产品运行总耗时包含配对 Draft 生成耗时；Draft 耗时仍可由单次运行指标单独审计。LLM 请求墙钟包含失败请求，并仍合并服务端推理、网络传输和流式接收；工具列按 Agent 实际等待的批次墙钟累计，并行工具不相加，公开 Verifier 不计入该列。

默认失败惩罚值为 10 分钟；这是历史工程评分口径，不是执行超时。为防止付费评测被异常 Agent 长尾支配，自动评测默认给每个 ReAct 阶段 15 次迭代和 250,000 Token 的独立安全预算，并检测重复单步/两步工具周期；这些限制不改变生产 CLI 的默认预算。

### 修复机会与条件增益

- `repair_eligible_count`：C 中实际满足生产修复触发条件并启动修复的运行数；
- `repair_attempt_rate`：已启动修复数 / 修复机会数；当前生产策略自动启动，因此正常情况下为 100%；
- `conditional_repair_success_rate`：首次失败、修复后最终客观成功的运行数 / 修复机会数。

若 `repair_eligible_count=0`，修复价值状态为 `NOT_EVALUABLE_NO_OPPORTUNITY`，不能解释为修复没有价值。

### Token 与成本

记录 Draft + ReAct 的 calls、input/output/cached tokens。成本只有显式提供每百万 Token 单价时才估算，报告不内置可能变化的模型价格；`costCurrency` 只控制报告币种标签，不执行汇率换算。报告同时给出平均产品成本和 `总产品成本 / 客观成功数` 的单位成功成本；不同 provider 的 Token 口径和缓存统计可能不同，跨模型比较必须同时审阅调用数和墙钟。

## 6. 公平性与随机性

- 同一任务的三组从相同 visible fixture 独立物化；
- 同一 provider/model、工具实现和原始任务；
- B/C 共用锁定 Spec document/digest；
- 模式顺序按固定 seed 随机化；
- 每个“任务 × 模式 × 重复轮次”使用独立 workspace 和记忆；
- 当前客户端不能统一设置所有 provider 的采样 seed，真实模型输出不能完全复现；目录默认每组重复两次，正式统计研究仍要求至少三次；
- 12 任务目录达到 RFC 的任务数量下限，但尚未运行新版付费基线，也未完成专门的歧义澄清和真人总人时实验。
- 成功率使用 95% Wilson 区间；A→B、B→C、A→C 按相同任务与重复轮次报告候选胜/负/平。当前只形成描述性配对统计，不用小样本点估计冒充显著性结论。
- 现有 12 个 fixture 已覆盖显式非目标、跨文件约束、兼容性决策、安全边界、状态转换和确定性错误突变，但需求、Scope 和命令仍较明确，不足以单独测量需求澄清价值。

## 7. 运行

付费运行前可显式复验 12 个参考实现都能通过各自的公开测试和隐藏 Oracle，并逐一验证确定性单点突变会被生产 `ToolRegistry.executeCommandForVerification` 路径拒绝，防止弱公开测试或平台 Shell 差异污染 B/C；这些较慢检查在默认回归中跳过：

```bash
mvn test -Dtest=ChangeSpecEvaluationInfrastructureTest \
  '-Dpaicli.changeSpecEval.validateFixtures=true' \
  -DskipTests=false
```

默认运行 12 个任务、三组、每组两次，共 72 次产品运行；另有每个“任务 × 重复轮次”一次配对 Draft 调用。该命令会产生费用，必须在用户单独批准后执行：

```bash
mvn test -Pchange-spec-eval
```

常用参数：

```bash
# 指定 provider
mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.provider=deepseek'

# 仅在本次评测 JVM 内覆盖模型，不修改持久配置
mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.provider=glm' '-Dpaicli.changeSpecEval.model=glm-4.6v-flashx'

# 只跑一个任务、一次重复，用于付费运行前 smoke
mvn test -Pchange-spec-eval \
  '-Dpaicli.changeSpecEval.cases=safe-divider' \
  '-Dpaicli.changeSpecEval.repetitions=1'

# 固定顺序并配置人民币单价
mvn test -Pchange-spec-eval \
  '-Dpaicli.changeSpecEval.seed=20260820' \
  '-Dpaicli.changeSpecEval.inputCostPerMillion=0.15' \
  '-Dpaicli.changeSpecEval.outputCostPerMillion=1.50' \
  '-Dpaicli.changeSpecEval.costCurrency=CNY'
```

配置项：

| 属性 | 默认值 | 说明 |
|---|---:|---|
| `paicli.changeSpecEval.provider` | PaiCLI 默认 provider | 指定本次使用的 provider |
| `paicli.changeSpecEval.model` | provider 当前配置 | 只在评测 JVM 内覆盖模型 ID |
| `paicli.changeSpecEval.repetitions` | `2` | 每任务/组重复次数，1～20 |
| `paicli.changeSpecEval.seed` | `20260820` | 三组运行顺序 seed |
| `paicli.changeSpecEval.cases` | 全部 12 个 | 逗号分隔任务 ID |
| `paicli.changeSpecEval.censorMinutes` | `10` | 失败惩罚 TTA 固定分钟数，1～60；不是实际超时或统计删失时间 |
| `paicli.changeSpecEval.inputCostPerMillion` | `0` | 输入 Token 单价；0 表示不估价 |
| `paicli.changeSpecEval.outputCostPerMillion` | `0` | 输出 Token 单价；0 表示不估价 |
| `paicli.changeSpecEval.costCurrency` | `USD` | 三字母报告币种标签，不换算汇率 |
| `paicli.changeSpecEval.reactTokenBudget` | `250000` | 每个 ReAct 阶段的评测 Token 安全预算 |
| `paicli.changeSpecEval.reactMaxIterations` | `15` | 每个 ReAct 阶段的评测最大迭代数，1～50 |

产物位于：

```text
target/change-spec-eval/<run-id>/
├── report.md
├── draft-attempts/  # 仅配对 Draft 最终无效时生成；脱敏并按 attempt 截断
├── workspaces/
└── first-pass/
```

若两次 Draft 都未通过结构或评测语义资格校验，Codec 错误会指出 Jackson 能定位到的具体字段路径；结构通过但未满足
任务 command、JUnit glob、最低公开测试数或 Criterion 引用规则时，错误也会反馈给第二次 Draft 纠错，最终仍不合格才按 `DRAFT_INVALID` 拒绝，不进入 B/C，也不产生配对 digest。
`draft-attempts/<case>-r<repetition>.md` 保存每次校验错误和脱敏后的模型输出，单次输出最多
保留 8 KiB，且不保存 system prompt、reasoning 或 API Key。`report.md` 会链接该诊断文件。

逐次结果的“诊断”列会把完整结束、隐藏任务失败且最终 changed-files 为空的 Spec Run 标为
`NO_CHANGE_COMPLETION`；即使公开 Verdict 因弱 Spec 而误判为 `PASSED`，也不会漏掉该标签。
该标签只解释失败形态，不替代公开 Verdict 或隐藏 Oracle。
首次 Verifier 失败进入修复时，repair input 同时携带首次 changed-files 数量；数量为 0 时会明确要求
模型实际使用工具检查并修改 workspace，不能只描述计划。修复次数仍严格限制为一次。

## 8. 报告边界

报告按全部任务和三个层级显示成功率，并对中型 + 高风险任务输出独立维度状态：

- `PASS`：该维度存在评测机会且达到暂定门槛；
- `FAIL`：该维度存在评测机会但未达到暂定门槛；
- `NOT_EVALUABLE`：成功率天花板、缺陷率下限、无完成声明分母或没有修复机会；
- `NOT_MEASURED`：自动 Pilot 未采集人工总投入等维度。

质量门槛先检查 C 相对 A 暂定 -5 个百分点的非劣护栏，再在存在改善空间时检查 +10 个百分点成功率增益或 30% 虚假完成下降。惩罚 TTA 暂保留“不恶化超过 15%”作为历史工程假设，但必须与成功 TTA、失败实际耗时、失败数、可信产品决策 TTA 和错误成本共同审阅。所有阈值都需要真实 SLA、缺陷成本和人力数据校准。

自动 Pilot 可以分别给出技术质量与自动时间结论；它无法测量 `total_human_effort`，因此该维度必须为 `NOT_MEASURED`。这不等于其他维度无价值，也不能据此宣称完整开发效率已经得到证明。

配对 Draft 采用双层约束：产品 Codec 拒绝让非 scope deterministic Criterion 只引用 `path_scope`；
评测资格检查再要求 command 精确命中任务允许列表、JUnit glob 固定为 `target/surefire-reports/TEST-*.xml`、`minimum_tests` 达到 fixture 的逐项公开证据下限，并校验每条非 scope deterministic Criterion 引用至少一个合格 command。报告分列公开证据实际执行测试数/任务下限。首次资格失败复用 Draft Generator 的唯一一次纠错机会；第二次仍失败才保存诊断。

12 个现有 fixture 的公开测试已按证据项拆分，并由免费预检核对声明测试数、实际 JUnit 执行数和参考实现；每个 fixture 的单点错误突变也必须被公开 Verifier 拒绝。任务扩展和证据加固发生在历史 GLM/DeepSeek Pilot 之后，因此后续付费结果必须建立新版基线，不能直接把与旧报告的变化归因给模型。

首次完整 Pilot、修复后的复跑结果与后续跨模型边界见 [ChangeSpec Pilot 修复与复跑清单](change-spec-pilot-remediation-checklist.md)。
