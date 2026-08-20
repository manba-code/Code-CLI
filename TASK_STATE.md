# ChangeSpec 任务状态

> 更新时间：2026-08-19  
> 当前状态：前两条垂直切片已完成；`/spec` 已能生成、校验并确认 Draft，但尚未保存或执行。  
> 事实来源：当前工作区代码、`git status`、`git diff`、Maven/Surefire 测试结果，以及仓库内 `docs/change-spec-v1-rfc.md`。对不能由这些材料证明的内容单独标为“尚未确认”。

## 1. 当前目标

### 已验证的事实

PaiCLI 的新增方向是可选的 Spec-Driven Code Change 工作流：把自然语言代码需求转换成可锁定、可执行、可验收的 ChangeSpec，并通过最小必要证据判断变更是否满足要求。目标是缩短“提出需求 → 获得可信验收结果”的时间，而不是建设需求管理平台、日志平台或通用 SDD 工具。

当前已交付前两条切片：ChangeSpec 领域模型、YAML Front Matter 解析、结构校验和稳定摘要，以及 `/spec <需求>` 的 Draft 生成、一次结构纠错和确认交互。尚未形成从锁定 Spec 到执行、验证、Verdict 的运行时闭环。

### 尚未确认

- ChangeSpec 是否能实际提升开发效率，尚无 A/B/C 实验数据支持。
- 当前数据模型是否覆盖真实企业代码变更的主要场景，尚未经过任务集验证。

## 2. 用户明确提出的强约束

### 已验证的事实

- 一切设计以开发效率为中心；不能加入“看起来完整或漂亮、实际降低效率”的模块。
- 避免过度设计和过度细化，不能偏离“代码变更提效与可信验收”这一重心。
- 小型、低风险、目标明确的任务可以继续使用普通 ReAct，不强制 Spec；Spec 必须用于收益能够覆盖额外成本的场景。
- V1 最终要形成可运行闭环，不能只停留在 RFC、Schema 或文档层。
- Evidence 只保留完成验收所需的可观察事实，不建设全量日志平台，不把 Agent 自述或思维过程当证据。
- V1 暂缓 LLM Reviewer；优先确定性验证，无法自动判定时再交给人。多 Reviewer 对抗/投票只有在后续指标证明收益时才考虑。
- 必须从 V1 开始记录效率和质量指标；没有对照实验数据，不宣称提效。
- Spec 是现有 Agent 的可选契约层，不是另造一个通用 Agent。V1 只接入 ReAct，不同时接入 `/plan` 和 `/team`。
- 每次只推进 RFC 中明确的一条切片；第二条切片不得提前保存 Spec、执行 ReAct 或实现 Evidence/Verdict。

### 尚未确认

- “复杂到什么程度才建议使用 Spec”还没有用任务规模、风险等级或耗时阈值量化。
- Human 验收在真实任务中的占比和时间成本尚无数据。

## 3. 已完成内容

### 已验证的事实

- 新增不可变的 `ChangeSpec` 领域模型，包含 Intent、Scope、Acceptance、Oracle 和 Verifier 定义。
- 新增 `ChangeSpecCodec`，支持解析 `YAML Front Matter + Markdown` 文档。
- YAML 是机器契约事实源；Markdown 仅作为人类可读补充，不参与摘要。
- 实现 V1 结构校验，包括协议版本、必填字段、Scope 路径、原子验收项、Oracle/Verifier 引用、重复 ID 和命令验证配置。
- V1 当前只接受 `deterministic` 与 `human` Oracle，以及 `path_scope` 与 `command` Verifier 定义。
- 使用规范化机器模型计算 SHA-256 `specDigest`；仅修改 Markdown 不改变摘要，修改机器契约会改变摘要。
- 未知字段和重复 YAML Key 会失败，避免拼写错误被静默忽略。
- 新增统一的 `ChangeSpecValidationException`，向后续入口暴露结构化错误列表。
- 新增 13 个 `ChangeSpecCodecTest` 测试用例并通过。
- 已形成 RFC、术语上下文和用例图文档草案。
- 新增 `/spec <代码变更需求>` 命令；单独输入 `/spec` 只显示用法，不进入隐藏模式。
- 新增无工具的 `SpecDraftGenerator`，使用当前模型、专用 Prompt、Project Context 和用户显式本地引用生成 Draft。
- Draft 必须通过现有 Codec 才能展示；第一次结构错误会反馈给同一模型并最多重生成一次。
- 新增 Draft 会话循环：Enter 确认、I 补充后重生成、ESC 或 `/cancel` 取消。
- 新增紧凑 CLI 摘要，只展示 Goal、Non-goals、Scope、Acceptance Criteria 和 Verifiers。
- 确认和取消均不会触发 Agent、保存 Spec 或修改代码。

