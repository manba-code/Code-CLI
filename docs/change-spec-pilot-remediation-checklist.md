# ChangeSpec Pilot 修复与复跑清单

> 基线：2026-08-21，`glm / glm-4.6v-flashx`，6 个任务 × 3 组 × 2 次。  
> 原始报告：`target/change-spec-eval/2026-08-21T15-47-31.935897400Z-20260820/report.md`。  
> 原则：先固定架构和评测口径，用同一模型复跑；只有同模型对照完成后才换模型。

## 1. 已立即生效

- [x] B/C 的产品耗时和成功 TTA 计入配对 Draft 生成耗时，不再只统计 ReAct、验证与修复。
- [x] 报告同时展示产品耗时、客观正确候选 TTA、可信产品决策 TTA、失败实际耗时、惩罚 TTA 和失败数；固定 `600s` 只作为历史惩罚评分，不再称为真实执行时长或严格统计删失。
- [x] 成本增加显式币种；本项目当前价格参数应使用 `CNY`，不再默认显示美元符号。
- [x] Windows 隐藏 Oracle 日志按严格 UTF-8、宿主默认编码、GB18030 顺序容错解码；非零退出仍判失败，但原生编码字节不再升级成 `Input length=1` 的评测异常。
- [x] Agent 停滞检测覆盖重复两步工具周期，例如 `write_file → execute_command` 循环。
- [x] 自动评测使用独立安全预算：默认单阶段最多 15 次 ReAct 迭代、250,000 Token；生产 CLI 默认仍为 50 轮、Token 不限。
- [x] 配对 Draft 的任务命令白名单与 Criterion 引用资格检查进入 Draft Generator 的既有两次纠错链路；第一次语义不合格时还有一次修正机会。
- [x] 增加 `paicli.changeSpecEval.model`，可只在本次评测 JVM 内覆盖模型，不修改用户持久配置。
- [x] 补充确定性单元测试和评测基础设施测试。

## 2. 免费验证

- [x] ChangeSpec/预算/报告回归全部通过；四态结论、新时间口径、ReAct LLM/工具墙钟拆分和 13 任务公开证据契约落地后的最新结果为 42 tests、0 failure、0 error、3 个 fixture 预检按开关跳过：

```powershell
mvn test '-Dtest=AgentBudgetTest,SpecDraftGeneratorTest,ChangeSpecEvaluationInfrastructureTest' -DskipTests=false
```

- [x] 13 个 fixture 通过逐项公开证据下限与隐藏 Oracle，并验证每个确定性单点错误突变都被生产命令执行路径拒绝（最新 22/22，0 failure，0 error，耗时 442.0s）：

```powershell
mvn test -Dtest=ChangeSpecEvaluationInfrastructureTest '-Dpaicli.changeSpecEval.validateFixtures=true' -DskipTests=false
```

- [x] 扩大免费回归：`mvn test '-Dtest=Spec*Test,ChangeSpec*Test' -DskipTests=false`，最新 84 tests、0 failure、0 error、4 个显式评测项按预期跳过。不要把仓库既有 Quick 红灯混成这批修复的回归。

## 3. 同模型代表性小样本（已完成，9 次产品运行）

先选一个小型、一个中型、一个高风险任务，各跑 A/B/C 一次。仍使用原模型 `glm-4.6v-flashx`：

```powershell
mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.provider=glm' '-Dpaicli.changeSpecEval.model=glm-4.6v-flashx' '-Dpaicli.changeSpecEval.cases=ascii-slugifier,login-retry-policy,workspace-path-safety' '-Dpaicli.changeSpecEval.repetitions=1' '-Dpaicli.changeSpecEval.seed=20260820' '-Dpaicli.changeSpecEval.inputCostPerMillion=0.15' '-Dpaicli.changeSpecEval.outputCostPerMillion=1.5' '-Dpaicli.changeSpecEval.costCurrency=CNY'
```

进入同模型全量复跑前必须满足：

- Draft 有效且 B/C digest `3/3` 一致；
- 没有 `Input length=1`；
- 单阶段不超过 15 次 ReAct 迭代，没有明显的两步循环长尾；
- 报告出现三种耗时口径、截断数和 `CNY`；
- 失败能够归到代码结果、`DRAFT_INVALID`、预算止损或明确的环境错误，不能只留下模糊异常。

