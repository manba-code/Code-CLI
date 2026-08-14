# Agent A/B 质量评测

首轮真实 LLM Pilot 的结果、根因和复测建议见
[`agent-ab-evaluation-pilot-report-2026-08-10.md`](agent-ab-evaluation-pilot-report-2026-08-10.md)。

## 目标

这套评测用于观察不同 Agent 架构在同一批工程任务上的真实效果，不属于普通确定性单元测试。

对照组固定为：

| 组 | PaiCLI 路径 | 用途 |
|---|---|---|
| A | ReAct `Agent` | 单 Agent 基线 |
| B | `PlanExecuteAgent` | 观察先规划再执行、但没有质量 Reviewer 的效果 |
| C | `AgentOrchestrator` | 完整 Planner / Worker / Reviewer Multi-Agent |

评测不会预设 C 必须优于 A/B，而是同时报告成功率、LLM 调用、Token、耗时、Reviewer 误放行/误拒和纠正恢复情况。

## 公平性与隔离

- 三组使用相同 provider、model、任务描述、工具集合和可见项目文件。
- 每个“用例 × 模式 × 重复轮次”创建独立临时工作区和独立 LongTermMemory。
- 三种模式的执行顺序按固定 seed 随机化，减少固定先后顺序影响。
- Agent 完成后才把隐藏测试写入工作区，模型执行期间看不到验收代码。
- 除用例声明允许修改的文件外，任何新增、修改或删除都会让“变更范围”检查失败。
- 真实 LLM 有随机性；建议至少重复 3～5 次，不以单次结果下结论。

评测工作区和报告都写在 `target/agent-eval/<run-id>/`，不会修改当前仓库源码。每次执行的 `run.log`、最终文件和隐藏验证日志会保留，便于审计。

## 运行

评测默认不运行，只有显式启用 Maven Profile 才会产生真实网络请求和 Token 消耗：

```bash
# 使用 PaiCLI 默认 provider，两个内置用例各运行一轮
mvn test -Pagent-eval

# 指定 provider，重复 5 轮
mvn test -Pagent-eval '-Dpaicli.eval.provider=deepseek' '-Dpaicli.eval.repetitions=5'

# 只运行一个用例
mvn test -Pagent-eval '-Dpaicli.eval.cases=safe-divider'

# 固定随机顺序，并配置每百万 Token 的美元价格估算（PowerShell 可直接运行）
mvn test -Pagent-eval '-Dpaicli.eval.seed=20260810' '-Dpaicli.eval.inputCostPerMillion=0.50' '-Dpaicli.eval.outputCostPerMillion=2.00'
```

API Key 与模型沿用 `PaiCliConfig`、环境变量和项目 `.env` 的现有加载规则。没有可用配置时评测会明确失败，不会生成“全部跳过但构建成功”的假结果。

可配置项：

| 属性 | 默认值 | 说明 |
|---|---:|---|
| `paicli.eval.provider` | PaiCLI 默认 provider | 指定同一评测使用的 provider |
| `paicli.eval.repetitions` | `1` | 每个用例重复次数，范围 1～20 |
| `paicli.eval.seed` | `20260810` | 三组执行顺序的随机种子 |
| `paicli.eval.cases` | 全部 | 逗号分隔的用例 ID |
| `paicli.eval.inputCostPerMillion` | `0` | 输入 Token 单价；0 表示不估价 |
| `paicli.eval.outputCostPerMillion` | `0` | 输出 Token 单价；0 表示不估价 |

## 内置任务与客观判定

当前提供两个小型 Java 修复任务：

- `safe-divider`：修复除零行为和整数除法语义。
- `ascii-slugifier`：实现 null/空白、Locale、ASCII 字符和分隔符折叠规则。

每个用例包含：

1. 模型可见的初始文件；
2. 模型不可见的隐藏 JUnit 测试；
3. 允许修改的文件白名单；
4. 有超时的 Maven 验证命令。

只有“没有未授权变更”并且“隐藏测试通过”两个条件都成立，任务才记为成功。模型或 Reviewer 的自然语言结论不参与客观成功判定。

## 报告解读

`report.md` 的总览比较：

- 任务成功率；
- 平均检查通过率；
- 平均 LLM 调用次数；
- 输入、输出和缓存 Token；
- Agent 执行耗时；
- 可选的估算成本。

Reviewer 混淆矩阵把 Multi-Agent 最终审批与隐藏验证交叉比较：

| 客观结果 | Reviewer 批准 | Reviewer 拒绝 |
|---|---|---|
| 隐藏验证通过 | 正确批准 | 误拒 |
| 隐藏验证失败 | 误放行 | 正确拒绝 |

`FAILED` 或 `BLOCKED` 没有形成明确质量判决，不进入该矩阵，但仍作为任务失败保留在逐次结果中。

## 如何扩展用例

在 `AgentEvaluationCatalog` 中增加 `AgentEvaluationCase`：

- 可见fixture应尽量小，减少Token噪声；
- 隐藏测试不要提前写入visible files；
- `allowedChangedFiles`只列任务真正允许修改的文件；
- 验证命令必须非交互、可重复并设置合理超时；
- 优先使用编译、JUnit、文件哈希等确定性Oracle，不要再用LLM给结果打分。

## 已知边界

- 当前客户端没有统一暴露temperature/seed控制，所以只能通过重复运行和随机化顺序降低采样波动，不能实现完全可复现的模型输出。
- B组使用现有Plan-and-Execute路径，它是“无质量Reviewer”的产品级消融组，但并非只删除Reviewer、其他实现逐行完全相同。
- Token成本依赖用户提供的单价；报告不内置可能随时间变化的模型价格。
- 两个内置任务只验证评测基础设施，不能代表大型跨文件工程任务；形成面试或产品结论前应扩展到至少20个分层任务。
