# ChangeSpec 任务状态

> 更新时间：2026-08-23
> 当前状态：前六条垂直切片已完成；第七条切片的首次 Pilot 修复、GLM 同模型代表性小样和完整 36 次复跑均已完成。评测目录已扩展到 12 个任务（每层 4 个），每个任务都有逐项公开证据契约和一个确定性单点错误突变。`glm-4.6v-flashx` 历史完整复跑 A/B/C 成功率为 58.33%/41.67%/41.67%；`deepseek-v4-pro-0813` 的历史冻结三任务小样 A/B/C 均为 100%。两者均早于本次任务目录与证据加固，不能直接与未来新版基线比较；任何新的完整付费评测均未获授权。
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
- 4 个小型、4 个中型、4 个高风险隔离 Java fixture；
- 首次候选快照、公开 Verifier、隐藏 Oracle、Scope 白名单和最终报告；
- `task_success_rate`、`first_pass_success_rate`、完成声明覆盖率、声明内/全运行虚假率、Scope、修复机会/条件成功率、四类时间口径、Token、平均产品成本和单位成功成本；
- 报告使用 `PASS / FAIL / NOT_EVALUABLE / NOT_MEASURED`，显示成功率 95% Wilson 区间和 A→B/B→C/A→C 配对胜/负/平，不再输出单一“有价值/无价值”总分；
- 自动 Pilot 明确把完整 `total_human_effort` 记为 `NOT_MEASURED`，不冒充为 0；Spec 确认或 Human Criterion 的局部计时不能替代完整人工投入。
- 配对 Draft 最终无效时保存两次校验错误和脱敏、截断后的模型输出，报告链接诊断文件；YAML 类型错误包含具体字段路径。
- Codec 拒绝仅用 `path_scope` 证明 behavior 等非 scope deterministic Criterion；评测配对 Draft 还必须精确命中任务允许的 command，并让每条非 scope deterministic Criterion 引用允许的 command，否则按 `DRAFT_INVALID` 保存诊断且不进入 B/C。
- 修复输入携带首次 changed-files 数量；首次零改动时要求实际使用工具修改，不能只描述计划。评测报告以 `NO_CHANGE_COMPLETION` 标记完整结束、隐藏任务失败且零改动的 Spec Run，不改变生产 Verdict。
- 评测语义资格失败会进入 Draft Generator 既有的最多两次纠错链路；最终仍无效才保存 `DRAFT_INVALID`。
- 12 个 fixture 按需求语义声明逐项公开证据契约，公开测试按证据项拆分；Draft command 的命令、JUnit glob、`minimum_tests` 下限和 Criterion 引用均在锁定前校验，报告显示执行测试数/任务下限。每个 fixture 另有一个参考实现上的确定性单点错误突变，免费预检要求生产公开 Verifier 必须拒绝它。
- 自动评测使用独立的 15 轮/250k Token ReAct 预算，生产 CLI 默认不变；停滞检测同时覆盖相同单步与两步工具周期。
- 报告将产品耗时、客观正确候选 TTA、可信产品决策 TTA、失败实际耗时和惩罚 TTA 分列；B/C 产品耗时包含配对 Draft，成本币种可显式配置。
- ReAct 内单独累计 LLM 请求等待墙钟与工具批次墙钟；失败模型请求仍计时，并行工具按 Agent 实际等待的整批时间统计，公开 Verifier 不混入工具批次列。
- Windows 原生命令日志按严格 UTF-8、宿主默认编码、GB18030 顺序容错解码，非零退出不会再因非法字节变成 Oracle 读取异常。

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