完成结果：`target/change-spec-eval/2026-08-23T04-42-48.514572700Z-20260820/report.md`。五项门槛全部通过：B/C digest `3/3`，无 Draft 诊断和 `Input length=1`，单阶段最多 15 次调用，预算止损原因可审计，报告包含三类耗时、截断数和 CNY。

## 4. 同模型完整复跑（已完成，36 次产品运行）

小样本通过后，用原模型、原六任务、原两次重复和原 seed 复跑：

```powershell
mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.provider=glm' '-Dpaicli.changeSpecEval.model=glm-4.6v-flashx' '-Dpaicli.changeSpecEval.repetitions=2' '-Dpaicli.changeSpecEval.seed=20260820' '-Dpaicli.changeSpecEval.inputCostPerMillion=0.15' '-Dpaicli.changeSpecEval.outputCostPerMillion=1.5' '-Dpaicli.changeSpecEval.costCurrency=CNY'
```

这一轮用于隔离“架构/评测修复”的效果。和旧报告比较：Draft 有效率、A/B/C 成功率、C 修复增益、虚假完成率、成功 TTA、固定失败惩罚数、平均调用与 Token。旧报告的惩罚 TTA P50 不能解释为实际失败耗时。

完成结果：`target/change-spec-eval/2026-08-23T04-59-57.745377400Z-20260820/report.md`，SHA-256 `887d3a2bfa17296f7992a1cbfe8ba996a15de3ea4bf44baa281c9f0845ba6e55`。

- 配对 Draft/digest 从 `8/12` 提升到 `12/12`，无 `DRAFT_INVALID` 和 `Input length=1`；最长 ReAct 阶段从旧 Pilot 的 50 次压到 15 次。
- A/B/C 成功率从 50.00%/41.67%/33.33% 变为 58.33%/41.67%/41.67%；C 首次成功率 33.33%、最终成功率 41.67%，一次修复净增 1/12。
- C 虚假完成率从 20.00% 降到 0%，但 B/C 成功率仍低于 A；中型 + 高风险任务中 C 比 A 低 25 个百分点，不能宣称 ChangeSpec 已提效。
- 成功 TTA P50 为 A/B/C 29.29s/46.18s/63.02s，失败惩罚数 5/12、7/12、7/12；自动 Pilot 的完整人工总投入仍为 `NOT_MEASURED`。
- 按实际 API 调用去除 B/C 报告中重复计入的配对 Draft 后，本轮约 1,108,295 输入 Token、99,509 输出 Token，按本次参数估算约 CNY 0.3155。

## 5. 仍需进一步验证或设计

