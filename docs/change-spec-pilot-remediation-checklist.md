# ChangeSpec Pilot 修复与复跑清单

> 基线：2026-08-21，`glm / glm-4.6v-flashx`，6 个任务 × 3 组 × 2 次。  
> 原始报告：`target/change-spec-eval/2026-08-21T15-47-31.935897400Z-20260820/report.md`。  
> 原则：先固定架构和评测口径，用同一模型复跑；只有同模型对照完成后才换模型。

## 1. 已立即生效

- [x] B/C 的产品耗时和成功 TTA 计入配对 Draft 生成耗时，不再只统计 ReAct、验证与修复。
- [x] 报告同时展示产品耗时 P50、仅成功样本 TTA P50、失败截断 TTA P50和截断数，避免把大量 `600s` 截断误读为真实执行时长。
- [x] 成本增加显式币种；本项目当前价格参数应使用 `CNY`，不再默认显示美元符号。
- [x] Windows 隐藏 Oracle 日志按严格 UTF-8、宿主默认编码、GB18030 顺序容错解码；非零退出仍判失败，但原生编码字节不再升级成 `Input length=1` 的评测异常。
- [x] Agent 停滞检测覆盖重复两步工具周期，例如 `write_file → execute_command` 循环。
- [x] 自动评测使用独立安全预算：默认单阶段最多 15 次 ReAct 迭代、250,000 Token；生产 CLI 默认仍为 50 轮、Token 不限。
- [x] 配对 Draft 的任务命令白名单与 Criterion 引用资格检查进入 Draft Generator 的既有两次纠错链路；第一次语义不合格时还有一次修正机会。
- [x] 增加 `paicli.changeSpecEval.model`，可只在本次评测 JVM 内覆盖模型，不修改用户持久配置。
- [x] 补充确定性单元测试和评测基础设施测试。

## 2. 免费验证

- [x] ChangeSpec/预算/报告回归全部通过（最终 35 tests，0 failure，0 error，2 个 fixture 预检按开关跳过）：

```powershell
mvn test '-Dtest=AgentBudgetTest,SpecDraftGeneratorTest,ChangeSpecEvaluationInfrastructureTest' -DskipTests=false
```

- [x] 六个 fixture 通过公开与隐藏 Oracle，并验证生产命令执行路径（15/15，0 failure，0 error）：

```powershell
mvn test -Dtest=ChangeSpecEvaluationInfrastructureTest '-Dpaicli.changeSpecEval.validateFixtures=true' -DskipTests=false
```

- [x] 扩大免费回归：`mvn test '-Dtest=Spec*Test,ChangeSpec*Test' -DskipTests=false`，最终 77 tests、0 failure、0 error、3 个显式评测项按预期跳过。不要把仓库既有 Quick 红灯混成这批修复的回归。

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

这一轮用于隔离“架构/评测修复”的效果。和旧报告比较：Draft 有效率、A/B/C 成功率、C 修复增益、虚假完成率、成功 TTA、截断数、平均调用与 Token。不要只比较新的截断 TTA P50。

完成结果：`target/change-spec-eval/2026-08-23T04-59-57.745377400Z-20260820/report.md`，SHA-256 `887d3a2bfa17296f7992a1cbfe8ba996a15de3ea4bf44baa281c9f0845ba6e55`。

- 配对 Draft/digest 从 `8/12` 提升到 `12/12`，无 `DRAFT_INVALID` 和 `Input length=1`；最长 ReAct 阶段从旧 Pilot 的 50 次压到 15 次。
- A/B/C 成功率从 50.00%/41.67%/33.33% 变为 58.33%/41.67%/41.67%；C 首次成功率 33.33%、最终成功率 41.67%，一次修复净增 1/12。
- C 虚假完成率从 20.00% 降到 0%，但 B/C 成功率仍低于 A；中型 + 高风险任务中 C 比 A 低 25 个百分点，不能宣称 ChangeSpec 已提效。
- 成功 TTA P50 为 A/B/C 29.29s/46.18s/63.02s，失败截断数 5/12、7/12、7/12；自动 Pilot 的人工介入时间仍为 `N/A`。
- 按实际 API 调用去除 B/C 报告中重复计入的配对 Draft 后，本轮约 1,108,295 输入 Token、99,509 输出 Token，按本次参数估算约 CNY 0.3155。

## 5. 仍需进一步验证或设计

- [ ] 增加按 LLM 请求、工具执行、公开 Verifier、隐藏 Oracle 分段的延迟明细，才能进一步拆分模型推理、网络和本地 Maven 的耗时。
- [ ] 审计公开 Verifier 对每条 Criterion 的证据覆盖强度；“一个宽泛 Maven 命令证明全部语义”仍可能造成公开验收过浅。
- [ ] 扩大到 12～15 个任务、每组至少 3 次，并报告置信区间；当前 12 次/组只能作工程判断。
- [ ] 设计真人参与的 Spec 确认、HITL、Human Criterion 计时；自动 Pilot 的人工介入时间继续是 `N/A`。
- [ ] 单独归因仓库 Quick 的历史失败，避免简历材料声称“全量测试绿色”。

## 6. 最后换模型

同模型全量结果归档后，再固定代码、任务、seed、重复次数和评测预算，只改 provider/model。先跑第 3 节的 9 次小样本，稳定后再跑 36 次。示例：

```powershell
mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.provider=deepseek' '-Dpaicli.changeSpecEval.model=<候选模型ID>' '-Dpaicli.changeSpecEval.cases=ascii-slugifier,login-retry-policy,workspace-path-safety' '-Dpaicli.changeSpecEval.repetitions=1' '-Dpaicli.changeSpecEval.seed=20260820' '-Dpaicli.changeSpecEval.inputCostPerMillion=<输入单价>' '-Dpaicli.changeSpecEval.outputCostPerMillion=<输出单价>' '-Dpaicli.changeSpecEval.costCurrency=CNY'
```

模型对照重点看：Draft 首次/最终有效率、可见测试一次做对率、隐藏 Oracle 成功率、循环/预算止损次数、成功 TTA 和单位成功成本。若只换模型后明显改善，才能把差值主要归到模型能力；在此之前不能给“纯模型原因占比”一个可信百分比。