- GLM 完整 Pilot 未满足旧首轮门槛；DeepSeek 冻结三任务小样三组均为 100%，说明模型/API 组合对完成能力影响显著，但成功率天花板、零虚假完成下限和零修复机会使增量质量与修复价值不可判定；
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
- 新增覆盖 B 组不修复、首次 VerificationAttempt observer、六任务分层/命令白名单、隐藏 Oracle 与 Scope 独立判定、自动 Pilot 人工时间不可冒充为 0，以及 B/C digest 配对审计。
- 测试运行期间 Maven 完成主代码 224 个源文件、测试代码 151 个源文件编译。
- `mvn test -Pchange-spec-eval '-Dpaicli.changeSpecEval.enabled=false'`：Profile 能正确只选中 live test，并在禁用真实调用时安全 skipped。
- `safe-divider` 的前三次单轮 smoke 中 B/C 均在配对 Draft 阶段失败，错误依次暴露 dotted key、front matter 和字符串字段收到对象三类结构漂移；加入诊断能力后的第四次 smoke 生成有效配对 Draft，A/B 通过且 B/C digest 为 `23dabcd0e7344c49877b338b82c1d400670edb62d79c765a91de0b95d0d36096`，C 初始轮只调用 `glob_files`、修复轮未调用工具，两轮 `changedFiles=0`，最终失败。
- Draft 诊断能力的针对性回归覆盖精确字段路径、两次 attempt、敏感字段脱敏、8 KiB 截断和报告链接。
- 针对 C 的零改动轨迹新增确定性回归：repair input 必须携带 `workspace_changed_files_count: 0` 和实际工具操作要求；评测分类仅在 Spec Run 完整结束、隐藏任务失败且 changed-files=0 时产生 `NO_CHANGE_COMPLETION`。
- `glm / glm-4.6v-flashx`、`safe-divider`、3 次重复、seed `20260820` 的报告位于 `target/change-spec-eval/2026-08-21T14-05-30.562118800Z-20260820/report.md`：A/B/C 成功率为 66.67%/66.67%/33.33%，B/C digest 2/3（另一对 Draft 无效）；C 第 3 轮首次 changed-files=0，修复轮实际执行 `read_file`/`write_file` 后公开与隐藏验证通过。
- 同一报告的第 1 轮 Draft 诊断成功保存两次相同的无效输出，精确定位 `verifiers[0].include`；第 2 轮有效但弱化的 Draft 让行为 Criterion 只引用 `VT-SCOPE`，没有 command Verifier，导致公开 `PASSED`、零改动、隐藏失败。
- 双层语义资格修复后的 `safe-divider × 1轮` 报告位于 `target/change-spec-eval/2026-08-21T15-10-55.679296800Z-20260820/report.md`：A/B/C 均通过，B/C digest 1/1 一致；锁定 Spec 的两条 behavior Criterion 均引用允许命令 `mvn -q -DskipTests=false test`，C 首轮失败后一次修复通过。
- 首次完整 Pilot 报告位于 `target/change-spec-eval/2026-08-21T15-47-31.935897400Z-20260820/report.md`：`glm / glm-4.6v-flashx`、6 任务 × 3 组 × 2 次、seed `20260820`，A/B/C 成功率为 50.00%/41.67%/33.33%，Draft 有效 8/12，C 虚假完成率 20.00%。旧报告 TTA P50 为 349.33s/600s/600s，但 B/C 未包含 Draft 时长，且多数失败被截断到 600s，不能当作真实执行时长比较。
- 首次 Pilot 的 C 产品执行总墙钟约 1272.41s，其中两个长尾约占 60.83%；`login-retry-policy` 第 2 轮出现 50 次 LLM 调用、817,691 输入 Token 的两步工具循环，证明原有“连续相同调用”停滞检测不足。
- 按用户提供的 CNY 单价复算，首次 Pilot API 账单约 0.4229 元；旧报告未配置成本且默认美元展示，现已增加显式币种参数。
- 2026-08-22 的立即修复回归：共 33 tests、0 failure、0 error；`AgentBudgetTest` 12 项、`SpecDraftGeneratorTest` 6 项，`ChangeSpecEvaluationInfrastructureTest` 15 项中 2 项需显式 fixture 开关而跳过。随后显式启用 fixture 预检，15/15 通过公开测试、隐藏 Oracle、Scope 和生产命令执行路径。
- 扩大 Spec/ChangeSpec 免费回归为 75 tests、0 failure、0 error、3 个显式付费/fixture 评测项按预期 skipped，`BUILD SUCCESS`。
- 2026-08-23 的最终修复回归：ChangeSpec/预算/报告定向回归 35 tests、0 failure、0 error、2 skipped；扩大 Spec/ChangeSpec 回归 77 tests、0 failure、0 error、3 skipped。新增覆盖 `path_scope` 禁止携带 `path` 字段、scope Criterion 必须且只能引用唯一 `path_scope`、初始/修复 ReAct 失败原因持久化和失败后恢复指令。
- 同模型代表性小样报告位于 `target/change-spec-eval/2026-08-23T04-42-48.514572700Z-20260820/report.md`：B/C digest `3/3`，无 Draft 诊断和 `Input length=1`，单阶段最多 15 次调用，五项全量准入门槛均通过。
- 修复后的同模型完整 Pilot 报告位于 `target/change-spec-eval/2026-08-23T04-59-57.745377400Z-20260820/report.md`，SHA-256 `887d3a2bfa17296f7992a1cbfe8ba996a15de3ea4bf44baa281c9f0845ba6e55`：A/B/C 成功率 58.33%/41.67%/41.67%，Draft/digest 12/12，C 首次/最终成功率 33.33%/41.67%，C 虚假完成率 0%，成功 TTA P50 29.29s/46.18s/63.02s，失败惩罚数 5/12、7/12、7/12。旧报告中的固定 `600s` 是惩罚评分，不是实际失败耗时。
- DeepSeek 冻结代表性小样报告位于 `target/change-spec-eval/2026-08-23T06-23-26.095442900Z-20260820/report.md`，SHA-256 `BC027951BE2DDA53E31727DBC071751F66CB025700F3649731FB8A6617721184`：A/B/C 均为 100%，B/C digest `3/3`，Scope 越界和虚假完成均为 0，C 修复次数为 0；后续 36 次未运行。
- 评测结论口径、ReAct LLM/工具墙钟拆分与公开证据契约后的免费回归：`AgentBudgetTest,SpecDraftGeneratorTest,ChangeSpecEvaluationInfrastructureTest` 共 41 tests、0 failure、0 error、2 skipped；扩大 Spec/ChangeSpec 回归 83 tests、0 failure、0 error、3 skipped；显式 fixture 预检 21/21 通过六任务公开证据下限、隐藏 Oracle、Scope 和生产命令执行路径。`change-spec-eval` profile 以 `enabled=false` 验证为 1 skipped，未调用真实模型。
- 公开证据加固修改了六个 fixture 的 visible tests 和 Draft `minimum_tests` 资格下限；历史 GLM/DeepSeek 报告继续归档，但后续付费结果必须建立新版基线，不能把差异直接归因给模型。
- 任务扩展后的免费回归：`AgentBudgetTest,SpecDraftGeneratorTest,ChangeSpecEvaluationInfrastructureTest` 共 42 tests、0 failure、0 error、3 个慢 fixture 检查按预期 skipped；扩大 Spec/ChangeSpec 回归 84 tests、0 failure、0 error、4 个显式慢/付费项按预期 skipped。
- 完整本地 fixture 预检 22/22 通过，0 failure、0 error、0 skipped，耗时约 6 分钟；12 个参考实现分别通过公开测试和隐藏 Oracle，12 个确定性单点错误突变分别被生产公开 Verifier 拒绝。
- 本次目录扩展新增 `email-canonicalizer`、`inclusive-clamp`、`feature-flag-precedence`、`order-state-machine`、`secret-redactor` 和 `token-expiry-cross-file`，覆盖显式非目标、非法输入、兼容优先级、状态转换、安全脱敏和跨文件约束。默认完整评测规模因此变为 12×3×2=72 次产品运行；未运行任何真实模型。
- 去除 B/C 产品行重复计入的配对 Draft 后，本轮实际 API 用量约 1,108,295 输入 Token、99,509 输出 Token，按本次 CNY 单价估算约 0.3155 元。最长 ReAct 阶段为 15 次；没有旧 Pilot 的 50 次循环长尾。

