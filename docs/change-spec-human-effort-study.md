# ChangeSpec 真人总人时实验协议

> 状态：协议已执行一次 9-session 可行性试跑；机械流程审计通过，但测后说明最终 `ACCEPT` 未基于具体代码审阅，完整真人总人时与接受准确率不可评价。模式工程价值有正面证据，量化收益尚未证明。中文更正版见 [`change-spec-human-effort-feasibility-20260823-01-zh.md`](change-spec-human-effort-feasibility-20260823-01-zh.md)。
> 目标：测量普通 ReAct 与 ChangeSpec 路径需要的完整真人主动投入，而不是用自动确认或局部交互耗时代替 `total_human_effort`。
> 费用边界：本协议本身不产生 API 费用；可行性试跑和正式实验都必须另行批准。

## 1. 要回答的问题

实验继续使用现有 A/B/C 定义：

| 组 | 产品路径 | 真人最终判断依据 |
|---|---|---|
| A | 原始需求 + 普通 ReAct | 人工阅读结果、Diff 和可见测试后接受、拒绝或要求返工 |
| B | 确认并锁定 ChangeSpec + ReAct + 公开 Verifier，关闭自动修复 | Spec Verdict、Evidence、Diff 和可见测试 |
| C | 与 B 共用锁定 ChangeSpec，允许最多一次 Evidence 驱动修复 | Spec Verdict、Evidence、Diff 和可见测试 |

主要问题是 A→C：完整 ChangeSpec 产品路径是否减少从收到需求到作出可信决定所需的真人总人时。A→B 和 B→C 是次要机制问题，分别解释契约/Evidence Gate 与一次自动修复的影响。

隐藏 Oracle 和 Scope 只在真人作出最终决定后运行，用于判断决定是否正确；参与者在 session 中不能看到隐藏测试内容或输出。

## 2. 核心口径

### 2.1 实验单位

一个实验单位是 `参与者 × 任务 × 模式` 的独立 session。每个 session 使用独立 workspace、会话历史、长期记忆、HITL 状态和运行产物。

同一参与者不能在不同模式下重复看到同一个 fixture，避免记住实现或测试后人为降低后续模式的复核与返工成本。参与者可以执行多个不同任务。

### 2.2 `total_human_effort`

`total_human_effort_ms` 是一个 session 内所有参与者主动投入时间之和：

```text
requirements_communication_ms
+ spec_confirmation_ms
+ hitl_decision_ms
+ human_criterion_ms
+ result_review_ms
+ rework_ms
+ rerun_operation_ms
= total_human_effort_ms
```

规则：

- 只计阅读、判断、输入、沟通和主动操作；不计等待模型响应、编译、测试、Verifier 或隐藏 Oracle 的时间。
- 同一参与者的事件不得重叠；两名参与者同时工作时按人时相加。
- 暂停、离席和无关打断不计入任何类别。
- 没有采集到的类别保存为空值，不能写成 `0`；只有明确观察到没有发生时才能记 `0`。
- 产品墙钟、自动阶段耗时与真人总人时分别报告，不能相加后仍称为人时。

### 2.3 事件分类

| 类别 | 开始 | 结束 | 不包含 |
|---|---|---|---|
| `requirements_communication` | 阅读需求或开始澄清 | 形成可执行理解或沟通结束 | 等待对方回复 |
| `spec_confirmation` | ChangeSpec Draft 可见 | 确认、补充后重生成、取消或判无效 | Draft 模型生成等待 |
| `hitl_decision` | 审批提示完整可见 | 批准、修改、拒绝或跳过已提交 | 工具实际执行时间 |
| `human_criterion` | Human Criterion 与 Diff 可见 | `P / F / S` 已提交 | 后续持久化等待 |
| `result_review` | 最终输出、Verdict 或 Diff 开始审阅 | 接受、拒绝或要求返工 | 隐藏 Oracle 执行 |
| `rework` | 开始分析失败或编写返工指令 | 指令提交或决定放弃 | Agent 执行返工的等待 |
| `rerun_operation` | 开始准备重跑 | 重跑已启动或结束后的人工整理完成 | 重跑本身的自动等待 |

沟通只使用 `requirements_communication`，不再同时记入 `rework`；返工阶段的沟通记入 `rework`。每段主动时间只能属于一个类别。

## 3. 参与者、角色与分配

### 3.1 参与者要求

目标参与者应能独立阅读 Java 17 代码、Maven 测试与 Git Diff，并理解基本的路径、安全和 API 兼容风险。正式实验记录经验等级，但不把个人姓名、邮箱或组织标识写入研究产物；仅使用随机参与者 ID。

开始前统一完成：

