# ChangeSpec 任务状态

> 更新时间：2026-08-20
> 当前状态：前三条垂直切片已完成；`/spec` 已能生成、校验、确认并锁定 ChangeSpec，然后通过现有 ReAct 执行。尚未运行 Verifier，也没有 Evidence、Criterion Result 或 Verdict。
> 事实来源：当前工作区代码、`git status`、`git diff`、Maven/Surefire 测试结果，以及 `docs/change-spec-v1-rfc.md`。不能由这些材料证明的内容单独标为“尚未确认”。

## 1. 当前目标

### 已验证的事实

PaiCLI 的 ChangeSpec 是可选的 Spec-Driven Code Change 契约层：把自然语言代码需求转换为可锁定、可执行、可验收的 ChangeSpec，并最终通过最小必要证据判断变更是否满足要求。目标是缩短“提出需求 → 获得可信验收结果”的时间，而不是建设需求管理、日志或通用工作流平台。

当前已交付前三条切片：

1. ChangeSpec 领域模型、YAML Front Matter 解析、结构校验和稳定摘要；
2. `/spec <需求>` Draft 生成、一次结构纠错、确认/补充/取消交互；
3. 确认后的完整文档锁定持久化，最终确认需求与锁定 YAML/digest 注入现有 ReAct。

当前链路是：

```text
需求 → Draft → 用户确认 → 稳定编码 → 不可覆盖锁定 → 回读身份校验
    → Side-Git + ESC 取消包装 → 现有 Agent.run() → 未验证的执行结果
```

### 尚未确认

- ChangeSpec 是否实际提升开发效率，尚无 A/B/C 实验数据支持。
- 当前数据模型和 Draft 质量是否覆盖真实企业代码变更的主要场景，尚未经过真实模型任务集验证。
- 尚未形成从 ReAct 结果到确定性验证、Evidence 和 Verdict 的闭环。

## 2. 用户明确提出的强约束

### 已验证的事实

- 一切设计以开发效率为中心，避免为了流程完整而增加无收益模块。
- 小型、低风险、目标明确的任务继续使用普通 ReAct，不强制 Spec。
- Evidence 只保留验收必需的可观察事实，不保存思维过程或完整工具日志。
- V1 暂缓 LLM Reviewer，优先确定性验证，无法自动判断时再交给人。
- Spec 是现有 Agent 的可选契约层，不是新的 Coding Agent；V1 只接入 ReAct。
- Spec 确认不等于工具授权，现有 HITL、PathGuard、CommandGuard 始终生效。
- 每次只推进 RFC 中一条垂直切片；第三条切片不提前实现 Verifier、Evidence 或 Verdict。
- `I` 输入的补充属于最终确认需求，必须与锁定契约一起进入执行输入。
- 既有 Quick 回归失败不阻塞第三切片，但必须记录基线且不能新增失败。

## 3. 已完成内容

### 第一、二条切片

- 不可变 `ChangeSpec` 领域模型，包含 Intent、Scope、Acceptance、Oracle 和 Verifier 定义。
- `ChangeSpecCodec` 支持解析严格的 `YAML Front Matter + Markdown`，拒绝未知字段和重复 YAML Key。
- YAML 是唯一机器事实源；Markdown 不参与摘要。
- SHA-256 `specDigest` 绑定规范化机器模型。
- 无工具 `SpecDraftGenerator` 使用当前模型、Project Context 和用户显式本地引用生成 Draft。
- Draft 结构错误最多反馈给同一模型纠正一次。
- Enter 确认、I 补充后重生成、ESC 或 `/cancel` 取消。

### 第三条切片

- `ChangeSpecCodec.encode(...)` 将已校验模型稳定编码为可读 YAML + Markdown；枚举使用小写 wire value，空值字段不写入。
- 编码前重新计算 digest，拒绝保存机器契约与 digest 不一致的对象。
- `SpecDraftSession.Result` 返回包含所有 `I` 补充的最终确认需求。
- 新增 `SpecRunCoordinator`，其单一运行入口负责 Draft 会话、锁定、回读校验、执行输入构造和 ReAct 调用。
- 锁定路径为 `.paicli/specs/<specId>-r<revision>.md`；同名文件禁止覆盖。
- 文件先写同目录临时文件，再使用原子移动；文件系统不支持原子移动时回退到不可覆盖移动。
- 编码结果和保存结果都会重新 decode，并核对 `specId + revision + specDigest`。
- 保存失败、Spec 无效或用户取消时不会进入 ReAct。
- ReAct 取消或失败时已锁定文件保留，不实现自动删除或续跑。
- 执行输入包含最终确认需求、锁定 YAML、specId、revision 和 digest；本地 `@path` 只在最终确认需求部分展开。
- CLI 复用 `SnapshotService.runTurn("spec", ...)`、`runWithCancelSupport(...)` 和同一个 `Agent.run()`。
- CLI 明确提示当前只结束 ReAct 阶段，结果不是验收 Verdict，不生成 `PASSED / FAILED`。

## 4. 本切片修改文件

### 生产代码