### 尚未确认

- 当前实现仍不是完整 Spec-to-Evidence 闭环，只完成了闭环前端的两条基础切片。
- Draft 生成只通过 Mock LLM 做了确定性测试，尚未通过真实模型和真实用户需求验证稳定性与可读性。

## 4. 修改过的文件

### 已验证的 ChangeSpec 切片文件

- `pom.xml`：增加 `jackson-dataformat-yaml:2.16.0` 依赖；当前 tracked diff 为新增 7 行。
- `src/main/java/com/paicli/spec/ChangeSpec.java`：领域模型。
- `src/main/java/com/paicli/spec/ChangeSpecDocument.java`：机器契约、Markdown 正文和摘要的组合对象。
- `src/main/java/com/paicli/spec/ChangeSpecCodec.java`：解析、校验、规范化和摘要计算。
- `src/main/java/com/paicli/spec/ChangeSpecValidationException.java`：统一校验异常。
- `src/test/java/com/paicli/spec/ChangeSpecCodecTest.java`：13 个针对性测试。
- `src/main/java/com/paicli/spec/SpecDraftGenerator.java`：无工具 Draft 生成和一次结构纠错。
- `src/main/java/com/paicli/spec/SpecDraftSession.java`：确认、补充重生成和取消的会话状态。
- `src/main/java/com/paicli/cli/SpecReviewInputParser.java`：确认输入解析。
- `src/main/java/com/paicli/cli/ChangeSpecCliFormatter.java`：单屏 Draft 摘要。
- `src/main/resources/prompts/modes/spec-draft.md`：ChangeSpec 专用生成 Prompt。
- `src/main/java/com/paicli/cli/CliCommandParser.java`：新增 `/spec` 命令解析。
- `src/main/java/com/paicli/cli/Main.java`：接入 Draft 生成、Project Context、本地引用和终端确认交互。
- `src/test/java/com/paicli/spec/SpecDraftGeneratorTest.java`：生成、无工具调用和一次纠错测试。
- `src/test/java/com/paicli/spec/SpecDraftSessionTest.java`：确认、补充和取消测试。
- `src/test/java/com/paicli/cli/SpecReviewInputParserTest.java`：确认输入测试。
- `src/test/java/com/paicli/cli/ChangeSpecCliFormatterTest.java`：单屏摘要字段测试。
- `src/test/java/com/paicli/cli/CliCommandParserTest.java`：新增 `/spec` 解析测试。
- `src/test/java/com/paicli/cli/MainInputNormalizationTest.java`：补全列表包含 `/spec` 的回归测试。
- `README.md`、`AGENTS.md`：同步当前命令和阶段边界。
- `docs/change-spec-v1-rfc.md`：RFC 状态已更新为 `Accepted`，前两条切片标记完成。
- `docs/change-spec-v1-usecase.svg`：角色与流程用例图。
- `CONTEXT.md`：ChangeSpec 领域术语。
- `TASK_STATE.md`：本状态记录。

当前 Git 已跟踪且有文本修改的文件包括 `AGENTS.md`、`README.md`、`pom.xml`、`CliCommandParser.java`、`Main.java`、`CliCommandParserTest.java` 和 `MainInputNormalizationTest.java`；其余本节列出的 ChangeSpec 新文件与文档当前尚未被 Git 跟踪。

### 工作树中存在但不应算作本切片内容改动

- `src/main/java/com/paicli/agent/SubAgent.java` 被 `git status` 标记为 `M`，但当前文件哈希与 `HEAD` 均为 `f9455e24955bedbab2abfcf85118566e5dc6747e`，`git diff` 无文本差异。

