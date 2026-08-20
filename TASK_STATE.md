# ChangeSpec 任务状态

> 更新时间：2026-08-20
> 当前状态：前五条垂直切片已完成；`/spec` 已能从 Draft、锁定和 ReAct 执行走到 Verifier Result、Criterion Result、Human Criterion、最终 Verdict，并把紧凑 Evidence 与 diff 持久化到 `.paicli/runs/<run-id>/`。尚未实现一次证据驱动修复和 A/B/C 评测。
> 事实来源：当前工作区代码、`git status`、`git diff`、Maven/Surefire 测试结果，以及 `docs/change-spec-v1-rfc.md`。不能由这些材料证明的内容单独标为“尚未确认”。

## 1. 当前目标

PaiCLI 的 ChangeSpec 是可选的 Spec-Driven Code Change 契约层：把自然语言代码需求转换为可锁定、可执行、可验收的 ChangeSpec，并通过最小必要证据判断变更是否满足要求。目标是缩短“提出需求 → 获得可信验收结果”的时间，而不是建设需求管理、日志或通用工作流平台。

当前已交付五条切片：

1. ChangeSpec 领域模型、YAML Front Matter 解析、结构校验和稳定 digest；
2. `/spec <需求>` Draft 生成、一次结构纠错、确认/补充/取消交互；
3. 完整文档锁定持久化，最终确认需求与锁定 YAML/digest 注入现有 ReAct；
4. 独立 workspace baseline、changed-files/final-diff、`path_scope`、command 和新鲜 JUnit XML 验证；
5. Criterion Result、Human Criterion、固定优先级 Verdict、紧凑运行结果和单次运行指标持久化。

当前链路：

```text
需求 → Draft → 用户确认 → 不可覆盖锁定 → workspace baseline
    → Side-Git + ESC 取消包装 → 现有 ReAct
    → command/JUnit Verifier → 最终 workspace diff + path_scope
    → deterministic Criterion 归约 → Human Criterion（必要时）
    → Verdict → result.json + change.diff
```

尚未确认：

- ChangeSpec 是否实际提升开发效率，尚无 A/B/C 实验数据支持；
- 当前数据模型和真实模型 Draft 质量是否覆盖主要企业代码变更场景；
- Human Criterion 的真实使用频率、人工耗时和当前 Evidence 保留格式是否满足长期排错需要。

## 2. 第五条切片已完成内容

### 结果接口与生命周期

- 新增独立 `SpecRunResult`，作为 CLI 和测试跨越 ChangeSpec 模块的主要结果接口；包含 run/spec 身份、生命周期状态、Verifier/Criterion/Human Evidence、Verdict、workspace、指标和产物状态。
- 确认前取消不创建 run 或 Verdict；锁定后的准备失败、ReAct 取消/失败和验证流程异常会生成 `INCOMPLETE`，保存当时 workspace Evidence。
- ReAct 后再次回读锁定文件；文件被修改、删除或无法解析时生成 `SPEC_INVALID`，不运行 Verifier。
- 持久化失败不改写已由 Evidence 算出的验收 Verdict，但会通过 artifacts 状态和 CLI 明确报错。

### Criterion 与 Verdict

- deterministic Criterion 引用多个 Verifier 时按 `FAIL > INCONCLUSIVE > PASS` 聚合：任一 `FAIL` 即失败；否则任一 `ERROR`、缺失或未运行即 `INCONCLUSIVE`；全部通过才 `PASS`。
- 同一 Verifier 每次 run 只执行一次，可被多条 Criterion 共享；Evidence ID 使用 `verifier:<verifierId>`。
- 只有所有 deterministic Criterion 通过后才进入 Human Criterion；CLI 使用 `P` 通过、`F` 拒绝、`S/ESC` 跳过。拒绝或跳过后停止后续人工判断。
- Human Evidence ID 使用 `human:<criterionId>`；选择与每条交互耗时会持久化。
- Verdict 按 `SPEC_INVALID → FAILED → INCOMPLETE → NEEDS_HUMAN → PASSED` 固定优先级归约；Agent 自述不能生成 PASS。

### 紧凑持久化与指标

- 每次锁定后的运行保存 `.paicli/runs/<run-id>/result.json` 和 `change.diff`；产物不可覆盖，`result.json` 最后原子写入。
- `result.json` schema 为 `paicli/spec-run-result/v1`，保持 specId/revision/digest 与锁定 Spec 一致。
- PASS 命令不保存 stdout；FAIL/ERROR 只保存 ANSI 清理、敏感字段脱敏、最多 8 KiB 的头尾摘要和截断标志，不保存思维过程或完整工具历史。
- `change.diff` 头部记录 runId/specId/revision/specDigest，正文继续使用 tracker 的 256 KiB 上限和截断标志；V1 不自动清理历史 run。
- 记录 `specGenerationMs`、`specConfirmationMs`、`reactExecutionMs`、`verificationMs`、`humanCriterionMs`、`totalMs`，以及 Draft/ReAct 各自和合计的 LLM calls/input/output/cached tokens。
- `humanCriterionMs` 只代表 Human Criterion；当前没有把它冒充包含 HITL 等待的完整 `human_intervention_time`。