- [x] 报告不再把天花板、零缺陷下限、无修复机会或未采集维度混成普通失败；统一使用 `PASS / FAIL / NOT_EVALUABLE / NOT_MEASURED`，且不输出单一“有价值/无价值”总分。
- [x] 报告拆分 A→B（契约与公开 Evidence Gate）、B→C（Evidence 修复）和 A→C（完整产品路径），并显示按任务/重复轮次配对的候选胜/负/平。
- [x] 增加 `repair_eligible_count`、修复尝试率和条件修复成功率；没有修复机会时明确为 `NOT_EVALUABLE`。
- [x] 时间指标分为客观正确候选 TTA、可信产品决策 TTA、失败实际耗时和惩罚 TTA；固定 `600s` 明确只是历史失败惩罚，不再称为实际失败耗时或严格删失时间。
- [x] 报告增加 Draft、ReAct、公开 Verifier、隐藏 Oracle 分段 P50、成功率 95% Wilson 区间和单位成功成本。
- [x] ReAct 内增加 LLM 请求与工具批次各自的墙钟采集；失败模型请求仍计时，并行工具按整批等待时间统计，公开 Verifier 继续单列。LLM 请求内部的服务端推理、网络和流式接收仍无法再拆分。
- [x] 13 个 fixture（4 small / 5 medium / 4 high）声明逐项公开证据契约，公开测试按证据项拆分；Draft 的 command、JUnit glob、`minimum_tests` 下限和 Criterion 引用在锁定前校验，报告显示执行测试数/任务下限。
- [x] 任务目录达到 12～15 个的数量下限，新增显式非目标、跨文件约束、兼容性决策、安全脱敏和状态转换；每个 fixture 都有一个基于参考实现的确定性单点错误突变，免费预检必须证明公开 Verifier 能杀死它。
- [x] 新增专门的歧义澄清任务 `clarified-display-name`；A/B/C 接收完全相同的统一澄清记录，且参考实现、隐藏 Oracle、Scope、生产公开 Verifier 和单点突变均通过免费预检。
- [ ] 新版真实评测每组至少重复 3 次。13 任务 × 3 组 × 3 次将产生 117 次产品运行；当前未获授权，不得自动启动。
- [x] 真人总人时 9-session 可行性试跑已由 3 名执行参与者 + 1 名独立需求确认者完成：9/9 session `VALID`、Scope 9/9，客观正确率 A/B/C 为 1/3、1/3、2/3，false acceptance 为 2/3、2/3、1/3，C 修复机会 0/3；测后说明最终 `ACCEPT` 未基于具体代码审阅，故真人效率与接受准确率不可评价。模式工程价值有正面证据，量化收益未证明；详见 [`change-spec-human-effort-feasibility-20260823-01-zh.md`](change-spec-human-effort-feasibility-20260823-01-zh.md)。
- [x] 单独归因并修复仓库 Quick 的历史失败：刷新后 9 个失败均来自 Windows 路径/CRLF 假设、RAG 测试外部依赖或 project path 规范化不一致；修复后 `-Pquick` 为 846 tests、0 failure、0 error、5 skipped，不带付费 Profile 的全量回归为 892 tests、0 failure、0 error、11 skipped。

> 第 79 项增强了 fixture 公开测试和 Draft 资格契约，发生在已归档的 GLM/DeepSeek Pilot 之后。旧报告继续作为历史证据，但未来付费结果必须先建立新版 A/B/C 基线，不能把与旧报告的变化直接归因给模型。

## 6. 跨模型对照（DeepSeek 小样已完成，完整复跑未执行）

同模型全量结果归档后，再固定代码、任务、seed、重复次数和评测预算，只改 provider/model。先跑第 3 节的 9 次小样本，稳定后再跑 36 次。示例：

```powershell
mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.provider=deepseek' '-Dpaicli.changeSpecEval.model=<候选模型ID>' '-Dpaicli.changeSpecEval.cases=ascii-slugifier,login-retry-policy,workspace-path-safety' '-Dpaicli.changeSpecEval.repetitions=1' '-Dpaicli.changeSpecEval.seed=20260820' '-Dpaicli.changeSpecEval.inputCostPerMillion=<输入单价>' '-Dpaicli.changeSpecEval.outputCostPerMillion=<输出单价>' '-Dpaicli.changeSpecEval.costCurrency=CNY'
```

模型对照重点看：Draft 首次/最终有效率、可见测试一次做对率、隐藏 Oracle 成功率、循环/预算止损次数、成功 TTA 和单位成功成本。若只换模型后明显改善，才能把差值主要归到模型能力；在此之前不能给“纯模型原因占比”一个可信百分比。

2026-08-23 已按冻结的三个代表任务、seed、单次重复和预算运行 `deepseek / deepseek-v4-pro-0813` 小样，报告位于 `target/change-spec-eval/2026-08-23T06-23-26.095442900Z-20260820/report.md`，SHA-256 为 `BC027951BE2DDA53E31727DBC071751F66CB025700F3649731FB8A6617721184`：

- A/B/C 均为 100%，B/C digest `3/3`，Scope 越界和虚假完成均为 0；
- C 全部首次通过、修复机会为 0，因此增量成功率和修复价值分别属于成功率天花板与无机会，不能解释为“没有价值”；
- 中型 + 高风险的 C 相对 A 自动耗时明显增加，说明当前产品路径存在自动流程开销；
- 本轮未配置模型单价，不能比较单位成功成本；每任务只重复一次，也不能形成统计学上的跨模型结论；
- 后续 36 次 DeepSeek 完整复跑未启动，必须由用户审核本轮报告后单独批准。