### 尚未确认

- `SubAgent.java` 的 `M` 状态由文件时间戳、Git stat cache、换行处理还是其他环境因素导致，尚未确认。
- 当前新增文件尚未提交，最终提交边界和提交方式尚未确定。

## 5. 关键设计决策及原因

### 已实现并验证

1. **YAML Front Matter + Markdown，YAML 为唯一机器事实源**：兼顾机器校验和人类阅读，避免维护两套互相漂移的契约。
2. **使用 Jackson YAML，不复用项目内简化的 Front Matter 手写解析器**：ChangeSpec 存在嵌套对象、列表、枚举和严格字段要求，成熟解析器能减少重复实现和边界错误。
3. **不可变领域模型**：记录构造时复制列表，避免锁定后的契约被调用方修改。
4. **摘要只绑定规范化机器模型**：保证格式、换行和 Markdown 说明变化不影响契约身份，同时机器字段变化一定影响摘要。
5. **严格拒绝未知字段、重复 Key 和无效引用**：尽早暴露拼写及契约错误，避免错误配置进入执行阶段。
6. **统一校验异常**：后续 `/spec` 入口可以把所有解析/校验失败统一归约为 `SPEC_INVALID`，无需理解底层 YAML 异常。
7. **当前模型 + 专用 Prompt**：不增加新模型配置；通过独立 Prompt 限制输出结构、禁止工具调用和未经证实的命令。
8. **最多一次结构纠错**：提高 Draft 首次可用性，同时限制延迟与 Token 成本，避免无限重生成。
9. **确认流程独立于执行**：本切片只返回已确认 Draft，避免在锁定、持久化和 ReAct 注入尚未完成时产生半闭环执行。

### RFC 已确定、尚未实现

1. **Spec 是可选入口，普通 ReAct 保留**：小任务不承担 Spec 成本；复杂、跨文件、高风险或有明确验收边界的任务再使用 `/spec`。
2. **V1 只接 ReAct**：先以最短路径验证 Spec 的增量价值，避免同时改动三条执行路径导致周期和归因复杂化。
3. **V1 只做确定性 Verifier 与 Human 兜底**：先验证低成本、可复现的证据闭环；Reviewer 需要额外成本和误判评测，因此暂缓。
4. **最小 Evidence**：只采集 Verdict 所需事实，降低存储、实现和理解成本。
5. **一次自动修复上限**：V1 计划只允许一次基于失败证据的修复，避免无限重试并便于比较成本。

## 6. 已否决方案及原因

### 已验证的设计结论

- **完整复刻 Spec Kit**：否决。其命令链、模板和目录体系超出单次代码变更闭环，增加流程成本。
- **把项目扩成 PRD、Roadmap、Issue、PR 或团队权限平台**：否决。偏离代码变更提效目标。
- **所有需求强制走 Spec**：否决。小任务的建约、确认和验证成本可能高于收益。
- **将 Spec 做成新的独立通用 Coding Agent**：否决。Spec 应约束和验收现有执行路径，不与 Claude Code/Codex 比拼通用生成能力。
- **V1 同时接入 ReAct、Plan 和 Multi-Agent**：否决。改动面过大，难以判断收益来自 Spec 还是编排方式。
- **记录完整工具历史、思维过程或 Agent 自述作为 Evidence**：否决。这些内容既增加噪声，也不能证明验收条件成立。
- **V1 默认启用多 Reviewer 对抗、旁观投票**：否决。成本和延迟确定增加，但相对确定性检查与 Human 的收益尚无指标证明。
- **允许 non-blocking Acceptance**：否决。会模糊任务成功定义；非强制目标应属于 Preference，而 V1 为缩小范围暂不实现 Preference。
- **把 Plan、Tasks、运行状态和 Evidence 写进 ChangeSpec**：否决。它们属于某次执行，不属于锁定的需求契约。

### 尚未确认

- 多 Reviewer 在高语义、低可测试任务中是否可能降低总体人工成本，需要后续独立实验，当前不能认定为永久无价值。

## 7. 当前测试结果

### 已验证的事实

