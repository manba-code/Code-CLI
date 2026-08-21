# ChangeSpec 任务状态

> 更新时间：2026-08-21
> 当前状态：前六条垂直切片已完成；第七条切片的 A/B/C 评测框架已经实现。最新 `safe-divider × 3轮` 真实 LLM smoke 中 A/B/C 成功率分别为 66.67%/66.67%/33.33%；C 有一轮通过零改动后的 Evidence 修复成功，另有一轮因弱 Spec 公开误放行。尚未运行完整 Pilot，因此还没有效率结论。
> 事实来源：当前工作区代码、`git status`、`git diff`、Maven/Surefire 测试结果，以及 `docs/change-spec-v1-rfc.md`。不能由这些材料证明的内容单独标为“尚未确认”。

## 1. 当前目标

PaiCLI 的 ChangeSpec 是可选的 Spec-Driven Code Change 契约层：把自然语言代码需求转换为可锁定、可执行、可验收的 ChangeSpec，并通过最小必要证据判断变更是否满足要求。目标是缩短“提出需求 → 获得可信验收结果”的时间，而不是建设需求管理、日志或通用工作流平台。

当前已交付六条切片：

1. ChangeSpec 领域模型、YAML Front Matter 解析、结构校验和稳定 digest；
2. `/spec <需求>` Draft 生成、一次结构纠错、确认/补充/取消交互；
3. 完整文档锁定持久化，最终确认需求与锁定 YAML/digest 注入现有 ReAct；
4. 独立 workspace baseline、changed-files/final-diff、`path_scope`、command 和新鲜 JUnit XML 验证；
5. Criterion Result、Human Criterion、固定优先级 Verdict、紧凑运行结果和单次运行指标持久化。
6. deterministic `FAIL` 后最多一次 Evidence 驱动修复、两轮 VerificationAttempt 和全量复验。

第七条切片当前已具备可运行基础设施：

- 独立 `change-spec-eval` Maven Profile，不改变原 ReAct/Plan/Team 的 `agent-eval`；
- A=普通 ReAct、B=ChangeSpec 完整验收但关闭修复、C=ChangeSpec 完整验收并允许一次修复；
- 每个“任务 × 重复轮次”的 B/C 共用同一份锁定 ChangeSpec document/digest；
- 2 个小型、2 个中型、2 个高风险隔离 Java fixture；
- 首次候选快照、公开 Verifier、隐藏 Oracle、Scope 白名单和最终报告；
- `task_success_rate`、`first_pass_success_rate`、`acceptance_pass_rate`、`false_completion_rate`、Scope、TTA、Token 和成本口径；
- 自动 Pilot 明确把 `human_intervention_time` 记为 `N/A`，不冒充为 0。
- 配对 Draft 最终无效时保存两次校验错误和脱敏、截断后的模型输出，报告链接诊断文件；YAML 类型错误包含具体字段路径。
- Codec 拒绝仅用 `path_scope` 证明 behavior 等非 scope deterministic Criterion；评测配对 Draft 还必须精确命中任务允许的 command，并让每条非 scope deterministic Criterion 引用允许的 command，否则按 `DRAFT_INVALID` 保存诊断且不进入 B/C。
- 修复输入携带首次 changed-files 数量；首次零改动时要求实际使用工具修改，不能只描述计划。评测报告以 `NO_CHANGE_COMPLETION` 标记完整结束、隐藏任务失败且零改动的 Spec Run，不改变生产 Verdict。

当前链路：

```text
需求 → Draft → 用户确认 → 不可覆盖锁定 → workspace baseline
    → Side-Git + ESC 取消包装 → 现有 ReAct
    → command/JUnit Verifier → 最终 workspace diff + path_scope
    → 首轮有 FAIL 且无 ERROR ? 同会话修复一次 → 基于原 baseline 全量复验
    → deterministic Criterion 归约 → Human Criterion（必要时）
    → Verdict → result.json + change.diff
```

尚未确认：

- ChangeSpec 是否实际提升开发效率，尚无 A/B/C 实验数据支持；
- 当前数据模型和真实模型 Draft 质量是否覆盖主要企业代码变更场景；
- Human Criterion 的真实使用频率、人工耗时和当前 Evidence 保留格式是否满足长期排错需要。

## 2. 第六条切片已完成内容

### 触发与生命周期

