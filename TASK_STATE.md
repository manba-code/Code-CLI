# ChangeSpec 任务状态

> 更新时间：2026-08-20
> 当前状态：前四条垂直切片已完成；`/spec` 已能生成、校验、确认并锁定 ChangeSpec，通过现有 ReAct 执行，然后采集本轮 workspace 变化并运行 Scope、command 和 JUnit Verifier。当前只展示 Verifier Result，尚无 Criterion Result、持久化 Evidence 或最终 Verdict。
> 事实来源：当前工作区代码、`git status`、`git diff`、Maven/Surefire 测试结果，以及 `docs/change-spec-v1-rfc.md`。不能由这些材料证明的内容单独标为“尚未确认”。

## 1. 当前目标

### 已验证的事实

PaiCLI 的 ChangeSpec 是可选的 Spec-Driven Code Change 契约层：把自然语言代码需求转换为可锁定、可执行、可验收的 ChangeSpec，并最终通过最小必要证据判断变更是否满足要求。目标是缩短“提出需求 → 获得可信验收结果”的时间，而不是建设需求管理、日志或通用工作流平台。

当前已交付四条切片：

1. ChangeSpec 领域模型、YAML Front Matter 解析、结构校验和稳定摘要；
2. `/spec <需求>` Draft 生成、一次结构纠错、确认/补充/取消交互；
3. 确认后的完整文档锁定持久化，最终确认需求与锁定 YAML/digest 注入现有 ReAct；
4. 独立 workspace baseline、changed-files/final-diff、`path_scope`、command 和新鲜 JUnit XML 验证。

当前链路是：

```text
需求 → Draft → 用户确认 → 稳定编码 → 不可覆盖锁定 → 回读身份校验
    → workspace baseline → Side-Git + ESC 取消包装 → 现有 Agent.run()
    → command/JUnit Verifier → 最终 workspace diff + path_scope → Verifier Result
```

### 尚未确认

- ChangeSpec 是否实际提升开发效率，尚无 A/B/C 实验数据支持。
- 当前数据模型和 Draft 质量是否覆盖真实企业代码变更的主要场景，尚未经过真实模型任务集验证。
- Verifier Result 尚未映射为 Criterion Result，也没有 Evidence 持久化和最终 Verdict。

## 2. 用户明确提出的强约束

### 已验证的事实

- 一切设计以开发效率为中心，避免为了流程完整而增加无收益模块。
- 小型、低风险、目标明确的任务继续使用普通 ReAct，不强制 Spec。
- Evidence 只保留验收必需的可观察事实，不保存思维过程或完整工具日志。
- V1 暂缓 LLM Reviewer，优先确定性验证，无法自动判断时再交给人。
- Spec 是现有 Agent 的可选契约层，不是新的 Coding Agent；V1 只接入 ReAct。
- Spec 确认不等于工具授权，Verifier 命令仍单独经过 HITL 和 CommandGuard。
- 每次只推进 RFC 中一条垂直切片；第四条切片不提前实现 Criterion Result、Verdict、结果持久化或自动修复。
- `I` 输入的补充属于最终确认需求，必须与锁定契约一起进入执行输入。
- 既有 Quick 回归失败不阻塞当前切片，但必须记录基线且不能新增失败。

## 3. 已完成内容

### 第一至三条切片

- 不可变 ChangeSpec 领域模型、严格 YAML Codec、SHA-256 digest 和稳定重编码。
- 无工具 Draft Generator、最多一次结构纠错、Enter/I/ESC 确认交互。
- 不可覆盖锁定、写前/写后身份校验、最终确认需求注入、现有 ReAct/Side-Git/ESC/HITL/策略层复用。
- 锁定文件在 ReAct 取消或失败后保留，revision 固定为 1。

### 第四条切片