- `src/main/java/com/paicli/spec/ChangeSpecCodec.java`：稳定编码、wire enum 序列化和编码前 digest 校验。
- `src/main/java/com/paicli/spec/SpecDraftSession.java`：返回最终确认需求。
- `src/main/java/com/paicli/spec/SpecRunCoordinator.java`：锁定、回读校验、执行输入和 ReAct 编排。
- `src/main/java/com/paicli/cli/Main.java`：把 `/spec` 接到 Coordinator、Side-Git/ESC 包装和未验证结果渲染。

### 测试

- `src/test/java/com/paicli/spec/ChangeSpecCodecTest.java`：编码 round-trip 和 digest 篡改拒绝。
- `src/test/java/com/paicli/spec/SpecDraftSessionTest.java`：最终确认需求和取消结果。
- `src/test/java/com/paicli/spec/SpecRunCoordinatorTest.java`：锁定执行、补充注入、取消、冲突、保存失败和执行失败保留文件。

### 文档

- `README.md`
- `AGENTS.md`
- `ROADMAP.md`
- `docs/change-spec-v1-rfc.md`
- `TASK_STATE.md`

基线提交为 `859d6a4cc23149a6cade2a2a046e192a3e1b7110`。该提交已经跟踪前两条切片的全部文件；本节第三条切片修改当前位于工作树中，尚未提交。

## 5. 关键设计决策及原因

1. **Coordinator 是深模块**：CLI 只提供本地引用展开与 ReAct 执行适配器，锁定不变量集中在 `com.paicli.spec`，调用者和测试共享同一接口。
2. **稳定重编码而非保存模型原始文本**：锁定事实来自已校验领域模型；Markdown 保留，格式和 YAML 注释不影响 digest。
3. **不可覆盖写入**：revision 1 锁定后不可被同名运行静默替换；冲突直接停止。
4. **写前、写后双校验**：先验证编码 round-trip，再验证磁盘回读，保证确认、保存和执行使用同一身份三元组。
5. **补充要求进入最终请求**：Draft 中反映补充还不够，ReAct 也应直接看到用户确认前增加的约束。
6. **锁定先于 ReAct，失败后保留**：确认后的契约是审计事实；执行取消或失败不能回头把它伪装成未发生。
7. **复用现有执行安全网**：Spec 只增加契约上下文，不绕过 Side-Git、ESC、HITL 或策略层。
8. **明确“未验证”**：本切片不能让 Agent 自述获得 Verdict 语义，为后续 Evidence Gate 保留清晰边界。

## 6. 当前测试结果

### 本切片已验证

| 检查 | 结果 |
|---|---|
| `mvn test '-Dtest=ChangeSpecCodecTest,SpecDraftGeneratorTest,SpecDraftSessionTest,SpecRunCoordinatorTest,SpecReviewInputParserTest,ChangeSpecCliFormatterTest,CliCommandParserTest,MainInputNormalizationTest' -DskipTests=false` | 109 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS` |
| `mvn -DskipTests package` | `BUILD SUCCESS`，生成 shaded jar；Shade 仍报告既有 module-info 和资源重叠警告 |
| `mvn test -Pquick` | 786 tests，10 failures，0 errors，1 skipped，`BUILD FAILURE` |
| `git diff --check` | 未发现空白错误；Git 仅提示部分文件后续可能由 LF 转为 CRLF |

Quick 的 10 项失败与前一条切片记录的失败类和测试方法一致：`ImageReferenceParserTest` 3 项，`MemoryManagerTest` 1 项，`PromptAssemblerTest` 1 项，`CodeIndexTest` 2 项，`CodeRetrieverTest` 1 项，`InlineRendererTest` 1 项，`CodeSearchGoldenSetTest` 1 项。测试总数从 777 增至 786，来自本切片新增测试；ChangeSpec 测试全部通过，没有新增失败。

## 7. 未解决问题

### 已验证的缺口

- 尚无 Workspace baseline 和本轮 changed-files/diff 采集。
- 尚未执行 Scope、command 或 JUnit Verifier。
- `minimum_tests` 仍只校验配置，没有解析 JUnit 报告。
- 尚无运行时 Evidence、VerifierResult、CriterionResult 和 Verdict 归约。
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

下一阶段是 RFC 的第四条切片：Workspace baseline、Scope 与 command/JUnit 验证。仍不提前实现 Criterion Result、最终 Verdict、持久化结果或自动修复。

建议的最小完成边界：

1. ReAct 执行前记录工作区 baseline，区分既有脏文件与本轮新增变化。
2. 执行后采集 changed files 和最终 diff；`.paicli/specs`、`.paicli/runs` 不参与代码 Scope。
3. 执行 `path_scope` 和 `command` Verifier，返回明确的 `PASS / FAIL / ERROR`。
4. 对配置了 `junit_report_glob + minimum_tests` 的命令解析 JUnit XML 并核对测试数量、失败和错误。
5. 命令退出码不符合预期为 `FAIL`；无法启动、超时或无有效结果为 `ERROR`。
6. Verifier 仍经过现有策略与授权规则，不因 Spec 放宽权限。
7. 增加 baseline、glob、命令、JUnit、超时和失败分支测试，并同步文档。

### 暂不执行

Criterion Result、Verdict、Human Criterion、一次证据驱动修复和 A/B/C 评测属于后续切片。只有用户明确要求继续后，才开始第四条切片。