### Quick 已知基线

- `mvn test -Pquick`：817 tests，10 failures，0 errors，3 skipped，`BUILD FAILURE`。
- 此前失败类和方法与第四条切片的 10 项基线完全一致：`ImageReferenceParserTest` 3 项，`MemoryManagerTest` 1 项，`PromptAssemblerTest` 1 项，`CodeIndexTest` 2 项，`CodeRetrieverTest` 1 项，`InlineRendererTest` 1 项，`CodeSearchGoldenSetTest` 1 项；本次 Windows ToolRegistry 修复后未重跑 Quick，针对性回归与评测 fixture 预检均通过。
- 已归因并修复 `ToolRegistryTest` 的 Windows 跨平台问题：`execute_command` 改用原生 `cmd.exe`，项目相对路径统一输出 `/`，超时清理子进程树；20 tests 全部通过。相关 `CommandGuardTest`、`ApprovalPolicyTest` 和 `SpecVerifierTest` 也通过。

## 5. 未解决问题

### 已验证的缺口

- DeepSeek 代表性跨模型小样已完成，但每任务只重复一次且任务出现成功率天花板，仍不能给纯模型原因分配可信百分比，也不能判定 ChangeSpec 的增量成功或修复价值；
- 12 个 fixture 的公开证据与单点突变均已通过免费预检，但仍缺少专门测量需求澄清价值的歧义任务，以及每组至少 3 次的新版真实模型基线；
- LLM 请求墙钟仍把服务端推理、网络传输和流式接收合并在一起，不能进一步归因到 provider 内部阶段；
- 完整 `total_human_effort` 尚未采集 Spec 确认、HITL、结果复核、返工和沟通时间；
- V1 仍只支持 revision 1，不支持运行中修改锁定需求或恢复/重跑既有 Spec；
- Quick 历史基线不是绿色状态，10 项既有失败归因仍未完成；

### 明确不在当前切片

- LLM Reviewer；
- Plan/Team 接入；
- 通用 Verifier SPI、工作流引擎或自动 run 清理策略。

## 6. 下一阶段任务

历史免费回归 → 原模型 3 个代表任务 × 1 次 → 原模型完整 36 次 → DeepSeek 冻结代表性 9 次小样均已完成并归档；此后 12 任务目录、公开证据契约和确定性突变的免费验证也已完成。下一阶段仅继续 Quick 历史失败归因和真人总人时实验设计；12×3×2 的新版基线或 12×3×3 的正式研究均未获授权，不得自动启动。详细边界见 [`docs/change-spec-pilot-remediation-checklist.md`](docs/change-spec-pilot-remediation-checklist.md)。

同模型代表性小样的完成证据：

1. B/C digest `3/3` 一致，Draft 最终有效；
2. 没有 Windows `Input length=1` 诊断污染；
3. 单阶段不超过 15 次 ReAct 迭代，没有重复两步循环长尾；
4. 当时报告明确区分产品耗时、成功 TTA、固定失败惩罚 TTA/失败数，并以 `CNY` 显示本次成本；新报告进一步拆分可信产品决策和失败实际耗时；
5. 小样通过后才运行的原模型 36 次已经归档；现在具备冻结条件后换模型的实验前提，但尚未形成跨模型结论。