- 只有首轮至少一个 deterministic Criterion 为 `FAIL` 且所有 Verifier 都没有 `ERROR` 时触发修复；`FAIL + ERROR`、Verifier 命令 HITL/策略拒绝、取消、超时和启动异常都不触发。
- Human Criterion 的存在不阻止确定性修复，但只在最终一轮 deterministic Criterion 全部通过后判断。
- 修复复用同一个 `ReActExecutor` / `Agent` 会话，通过 `INITIAL / REPAIR` phase 让 CLI 和测试无需猜测输入文本；最多调用两次。
- 修复正常完成后再次回读锁定 Spec，并使用运行开始时的原始 workspace baseline 重跑全部 Verifier。
- 修复取消或异常得到 `REPAIR_CANCELED / REPAIR_FAILED` 和 `INCOMPLETE`，不使用首轮 Evidence 判断可能已部分变化的最终 workspace；修复期间篡改锁定 Spec 得到 `SPEC_INVALID`。
- 当前编码阶段普通工具拒绝仍是面向模型的文本结果，没有结构化拒绝遥测；V1 不做脆弱的字符串反解析。初始 ReAct 取消/失败和 Verifier 命令拒绝都有结构化信号。

### 两轮 Evidence 与指标

- `SpecRunResult.verificationAttempts` 是一至两轮 Verifier Evidence 的唯一事实源；`verifierResults()` 只派生最后一轮，供 CLI 兼容读取。
- VerificationAttempt 从 1 连续编号，phase 固定为 `initial / post_repair`；Evidence ID 使用 `verifier:attempt-<n>:<verifierId>`，最终 Criterion 只引用最后一轮有效 Evidence。
- `result.json` 继续使用 `paicli/spec-run-result/v1`，保存 `repairCount` 和 `verificationAttempts`；`repairCount` 表示已经启动的修复次数，修复异常或取消也记为 1。
- 修复 Prompt 与持久化共用 `SpecEvidenceFormatter`：命令输出清理 ANSI、敏感字段脱敏、最多保留 8 KiB 头尾摘要，修复输入中的全部 failure Evidence 另有 16 KiB 总预算。
- `change.diff` 只保存最终 workspace diff；首轮只保存当轮 changed files 和 Verifier Result，避免重复持久化大 diff。
- `reactExecutionMs`、`verificationMs` 和 ReAct LLM usage 累计初始与修复两轮；保留 Draft/ReAct/total 的既有指标口径。
- CLI 在修复前显示 `1/1 Evidence 驱动修复`，最终结果按 attempt 分组，并显示 `自动修复: 1/1`。

## 3. 第七条切片评测基础设施

### 生产代码

- `src/main/java/com/paicli/spec/SpecRunCoordinator.java`

生产默认行为不变。Coordinator 只增加显式 `RunOptions`：评测 B 组关闭修复，评测器通过旁路 observer 在首次验证后保存候选快照；observer 异常不会改变生产 Verdict。

### 测试

- `src/test/java/com/paicli/spec/SpecRunCoordinatorTest.java`
- `src/test/java/com/paicli/spec/eval/ChangeSpecEvaluationCase.java`
- `src/test/java/com/paicli/spec/eval/ChangeSpecEvaluationCatalog.java`
- `src/test/java/com/paicli/spec/eval/ChangeSpecEvaluationRunner.java`
- `src/test/java/com/paicli/spec/eval/ChangeSpecEvaluationReport.java`
- `src/test/java/com/paicli/spec/eval/ChangeSpecQualityEvaluationTest.java`
- `src/test/java/com/paicli/spec/eval/ChangeSpecEvaluationInfrastructureTest.java`
- 同目录的 mode/tier/result/paired-draft/LLM 计数与 renderer 支持类

### 文档

- `README.md`
- `AGENTS.md`
- `ROADMAP.md`
- `docs/change-spec-v1-rfc.md`
- `docs/change-spec-abc-evaluation.md`
- `TASK_STATE.md`

第七条切片的代码基线为 `15ed027 feat(spec): 完成一次 Evidence 驱动修复切片`；该提交已在 `main` / `origin/main`，开始本切片前工作树干净。

## 4. 验证结果

### 当前本切片通过