## 3. 本切片修改文件

### 生产代码

- `src/main/java/com/paicli/spec/SpecRunResult.java`
- `src/main/java/com/paicli/spec/SpecRunStore.java`
- `src/main/java/com/paicli/spec/SpecRunCoordinator.java`
- `src/main/java/com/paicli/spec/SpecDraftGenerator.java`
- `src/main/java/com/paicli/spec/SpecDraftSession.java`
- `src/main/java/com/paicli/agent/Agent.java`
- `src/main/java/com/paicli/cli/ChangeSpecCliFormatter.java`
- `src/main/java/com/paicli/cli/Main.java`

### 测试

- `src/test/java/com/paicli/agent/AgentRunResultTest.java`
- `src/test/java/com/paicli/cli/ChangeSpecCliFormatterTest.java`
- `src/test/java/com/paicli/spec/SpecDraftGeneratorTest.java`
- `src/test/java/com/paicli/spec/SpecDraftSessionTest.java`
- `src/test/java/com/paicli/spec/SpecRunCoordinatorTest.java`

### 文档

- `README.md`
- `AGENTS.md`
- `ROADMAP.md`
- `docs/change-spec-v1-rfc.md`
- `TASK_STATE.md`

第四条切片基线提交为 `21c410f feat(spec): 完成 Workspace 与确定性 Verifier 切片`；第五条切片修改当前位于工作树中，尚未提交。

## 4. 验证结果

### 本切片通过

- ChangeSpec/CLI/HITL/Agent 指标针对性组合：111 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS`。
- 覆盖多 Verifier `FAIL + ERROR` 优先级、Verifier ERROR、Human 通过/拒绝/跳过、锁定 Spec 篡改、ReAct 取消/失败、持久化失败、命令摘要脱敏截断、指标与 CLI 渲染。
- `mvn -DskipTests package`：`BUILD SUCCESS`，生成 shaded jar；只有既有 module-info、manifest 和资源重叠警告。
- `git diff --check`：未发现空白错误；Git 仅提示部分文件后续可能由 LF 转为 CRLF。

### Quick 已知基线

- `mvn test -Pquick`：806 tests，10 failures，0 errors，1 skipped，`BUILD FAILURE`。
- 失败类和方法与第四条切片的 10 项基线完全一致：`ImageReferenceParserTest` 3 项，`MemoryManagerTest` 1 项，`PromptAssemblerTest` 1 项，`CodeIndexTest` 2 项，`CodeRetrieverTest` 1 项，`InlineRendererTest` 1 项，`CodeSearchGoldenSetTest` 1 项；本切片新增测试全部通过，没有新增 Quick 失败。
- 额外显式运行 `ToolRegistryTest` 时，19 项中有 5 项外部命令/glob/grep/超时测试失败；当前切片没有修改 `tool/`，该现象未在本切片内归因，且 quick profile 本来就排除 `ToolRegistryTest`。

## 5. 未解决问题

### 已验证的缺口

- 尚无 deterministic `FAIL` 后的一次 Evidence 驱动修复；
- 尚无 A/B/C 任务集、指标报告和效率结论；
- 完整 `human_intervention_time` 尚未单独汇总 Spec 确认、HITL 等待和 Human Criterion 时间；
- V1 仍只支持 revision 1，不支持运行中修改锁定需求或恢复/重跑既有 Spec；
- Quick 历史基线不是绿色状态，10 项既有失败归因仍未完成；
- 独立 `ToolRegistryTest` 的 5 项当前环境失败尚未归因。

### 明确不在当前切片

- 自动修复；
- A/B/C 评测；
- LLM Reviewer；
- Plan/Team 接入；
- 通用 Verifier SPI、工作流引擎或自动 run 清理策略。

## 6. 下一阶段任务

下一阶段是 RFC 的第六条切片：确定性失败后最多一次 Evidence 驱动修复。

最小完成边界：

1. 仅在首次验证产生 deterministic `FAIL` 时触发；`SPEC_INVALID`、Verifier `ERROR`、HITL/策略拒绝和 Human 未判断不触发；
2. 向同一 ReAct 会话追加失败 Criterion、对应 Evidence、原因和原 spec digest，禁止修改锁定 Spec；
3. 最多修复一次，之后重新运行全部 Verifier，而不是只重跑失败项；
4. 在 result.json 中记录 `repairCount`、两轮 Evidence 和最终 Verdict，同时保持紧凑输出；
5. 覆盖修复成功、再次失败、修复执行异常和不应触发修复的分支；
6. 同步 README、AGENTS、ROADMAP、RFC 和本状态文件。