1. 10～15 分钟产品操作说明；
2. 一个不属于 13 个正式 fixture 的练习任务；
3. 计时器开始/暂停练习；
4. 对“接受、拒绝、要求返工”判定标准的校准。

### 3.2 B/C 的配对 Spec

每个 `任务 × repetition block` 只生成一个合格 Draft，由未接触该任务实现的需求确认者审阅并锁定。B/C 从同一 baseline 分叉并复用同一个 `specDigest`。

确认者的完整主动确认时间分别计入 B 和 C，模拟任一产品路径都必须支付的确认成本；真实 Draft API 调用仍只发生一次。执行 B 与 C 的参与者必须不同，且此前没有看过该 fixture。若 B/C digest 不一致，该配对块标记为 `EXCLUDED_PROTOCOL_DEVIATION`，不能进入 B→C 分析。

### 3.3 顺序与学习效应

模式顺序使用固定 seed 做区组随机化，并让每名参与者执行的 A/B/C 数量尽量平衡。参与者永远不会重复同一任务。

可行性试跑使用 3 名执行参与者、1 名独立需求确认者、三个代表任务和 3×3 Latin square，共 9 个执行 session。独立确认者分别确认三个不同任务的 B/C 配对 Spec，不执行这些任务：

| 参与者 | `ascii-slugifier` | `login-retry-policy` | `workspace-path-safety` |
|---|---|---|---|
| P1 | A | B | C |
| P2 | B | C | A |
| P3 | C | A | B |

这 9 个 session 只验证操作、计时、配对和数据完整性，每个任务/模式只有一次观察，不能据此宣称提效。

正式实验建议 9 名执行参与者各完成 13 个任务且每个任务只看一次，通过区组分配使每个 `任务 × 模式` 获得 3 次独立观察，共 117 个 session。B/C 另需 3 名独立需求确认者，每人只确认每个任务的一个 repetition block；同一个人可以确认多个不同任务，但不能重复确认同一 fixture，也不能执行或复核自己确认过的任务。因此正式设计最低需要 12 名真人参与者。

## 4. 固定实验流程

### 4.1 运行前冻结

每轮实验必须先保存 study manifest，至少包含：

- Git commit、dirty worktree 状态和 fixture catalog hash；
- provider、model、评测 seed、ReAct 迭代/Token 预算；
- 任务、模式、repetition block、顺序分配；
- HITL/CommandGuard 配置和允许命令；
- 人时非劣界值与最大主动人时；
- 协议版本和 CSV 模板版本。

未设置 `human_effort_noninferiority_margin_pct` 时可以做可行性试跑，但不得进行正式 `PASS / FAIL` 人工效率判定。该界值必须在查看正式结果前由产品负责人基于人员成本和错误风险预注册，不能在看到数据后调整。

### 4.2 单个 session

1. 创建全新 workspace、会话、记忆和 HITL 状态，加载固定任务材料。
2. 观察员启动 session 墙钟；参与者开始主动操作时启动相应人时事件。
3. A 直接提交原始任务；B/C 展示已配对的 Draft 确认过程和最终锁定文档。
4. 参与者按真实判断处理 HITL，不允许跨 session 继承“全部放行”。
5. Agent 正常结束后，参与者审阅该模式正常可见的输出并选择 `ACCEPT / REJECT / REWORK / ABANDON`。
6. 所有模式最多允许 2 次人工发起的返工/重跑；C 的内置 Evidence 自动修复不算人工返工，但相关 HITL 判断仍计人时。
7. 达成接受、明确拒绝/放弃，或触及预注册的主动人时上限时结束。建议可行性试跑上限为每 session 30 分钟主动人时，自动等待不占上限。
8. 冻结真人最终决定后再运行隐藏 Oracle 与 Scope，写入结果表；不得把隐藏结果反馈给当前参与者后继续修改同一 session。

### 4.3 公平性约束

- 三组使用相同模型、预算、任务描述、visible fixture、公开命令和机器环境。
- A 可以使用产品正常提供的可见工具和测试，但不能获得 B/C 的锁定 Spec、Criterion、Verifier Evidence 或 Verdict。
- B/C 使用相同锁定 Spec；B 只关闭自动修复，其他产品行为不变。
- 观察员只能解释协议或记录故障，不能提示代码、测试边界或接受决定。
- 环境故障、计时器故障、参与者提前看见隐藏 Oracle、重复接触同一任务等情况必须记录为协议偏差，不能静默删除。

## 5. 数据采集

仓库提供一个冻结清单和三个只含表头的记录模板：