- `WorkspaceChangeTracker` 在锁定后、ReAct 前采集内容摘要 baseline，不依赖 Git HEAD 或 Side-Git 是否启用。
- 既有脏文件未继续变化不计入本轮；本轮继续修改、增加和删除会进入 changed files，rename 按删除+新增处理。
- tracker 固定排除 `.git`、`.paicli/specs`、`.paicli/runs` 和常见构建产物目录，但不笼统忽略全部 Git ignored 文件。
- 生成相对 baseline 的统一 final diff；二进制/大文件只记录变化标记，整体 diff 有大小上限和截断标志。
- 每份 Spec 必须有且仅有一个 `path_scope` Verifier，以及一个仅引用它的 deterministic `kind: scope` Criterion；未引用 Verifier 会被结构校验拒绝。
- Scope glob 使用项目相对 `/` 路径、大小写敏感的 `*`/`**`/`?` 匹配；`exclude` 优先于 `include`。
- command Verifier 按 Spec 声明顺序串行执行，返回结构化 `COMPLETED / TIMED_OUT / START_ERROR / CANCELED / POLICY_DENIED / HITL_DENIED`，不再从展示文本反解析退出码；HITL/策略拒绝或用户取消会停止后续 command 并将未运行项记为 `ERROR`。
- 普通 `execute_command` 与 Verifier 共用同一结构化命令执行核心；Verifier 入口仍执行 CommandGuard、危险操作审计和独立 HITL。
- HITL 不允许修改锁定 Verifier 命令；用户只能批准原命令、批量批准或拒绝/跳过。
- JUnit 只采信本次命令新生成或内容变化的 XML，使用禁用 DOCTYPE/外部实体的安全解析器；历史报告不能误放行。
- `minimum_tests` 使用 `tests - skipped`；exit code 不符、测试不足、failures/errors 非零为 `FAIL`，启动失败、超时、拒绝或 XML 无法形成有效结果为 `ERROR`。
- 所有 command 完成后重新采集最终 workspace，再运行 `path_scope`，因此命令导致的源码变化也受 Scope 检查。
- ReAct 正常结束才运行 Verifier；ESC 取消或执行异常仍采集 changed-files/diff，但不运行 Verifier。
- CLI 展示 Verifier `PASS / FAIL / ERROR`、原因和 changed files，并明确这些结果尚不是 Criterion Result 或最终 Verdict。

## 4. 本切片修改文件

### 生产代码

- `src/main/java/com/paicli/spec/WorkspaceChangeTracker.java`
- `src/main/java/com/paicli/spec/SpecVerifier.java`
- `src/main/java/com/paicli/spec/ChangeSpecCodec.java`
- `src/main/java/com/paicli/spec/SpecRunCoordinator.java`
- `src/main/java/com/paicli/tool/CommandExecutionResult.java`
- `src/main/java/com/paicli/tool/ToolRegistry.java`
- `src/main/java/com/paicli/hitl/HitlToolRegistry.java`
- `src/main/java/com/paicli/cli/Main.java`
- `src/main/resources/prompts/modes/spec-draft.md`

### 测试

- `src/test/java/com/paicli/spec/WorkspaceChangeTrackerTest.java`
- `src/test/java/com/paicli/spec/SpecVerifierTest.java`
- `src/test/java/com/paicli/spec/ChangeSpecCodecTest.java`
- `src/test/java/com/paicli/spec/SpecDraftGeneratorTest.java`
- `src/test/java/com/paicli/spec/SpecRunCoordinatorTest.java`
- `src/test/java/com/paicli/tool/ToolRegistryTest.java`
- `src/test/java/com/paicli/hitl/HitlToolRegistryTest.java`

### 文档

- `README.md`
- `AGENTS.md`
- `ROADMAP.md`
- `docs/change-spec-v1-rfc.md`
- `TASK_STATE.md`

第三条切片之前的基线提交为 `859d6a4cc23149a6cade2a2a046e192a3e1b7110`；第三、四条切片修改当前仍位于工作树中，尚未提交。

## 5. 关键设计决策及原因

