# ChangeSpec V1 A/B/C 评测协议

> 状态：评测框架已实现，真实 LLM 快速试验尚未运行。  
> 运行入口：`mvn test -Pchange-spec-eval`（会产生网络请求和 Token 费用）。

## 1. 要回答的问题

本评测只回答两个产品问题：

1. ChangeSpec 契约和确定性 Evidence Gate 是否比普通 ReAct 更可靠；
2. 在同一份锁定 ChangeSpec 下，一次 Evidence 驱动修复是否带来可测的增益。

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

默认任务集包含六个隔离 Java 17 Maven fixture：

| 层级 | 任务 | 主要风险 |
|---|---|---|
| 小型 | `safe-divider` | 边界条件与既有语义 |
| 小型 | `ascii-slugifier` | 字符分类、Locale 与折叠规则 |
| 中型 | `login-retry-policy` | 多分支重试次数和异常传播 |
| 中型 | `timeout-config-compat` | 新旧配置兼容、优先级和输入校验 |
| 高风险 | `workspace-path-safety` | 路径逃逸和安全边界 |
| 高风险 | `operation-result-api-compat` | 公共 API 源兼容和新增语义 |

每个任务固定包含：

- 原始任务描述和模型可见源码/测试；
- `bounded` Scope 所需的允许修改文件集合；
- 唯一允许的公开命令 `mvn -q -DskipTests=false test`；
- 模型不可见的隐藏 JUnit Oracle；
- 两分钟隐藏验证命令超时；
- 独立 workspace、长期记忆和运行产物。

公开测试用于给 Agent 和 Evidence 修复提供可行动的失败证据；隐藏测试覆盖额外边界，只在候选完成后写入首次/最终候选的副本或 workspace。Agent 永远看不到隐藏测试内容和输出。

## 4. 自动交互策略

- 第一个结构有效的配对 Draft 自动确认；结构纠错仍遵循生产 Draft Generator 最多两次生成的规则；
- 公开 Verifier 只有命令完全等于任务预声明命令时才执行，其他命令记为 `HITL_DENIED`；
- fixture 位于隔离 workspace，Agent 继续受 PathGuard 和 CommandGuard 约束；
- 自动评测不替代 Human Criterion：若 Draft 生成 Human Criterion，评测器选择 `SKIPPED`，最终通常为 `NEEDS_HUMAN`；
- 因为没有真实用户，本 Pilot 的 `human_intervention_time` 固定报告为 `N/A`，不能写成 0。

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

### `scope_violation_rate`

最终 changed files 出现任何不在 `allowedChangedFiles` 中的业务文件，即为一次越界。`target`、`.paicli`、评测日志和记忆目录不作为业务变化。

### `time_to_accepted_change`

成功运行记录：

```text
产品运行总耗时 + 最终隐藏 Oracle 耗时
```

失败运行按统一 `censorMinutes` 截断。报告同时单列产品耗时和隐藏 Oracle 耗时。默认截断值为 10 分钟；这是指标截断口径，Agent 自身仍使用相同的产品默认轮数预算和命令超时。

### Token 与成本

记录 Draft + ReAct 的 calls、input/output/cached tokens。成本只有显式提供每百万 Token 单价时才估算，报告不内置可能变化的模型价格。

## 6. 公平性与随机性

- 同一任务的三组从相同 visible fixture 独立物化；
- 同一 provider/model、工具实现和原始任务；
- B/C 共用锁定 Spec document/digest；
- 模式顺序按固定 seed 随机化；
- 每个“任务 × 模式 × 重复轮次”使用独立 workspace 和记忆；
- 当前客户端不能统一设置所有 provider 的采样 seed，真实模型输出不能完全复现，因此快速试验默认重复两次；
- 快速样本只能形成描述性结论，稳定后按 RFC 扩展到 12～15 个任务、每组 3 次。

## 7. 运行

付费运行前可显式复验六个参考实现都能通过各自的公开测试和隐藏 Oracle，并额外用生产 `ToolRegistry.executeCommandForVerification` 路径运行一个公开 Maven Verifier，防止平台 Shell 差异污染 B/C；该检查默认回归中跳过：

```bash
mvn test -Dtest=ChangeSpecEvaluationInfrastructureTest \
  '-Dpaicli.changeSpecEval.validateFixtures=true' \
  -DskipTests=false
```

默认运行六个任务、三组、每组两次，共 36 次产品运行；另有每个“任务 × 重复轮次”一次配对 Draft 调用：

```bash
mvn test -Pchange-spec-eval
```

常用参数：

```bash
# 指定 provider
mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.provider=deepseek'

# 只跑一个任务、一次重复，用于付费运行前 smoke
mvn test -Pchange-spec-eval \
  '-Dpaicli.changeSpecEval.cases=safe-divider' \
  '-Dpaicli.changeSpecEval.repetitions=1'

# 固定顺序并配置美元单价
mvn test -Pchange-spec-eval \
  '-Dpaicli.changeSpecEval.seed=20260820' \
  '-Dpaicli.changeSpecEval.inputCostPerMillion=0.50' \
  '-Dpaicli.changeSpecEval.outputCostPerMillion=2.00'
```

配置项：

| 属性 | 默认值 | 说明 |
|---|---:|---|
| `paicli.changeSpecEval.provider` | PaiCLI 默认 provider | 指定本次使用的 provider |
| `paicli.changeSpecEval.repetitions` | `2` | 每任务/组重复次数，1～20 |
| `paicli.changeSpecEval.seed` | `20260820` | 三组运行顺序 seed |
| `paicli.changeSpecEval.cases` | 全部 | 逗号分隔任务 ID |
| `paicli.changeSpecEval.censorMinutes` | `10` | 失败 TTA 截断分钟数，1～60 |
| `paicli.changeSpecEval.inputCostPerMillion` | `0` | 输入 Token 单价；0 表示不估价 |
| `paicli.changeSpecEval.outputCostPerMillion` | `0` | 输出 Token 单价；0 表示不估价 |

产物位于：

```text
target/change-spec-eval/<run-id>/
├── report.md
├── workspaces/
└── first-pass/
```

## 8. 报告边界

报告按全部任务和三个层级分别显示成功率，并对中型 + 高风险任务计算 RFC 门槛：

- C 相比 A 成功率是否提高至少 10 个百分点，或虚假完成率是否相对下降至少 30%；
- C 的 TTA P50 是否恶化不超过 15%；
- `human_intervention_time` 是否不增加。

自动 Pilot 无法测量最后一项，所以即使前两项满足，也只能说明“出现自动化质量/耗时正向信号”，不能宣称已经满足完整开发效率价值门槛。需要真实用户参与的确认、HITL 和 Human Criterion 计时试验才能补齐该结论。