- [`change-spec-human-effort-manifest-template.yaml`](change-spec-human-effort-manifest-template.yaml)：运行前冻结的代码、模型、预算、任务与判定参数；
- [`change-spec-human-effort-assignments-template.csv`](change-spec-human-effort-assignments-template.csv)：预先生成的参与者、任务、模式、顺序和 B/C 配对分配；
- [`change-spec-human-effort-events-template.csv`](change-spec-human-effort-events-template.csv)：每段主动人时事件；
- [`change-spec-human-effort-sessions-template.csv`](change-spec-human-effort-sessions-template.csv)：每个 session 的分配、决定、自动结果和汇总。

事件计时优先使用独立的单调计时器；UTC 时间戳只用于审计顺序，`active_ms` 由单调时钟计算。现有 `specConfirmationMs` 和 `humanCriterionMs` 可以作为交叉核对，但不能直接代替观察记录，更不能推导其他未采集类别。

`protocol_status` 取值：

- `VALID`：流程和字段完整，可进入主分析；
- `PARTIAL`：有结果但至少一个人时类别无法判断，保留原始记录但不进入完整人时比较；
- `EXCLUDED_PROTOCOL_DEVIATION`：发生预注册的排除条件；
- `ABORTED`：参与者或环境中止，报告实际已投入人时但不冒充完成 session。

自由文本备注不能包含 API Key、真实身份、内部仓库内容或完整终端日志。研究产物不保存模型 reasoning。

## 6. 数据校验

分析前必须通过以下检查：

1. `session_id`、`event_id` 唯一，事件能够关联到已声明的 session；
2. 同一参与者在同一 session 的主动事件不重叠，`ended_at_utc >= started_at_utc`；
3. `total_human_effort_ms` 等于全部有效事件 `active_ms` 之和；
4. B/C 每个配对块的 `spec_digest` 一致；
5. 每名参与者没有重复接触同一 `case_id`；
6. `ACCEPT / REJECT / ABANDON` 在隐藏 Oracle 运行前已经冻结；
7. 空值与真实 `0` 可区分；任何人工补值都保留原因和修改审计。

失败记录不得直接删除。主报告列出 `VALID / PARTIAL / EXCLUDED / ABORTED` 数量和原因；只有 `VALID` session 进入完整 `total_human_effort` 主分析。

## 7. 指标与分析

### 7.1 主要指标

- 每组 `total_human_effort_ms` 的中位数、IQR、均值和 95% 区间；
- A→C 的整体、按 tier、按任务差值和相对变化；
- 七类人时构成，重点显示确认成本是否被复核、返工或重跑节省抵消；
- 真人接受决定准确率：隐藏 Oracle/Scope 失败但选择 `ACCEPT` 为 false acceptance；客观正确却最终 `REJECT/ABANDON` 为 false rejection；
- 人工返工率、人工重跑率、HITL 次数和放弃率。

### 7.2 次要指标

- A→B：契约与 Evidence Gate 对复核、错误接受和返工的影响；
- B→C：在相同 `specDigest` 的配对块内比较一次自动修复带来的人时变化；
- 从提交需求到冻结真人决定的端到端墙钟，继续与主动人时分列；
- 与自动评测的任务成功率、可信产品决定 TTA、Token 和单位成功成本联合展示。

可行性试跑只列出逐 session 原始数据和协议问题；量化效率、质量增益和因果归因维度标记为 `NOT_EVALUABLE_PILOT_ONLY`，同时允许基于直接流程证据单独报告契约、范围治理、验证闭环和可审计性的工程价值。正式实验报告任务和参与者两个来源的差异，至少展示分层结果与配对差值；样本不足时不以点估计冒充统计结论。

### 7.3 结论状态

- `NOT_MEASURED`：没有有效真人事件数据；
- `NOT_EVALUABLE`：只有可行性数据、有效样本不足或关键对照不可比；
- `PASS`：正式实验达到预注册的人时门槛，同时质量非劣护栏、Scope 和 false acceptance 不回退；
- `FAIL`：具备正式评测机会但未达到预注册门槛或质量护栏。

高风险任务允许以预注册的少量人时增加换取更低 false acceptance、复核或返工成本，但必须分维度报告，不能汇总成单一“ChangeSpec 有价值”分数。

## 8. 执行门槛与下一步

协议设计完成不等于真人效率已测得。后续分两次单独授权：

1. **可行性试跑**：3 名执行参与者 + 1 名独立需求确认者、3 个代表任务、9 个执行 session；目标是验证计时、CSV、配对 Spec、任务隔离和观察员流程。
2. **正式实验**：完成可行性复盘并冻结协议后，9 名执行参与者 + 3 名独立需求确认者，13 个任务 × 3 组 × 3 次，共 117 个执行 session；运行真实模型时同时产生 API 费用。

任一阶段开始前都要明确批准人员投入、模型费用、冻结 commit/model、实验日期、最大主动人时和人时非劣界值。未获这些授权时，自动 Pilot 的 `total_human_effort` 继续报告 `NOT_MEASURED`。