1. **Baseline 独立于 Git**：验收口径必须区分运行前脏文件与本轮增量，不能把 Git HEAD diff 误当成 Agent diff。
2. **Scope 是必备契约**：如果 bounded Scope 没有对应 Criterion/Verifier，后续 Verdict 无法证明越界，因此在 Draft 阶段直接拒绝。
3. **命令结果结构化**：Verifier 直接消费退出码和状态，避免解析面向 LLM 的中文文本。
4. **授权不随 Spec 确认扩张**：锁定命令仍需 HITL；为保持 digest 对应关系，Verifier 不接受审批时修改参数。
5. **只采信新鲜 JUnit**：不删除用户报告，也不让上一次测试结果为本轮提供虚假证据。
6. **Scope 最后执行**：command Verifier 可能产生源码或配置变化，最终 Scope 必须覆盖这些副作用。
7. **中断仍采集 workspace**：取消或执行异常不应运行验收命令，但取消前已经发生的文件变化不能丢失。
8. **保持切片边界**：当前只返回 Verifier Result；不提前建立结果 JSON、Criterion 归约、Verdict 或修复循环。

## 6. 当前测试结果

### 本切片已验证

- ChangeSpec/Verifier/Workspace/CLI/HITL 针对性组合：154 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS`。
- `mvn -DskipTests package`：`BUILD SUCCESS`，生成 shaded jar；Shade 仍只有既有 module-info、manifest 和资源重叠警告。
- `mvn test -Pquick`：797 tests，10 failures，0 errors，1 skipped，`BUILD FAILURE`。
- `git diff --check`：未发现空白错误；Git 仅提示部分文件后续可能由 LF 转为 CRLF。

### 已知基线

上一切片的 `mvn test -Pquick` 为 786 tests、10 failures、0 errors、1 skipped。本切片测试总数增至 797，10 项失败的测试类和方法与上一切片完全一致：`ImageReferenceParserTest` 3 项，`MemoryManagerTest` 1 项，`PromptAssemblerTest` 1 项，`CodeIndexTest` 2 项，`CodeRetrieverTest` 1 项，`InlineRendererTest` 1 项，`CodeSearchGoldenSetTest` 1 项；没有新增失败。

## 7. 未解决问题

### 已验证的缺口

- 尚无 Criterion Result 和固定优先级 Verdict 归约。
- 尚未把 Verifier Result、changed files、diff、耗时与 LLM 使用量紧凑持久化到 `.paicli/runs`。
- 尚无 Human Criterion 交互。
- 尚无失败证据驱动的一次自动修复。
- 尚无 A/B/C 任务集、指标采集和效率结论。
- V1 仍只支持 revision 1，不支持运行中修改锁定需求或恢复既有 Spec 执行。
- Quick 回归历史基线不是绿色状态，失败归因仍未完成。

### 尚未确认

- 真实模型 Draft 的稳定性和可读性。
- Human Criterion 的真实使用频率和人工耗时。
- Evidence 紧凑持久化格式与保留周期是否满足实际排错需要。
- 是否需要在后续提供锁定 Spec 的显式重跑/恢复入口；V1 当前不实现。

## 8. 下一阶段任务

### 已确定但尚未开始

下一阶段是 RFC 的第五条切片：Criterion Result、Verdict 和紧凑运行结果持久化。仍不提前实现一次证据驱动修复或 A/B/C 评测。

建议的最小完成边界：

1. 将每个 deterministic Criterion 引用的 Verifier Result 归约为 `PASS / FAIL / INCONCLUSIVE / NOT_RUN`，保留证据引用和原因。
2. 在确定性检查完成后处理 Human Criterion，并记录人工选择与耗时。
3. 按 RFC 固定优先级生成 `SPEC_INVALID / FAILED / INCOMPLETE / NEEDS_HUMAN / PASSED`。
4. 保存 `.paicli/runs/<run-id>/result.json` 与 `change.diff`，限制命令输出摘要，不保存思维过程或完整工具日志。
5. 保证 specId/revision/digest 在锁定 Spec、运行结果和 diff 元数据中一致。
6. CLI 展示最终 Verdict 和逐条 Criterion Result，并覆盖失败、错误、未运行、人工跳过和全部通过分支测试。
7. 同步 README、AGENTS、ROADMAP、RFC 和本状态文件。

### 暂不执行

一次证据驱动修复和 A/B/C 评测属于后续切片。只有用户明确要求继续后，才开始第五条切片。