- `mvn test '-Dtest=Spec*Test,ChangeSpec*Test' -DskipTests=false`：59 tests，0 failure，0 error，2 个显式评测测试按预期 skipped，`BUILD SUCCESS`。
- `mvn test -Dtest=ChangeSpecEvaluationInfrastructureTest '-Dpaicli.changeSpecEval.validateFixtures=true' -DskipTests=false`：5 tests，0 failure，0 error，0 skipped；六个参考实现全部通过公开测试、隐藏 Oracle 和 Scope 检查，并由一个参考实现额外证明公开 Maven Verifier 可通过生产 `ToolRegistry` 命令路径运行。
- 新增覆盖 B 组不修复、首次 VerificationAttempt observer、六任务分层/命令白名单、隐藏 Oracle 与 Scope 独立判定、报告中的 `human_intervention_time=N/A` 和 B/C digest 配对审计。
- 测试运行期间 Maven 完成主代码 224 个源文件、测试代码 151 个源文件编译。
- `mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.enabled=false'`：Profile 能正确只选中 live test，并在禁用真实调用时安全 skipped。
- `safe-divider` 的前三次单轮 smoke 中 B/C 均在配对 Draft 阶段失败，错误依次暴露 dotted key、front matter 和字符串字段收到对象三类结构漂移；加入诊断能力后的第四次 smoke 生成有效配对 Draft，A/B 通过且 B/C digest 为 `23dabcd0e7344c49877b338b82c1d400670edb62d79c765a91de0b95d0d36096`，C 初始轮只调用 `glob_files`、修复轮未调用工具，两轮 `changedFiles=0`，最终失败。
- Draft 诊断能力的针对性回归覆盖精确字段路径、两次 attempt、敏感字段脱敏、8 KiB 截断和报告链接。
- 针对 C 的零改动轨迹新增确定性回归：repair input 必须携带 `workspace_changed_files_count: 0` 和实际工具操作要求；评测分类仅在 Spec Run 完整结束、隐藏任务失败且 changed-files=0 时产生 `NO_CHANGE_COMPLETION`。
- `glm / glm-4.6v-flashx`、`safe-divider`、3 次重复、seed `20260820` 的报告位于 `target/change-spec-eval/2026-08-21T14-05-30.562118800Z-20260820/report.md`：A/B/C 成功率为 66.67%/66.67%/33.33%，B/C digest 2/3（另一对 Draft 无效）；C 第 3 轮首次 changed-files=0，修复轮实际执行 `read_file`/`write_file` 后公开与隐藏验证通过。
- 同一报告的第 1 轮 Draft 诊断成功保存两次相同的无效输出，精确定位 `verifiers[0].include`；第 2 轮有效但弱化的 Draft 让行为 Criterion 只引用 `VT-SCOPE`，没有 command Verifier，导致公开 `PASSED`、零改动、隐藏失败。
- 双层语义资格修复后的 `safe-divider × 1轮` 报告位于 `target/change-spec-eval/2026-08-21T15-10-55.679296800Z-20260820/report.md`：A/B/C 均通过，B/C digest 1/1 一致；锁定 Spec 的两条 behavior Criterion 均引用允许命令 `mvn -q -DskipTests=false test`，C 首轮失败后一次修复通过。

### Quick 已知基线

- `mvn test -Pquick`：817 tests，10 failures，0 errors，3 skipped，`BUILD FAILURE`。
- 此前失败类和方法与第四条切片的 10 项基线完全一致：`ImageReferenceParserTest` 3 项，`MemoryManagerTest` 1 项，`PromptAssemblerTest` 1 项，`CodeIndexTest` 2 项，`CodeRetrieverTest` 1 项，`InlineRendererTest` 1 项，`CodeSearchGoldenSetTest` 1 项；本次 Windows ToolRegistry 修复后未重跑 Quick，针对性回归与评测 fixture 预检均通过。
- 已归因并修复 `ToolRegistryTest` 的 Windows 跨平台问题：`execute_command` 改用原生 `cmd.exe`，项目相对路径统一输出 `/`，超时清理子进程树；20 tests 全部通过。相关 `CommandGuardTest`、`ApprovalPolicyTest` 和 `SpecVerifierTest` 也通过。

## 5. 未解决问题

### 已验证的缺口

- 已有 A/B/C 任务集、三轮缺口定位 smoke 和一轮修复后 smoke；零改动修复防护已命中真实成功，配对 Draft 语义资格也已通过本地测试与真实有效 Draft 验证；
- 完整 `human_intervention_time` 尚未单独汇总 Spec 确认、HITL 等待和 Human Criterion 时间；
- V1 仍只支持 revision 1，不支持运行中修改锁定需求或恢复/重跑既有 Spec；
- Quick 历史基线不是绿色状态，10 项既有失败归因仍未完成；

### 明确不在当前切片

- LLM Reviewer；
- Plan/Team 接入；
- 通用 Verifier SPI、工作流引擎或自动 run 清理策略。

## 6. 下一阶段任务

当前阻塞项已解决并通过本地回归与一次真实单任务 smoke。下一步先提交本批次，再由用户明确决定是否运行默认 36 次、会产生 Token 费用的完整 Pilot。完整 Pilot 命令为：

```bash
mvn test -Pchange-spec-eval
```

最小完成边界：

1. 先保持默认 6 个任务 × 3 组 × 2 次，不在首轮临时更换模型、任务或口径；
2. 运行前记录 provider/model 和可选的输入/输出 Token 单价；
3. 审计 12 对 B/C digest 是否一致、隐藏测试是否只在候选完成后注入；
4. 输出 `target/change-spec-eval/<run-id>/report.md`；
5. 自动 Pilot 的人工时间保持 `N/A`，即使其他门槛通过也不宣称满足完整提效门槛；
6. 若自动 Pilot 出现正向信号，再另行设计有真实用户参与的确认/HITL/Human Criterion 计时试验。
