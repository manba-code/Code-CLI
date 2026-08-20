# ChangeSpec 任务状态

> 更新时间：2026-08-20
> 当前状态：前六条垂直切片已完成；`/spec` 已能从 Draft、锁定和 ReAct 执行走到首次验证、最多一次 Evidence 驱动修复、全量复验、Criterion Result、Human Criterion、最终 Verdict，并把一至两轮紧凑 Evidence 与最终 diff 持久化到 `.paicli/runs/<run-id>/`。尚未实现 A/B/C 评测。
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

## 3. 本切片修改文件

### 生产代码

- `src/main/java/com/paicli/spec/SpecRunResult.java`
- `src/main/java/com/paicli/spec/SpecRunStore.java`
- `src/main/java/com/paicli/spec/SpecRunCoordinator.java`
- `src/main/java/com/paicli/spec/SpecEvidenceFormatter.java`
- `src/main/java/com/paicli/cli/ChangeSpecCliFormatter.java`
- `src/main/java/com/paicli/cli/Main.java`

### 测试

- `src/test/java/com/paicli/cli/ChangeSpecCliFormatterTest.java`
- `src/test/java/com/paicli/spec/SpecRunCoordinatorTest.java`

### 文档

- `README.md`
- `AGENTS.md`
- `ROADMAP.md`
- `docs/change-spec-v1-rfc.md`
- `TASK_STATE.md`

第五条切片基线提交为 `6981d8b feat(spec): 完成 Criterion Result、Verdict 与运行结果持久化`；第六条切片修改当前位于工作树中，尚未提交。

## 4. 验证结果

### 本切片通过

- ChangeSpec/CLI/HITL/Agent 针对性组合：202 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS`。
- 新增覆盖修复成功、全量复验后通过、复验仍失败、修复抛异常、修复取消、修复篡改 Spec、`FAIL + HITL_DENIED` 不触发、两轮 Evidence ID/JSON、Prompt 脱敏和聚合指标。
- `mvn -DskipTests package`：`BUILD SUCCESS`，生成 shaded jar；只有既有 module-info、manifest 和资源重叠警告。
- `git diff --check`：未发现空白错误；Git 仅提示部分文件后续可能由 LF 转为 CRLF。

### Quick 已知基线

- `mvn test -Pquick`：811 tests，10 failures，0 errors，1 skipped，`BUILD FAILURE`。
- 失败类和方法与第四条切片的 10 项基线完全一致：`ImageReferenceParserTest` 3 项，`MemoryManagerTest` 1 项，`PromptAssemblerTest` 1 项，`CodeIndexTest` 2 项，`CodeRetrieverTest` 1 项，`InlineRendererTest` 1 项，`CodeSearchGoldenSetTest` 1 项；本切片新增测试全部通过，没有新增 Quick 失败。
- 额外显式运行 `ToolRegistryTest` 时，19 项中有 5 项外部命令/glob/grep/超时测试失败；当前切片没有修改 `tool/`，该现象未在本切片内归因，且 quick profile 本来就排除 `ToolRegistryTest`。

## 5. 未解决问题

### 已验证的缺口

- 尚无 A/B/C 任务集、指标报告和效率结论；
- 完整 `human_intervention_time` 尚未单独汇总 Spec 确认、HITL 等待和 Human Criterion 时间；
- V1 仍只支持 revision 1，不支持运行中修改锁定需求或恢复/重跑既有 Spec；
- Quick 历史基线不是绿色状态，10 项既有失败归因仍未完成；
- 独立 `ToolRegistryTest` 的 5 项当前环境失败尚未归因。

### 明确不在当前切片

- A/B/C 评测；
- LLM Reviewer；
- Plan/Team 接入；
- 通用 Verifier SPI、工作流引擎或自动 run 清理策略。

## 6. 下一阶段任务

下一阶段是 RFC 的第七条切片：A/B/C 评测与指标报告。

最小完成边界：

1. 建立至少 6 个分层任务，统一初始 workspace、模型、时间限制、隐藏 Oracle 和最终验证方式；
2. A 组使用普通 ReAct，B 组使用 ChangeSpec 但关闭 Evidence Gate 修复，C 组使用当前 ChangeSpec + 一次修复；
3. 每组至少重复 2 次，记录成功率、虚假完成率、首次通过率、`time_to_accepted_change`、人工介入、Token 和成本；
4. 公开 Verifier 与隐藏 Oracle 分离，不能把隐藏测试暴露给 Agent；
5. 输出首轮指标报告，只按 RFC 门槛陈述数据，不在小样本上宣称普遍提效。