| 检查 | 结果 |
|---|---|
| `mvn test -Dtest=ChangeSpecCodecTest -DskipTests=false` | 13 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS` |
| `mvn test '-Dtest=CliCommandParserTest,MainInputNormalizationTest,SpecReviewInputParserTest,ChangeSpecCliFormatterTest,ChangeSpecCodecTest,SpecDraftGeneratorTest,SpecDraftSessionTest' -DskipTests=false` | 100 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS` |
| `mvn -DskipTests package` | `BUILD SUCCESS`，生成 shaded jar |
| `mvn test -Pquick` | 777 tests，10 failures，0 errors，1 skipped，`BUILD FAILURE` |
| `git diff --check` | 未发现空白错误；Git 仅提示部分文件后续可能由 LF 转为 CRLF |

Quick 失败项：

- `ImageReferenceParserTest`：3 项
- `MemoryManagerTest`：1 项
- `PromptAssemblerTest`：1 项
- `CodeIndexTest`：2 项
- `CodeRetrieverTest`：1 项
- `InlineRendererTest`：1 项
- `CodeSearchGoldenSetTest`：1 项

这些失败均不位于本次新增的 ChangeSpec 测试中；当前 10 项均包含在上一轮记录的 11 项失败集合中，没有出现新的失败测试。上一轮失败的 `TerminalMarkdownRendererTest` 本轮通过，但是否属于环境波动尚未诊断。

### 尚未确认

- 第一条切片修改前没有运行同环境基线，因此仍不能从 Git 历史证明上述 Quick 失败的最初来源。
- 部分失败表象可能与 Windows 路径、当前目录或换行有关，但尚未完成诊断，不能作为结论。
- Maven Shade 输出存在 `module-info` 和 service overlap 警告；构建成功，但其长期影响未评估。

## 8. 未解决问题

### 已验证的缺口

- 尚未将锁定后的 ChangeSpec 注入 ReAct 执行上下文。
- 确认后的 Draft 尚未持久化到 `.paicli/specs`，当前命令结束后不会保留运行时对象。
- 尚无 Spec Run、工作区变更基线、Scope 执行检查和命令 Verifier 执行器。
- `minimum_tests` 目前只校验配置，尚未解析 JUnit 报告并核对测试数量。
- 尚无运行时 Evidence、VerifierResult、CriterionResult 和 Verdict 归约。
- 尚无 Human Criterion 交互。
- 尚无失败证据驱动的一次自动修复。
- 尚无 A/B/C 任务集、指标采集和开发效率结论。
- 尚无运行时 Revision 流程；V1 当前模型只支持读取 revision 字段。
- Quick 回归不是绿色状态，且失败归因未完成。

### 尚未确认的设计问题

- V1 已采用用户显式 `/spec`，不自动强制切换；是否在后续增加弱提示，需要根据不同任务类型的净耗时数据决定。
- 当前模型配合专用 Prompt 和一次纠错能否达到足够稳定性，仍需真实模型任务集验证。
- Human Criterion 的真实使用频率是否会抵消 Spec 带来的效率收益。
- Evidence 的最小持久化格式和保留周期尚未通过闭环实现验证。

## 9. 下一阶段任务

### 已确定但尚未开始

下一阶段是 RFC 的第三步：把用户确认的 ChangeSpec 锁定、持久化，并作为本轮不可变契约注入现有 ReAct；仍不提前实现 Verifier、Evidence 或 Verdict。

建议的最小完成边界：

1. Enter 确认后把完整文档保存到 `.paicli/specs/CHANGE-<timestamp>-r1.md`。
2. 保存结果必须与确认时的 `specId + revision + specDigest` 一致，Markdown 变化不得改变机器契约 Digest。
3. 将锁定 Spec 作为本轮输入交给现有 `Agent.run()`，不创建 Spec 专用 Coding Agent。
4. 未确认、无效或保存失败时不得进入 ReAct。
5. Spec 确认不替代 HITL、PathGuard 或 CommandGuard，也不扩大工具权限。
6. 增加持久化、注入、取消及失败分支测试，并同步文档。

### 暂不执行

Workspace baseline、Scope/command/JUnit Verifier、Evidence/Verdict、一次修复和 A/B/C 评测仍属于后续切片。只有用户明确要求继续后，才开始第三条切片。
