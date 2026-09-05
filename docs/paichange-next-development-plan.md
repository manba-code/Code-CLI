# PaiChange 后续开发计划

> 编制日期：2026-09-04  
> 状态：M1、M2 已完成实施与验收；M3–M6 待开发，未授权扩展实施或生产上线  
> 基线：Phase 1–6 本地后端、最小 Web、离线 Mock 演示已完成  
> 前置文档：[平台改造计划](paichange-platform-refactoring-plan.md)、[项目路线图](../ROADMAP.md)

## 1. 目标与范围

在现有 ChangeTask 工作流上，依次补齐可靠运行、人工验收、工具治理、真实研发平台接入、身份权限和生产运行基础。继续复用 PaiCLI Worker、ChangeSpec、Verifier 与最多一次 Evidence 修复机制。

本计划把前序讨论的六项开发内容拆成可验收的阶段。原平台计划 §19 的 Phase 7 仍是生产化设计纲要；本文使用 M1–M6 编号，避免把设计纲要误认为已批准的完整生产实施。

原计划编制阶段仅整理文档；2026-09-04 已分别授权实施 M1 与 M2。真实 SCM 写入、部署、付费模型评测及 M3–M6 不在本次范围。不预设人力和日历工期；每阶段按验收退出，实际排期在进入阶段时结合实现规模确定。

## 2. 当前基线与缺口

| 能力 | 当前事实 | 本计划补齐内容 |
|---|---|---|
| Draft | M1 已改为原子保存 Draft Job、后台领取生成并恢复；详见实施记录 | 异步 Draft Job、重试、取消、重启恢复 |
| 人工验收 | M2 已增加逐项人工补录、判断版本与独立交付审批；缺项仍 pending | 人工证据、逐项判断、确定性重算 Verdict |
| 工具治理 | Worker 使用原始 `ToolRegistry`；`toolPolicy` 是审计 profile | profile 实际执行、持久化工具审批 |
| SCM | SQLite Mock PR/Check；现有 Mock 类不能直接等同于通用 Adapter 接口 | 提取最小接口，接入一个真实 SCM |
| 身份权限 | 单 API Key、客户端 `actorId`；已有业务职责分离规则 | 服务端可信身份、项目权限、统一鉴权 |
| 生产运行 | 本地单进程、SQLite、worktree、本地 Evidence | 执行隔离、Secret 管理、证据完整性、部署与恢复 |

以上是 2026-09-04 的代码核对结果，后续实施前应再次核对，不用历史测试数量代替当前验证。

## 3. 优先级与依赖

| 编号 | 优先级 | 开发内容 | 依赖与退出成果 |
|---|---|---|---|
| M1 | P0 | Draft 异步生成与中断恢复 | 基于现有队列与 Workflow；形成可恢复创建流程 |
| M2 | P0 | Human Evidence 补录闭环 | M1 后实施；形成完整人工验收与交付闭环 |
| M3 | P1 | 组织工具策略与 Worker 工具审批 | 复用 M1 恢复约定；共享使用前依赖 M5 身份权限 |
| M4 | P1 | 接入一个真实 SCM 平台 | Adapter 可用本地假服务开发；真实共享试点依赖 M3、M5、M6a |
| M5 | P1 | 身份认证与 RBAC | 实施顺序前移到真实 SCM 试点之前，为各类审批提供可信身份 |
| M6 | P2 | 生产隔离与存储部署 | 拆为 M6a 最小隔离与 M6b 存储部署；M6a 是共享试点前置条件 |

建议实际交付顺序：**M1 → M2 → M5 → M3 → M6a → M4 → M6b**。编号沿用六项内容的讨论顺序，不表示必须按编号开发。P2 表示生产化整体优先级；其中执行隔离的最小切片必须在处理共享环境中的不可信仓库前完成。

## 4. M1：Draft 异步生成与中断恢复

实施记录：[M1 Draft 异步生成与中断恢复](paichange-m1-implementation.md)。

### 4.1 目标与设计

创建任务只等待输入校验和持久化，不等待模型生成。浏览器立即获得 changeId，之后通过任务详情和事件查看生成结果。

- 保留 `POST /v1/changes` 创建成功返回 201 的契约，返回任务身份与当前状态；201 不再暗示已经到达 `SPEC_REVIEW`。幂等重放沿用已存在任务的响应约定。
- 将初次生成和 SUPPLEMENT 后重新生成统一为可持久化 Draft Job。业务状态由 ChangeWorkflow 维护，技术队列只负责领取、租约、重试和恢复。
- 使用独立生成 attempt/generation 标识，绑定 changeId、目标 revision 和输入快照。Worker 回写时校验身份与版本，取消、被替代或失去租约的生成结果不得覆盖当前任务。
- 任务保存与入队采用可恢复协议：优先持久化同事务的待调度记录，再交给队列消费；若存储边界不支持同事务，必须有持久化调度意图与启动对账，覆盖保存成功但入队失败窗口。
- 明确暂时性故障重试上限、退避和超时；持久化重试次数。Draft 内容资格纠错与基础设施重试分别计数，避免恢复无限追加模型调用。
- 支持显式取消及失败后重试。重试创建新的 generation，保留旧失败事件；重启恢复沿用原输入快照，已确认或锁定的 Spec 不重新生成。
- 页面在创建返回后展示后台生成状态、失败原因、重试和取消入口；先使用有退避、可停止的轮询，SSE 不作为本期条件。

### 4.2 实施切片

1. 定义 Draft Job 数据、状态转换、取消/重试 API 与存储迁移，补充进程崩溃窗口说明。
2. 接通持久化调度、领取、生成、回写和启动恢复，移出同步请求中的模型调用。
3. 接通 Web 状态展示和操作，调整原“创建中”测试以区分请求提交与后台生成。

主要落点：`DefaultChangeWorkflow`、`ChangePlatform`、`ChangeStore` / `SqliteChangeStore`、`runtime/task`、`ChangeApiHandler` 和 `paichange-web`。Draft Job 的新增类型是拟议实现，不把现有 Worker Job 的执行语义直接改写为 Draft 语义。

### 4.3 验收标准

- [x] 用可挂起的 Draft stub 验证：生成未释放时，创建 HTTP 请求已经返回有效 changeId。
- [x] 同幂等键重复创建或重复领取，不产生重复有效 Draft，也不重复进入审批阶段。
- [x] 覆盖保存后未入队、领取后未调用、调用后未保存、保存后未确认四类崩溃窗口；允许有界重算，只有一个结果成为当前有效版本。
- [x] 取消或新 generation 生效后，迟到回调不能改变当前业务状态；底层可取消调用被终止，不可取消调用的结果被丢弃。
- [x] 服务重启后待生成任务可以恢复，已锁定任务保持原 digest；失败重试不清除历史。
- [x] 刷新页面能够继续跟踪，失败不显示草稿成功，旧页面版本冲突要求刷新。

验收证据：`DraftRecoveryTest`、`DraftJobRunnerTest`、`ChangePlatformEndToEndTest`、`LlmCallCancellationTest` 与 Web 回归；针对性 81 项通过，quick 911 项（0 failures/errors，5 skipped），浏览器完成后台生成、失败重试、刷新续看、服务重启及取消。具体迁移和边界见 [M1 实施记录](paichange-m1-implementation.md)。

## 5. M2：Human Evidence 补录闭环（已完成）

实施记录：[M2 Human Evidence 补录闭环](paichange-m2-implementation.md)。

### 5.1 目标与设计

为 Human Criterion 提供独立人工判断入口。人工证据补录完成后，由系统结合已有确定性结果重新计算 Verdict，再按风险规则进入 Delivery Approval。

- 每条人工记录绑定 changeId、specDigest、runId、headSha、criterionId、结果、理由、证据引用、操作者和时间。客户端提交 expectedVersion，过期身份返回 409。
- 人工结果沿用现有 Human 判断语义；跳过或缺失不能算通过。确定性 Criterion 的 FAIL/ERROR/INCONCLUSIVE 不得被人工判断改写为 PASS。
- 第一版支持文本说明和当前任务已有 Artifact 引用；任意本地路径、任意远程抓取和附件上传不进入首版。
- 原始 VerificationAttempt、Evidence 和 Worker 结果不可覆盖；人工补录与重新聚合形成有版本的判断记录，并区分原始 Run Verdict 与当前有效交付判断。
- 人工验收和 Delivery Approval 是独立操作。补录不直接发布成功；HIGH 风险仍需要有效 Delivery Approval。
- 同一 specDigest/headSha 下，人工补录可能使 Check 从 pending 转为 success/failure。当前 Mock Check 按代码身份禁止覆盖结论，需要引入交付判断 revision 与追加发布历史，保持同一 revision 幂等，禁止迟到 pending 覆盖更新结果。
- 人工记录修改采用追加更正，重算后使依赖旧判断的交付审批失效；Spec、head 或有效运行变化同样使旧证据与审批不再适用。

### 5.2 实施切片

1. 新增人工记录与判断版本的存储，定义纯聚合逻辑及有效性校验。
2. 增加 Workflow 操作、HTTP API、Mock Check 演进和事件审计。
3. 页面按 Criterion 展示待验收项、证据和结果，并独立展示后续交付审批。

主要落点：`ChangeDecision`、`DefaultChangeWorkflow`、`RunRef`、`ApprovalRecord`、`MockScmAdapter`、`SpecRunResult` 相关聚合逻辑、Artifact API 与 Web。是否扩展 RunRef 或新增独立判断对象，在本阶段设计中确定；不得静默覆盖磁盘原始 result.json。

### 5.3 验收标准

- [x] 确定性结果通过、人工项缺失时仍为 NEEDS_HUMAN/pending。
- [x] 人工项全部满足后按现有语义重算；HIGH 仍停留在交付审批，普通批准无法绕过人工项。
- [x] 确定性失败或验证异常时，人工输入不能产生 PASSED。
- [x] 更换 head、Spec、run 或判断版本后，旧页面提交被拒绝，旧审批不能放行。
- [x] pending → 最终结论可以幂等发布，完整保留旧判断；乱序发布不能回退当前结果。
- [x] 重启后人工记录、当前判断和审批失效状态一致；文本继续通过 textContent 渲染。

验收证据：`HumanEvidenceWorkflowTest`、`HumanEvidenceApiTest`、既有 Mock/Workflow/Store/平台回归及 Web 测试；平台针对性 63 项通过，Node 11 项通过，quick 923 项（0 failures/errors，5 skipped）。浏览器完成补录、双页面 409、独立 HIGH 审批、更正失效、确定性失败阻断和服务重启。详见 [M2 实施记录](paichange-m2-implementation.md)。

## 6. M3：组织工具策略与 Worker 工具审批

### 6.1 目标与设计

将 `STANDARD / RESTRICTED / LOCKED_DOWN` 从审计标签变为实际执行策略，后台 Worker 遇到需确认的工具调用时能够等待、拒绝或继续。

- 定义版本化组织/项目工具规则：允许、需审批、拒绝三种结果；拒绝优先，未知工具默认拒绝。工具名、参数范围、命令、MCP server/tool 与工作目录都参与决策。
- Agent 工具调用、修复阶段和 command Verifier 使用同一策略入口；人工批准不能绕过 PathGuard、CommandGuard 或组织拒绝规则。
- 复用现有 HITL 抽象，增加后台持久化审批处理器；保持 CLI 交互和微信默认拒绝策略的现有行为。
- 审批请求绑定任务、run、调用 ID、规范化参数摘要、工作目录、Spec 身份与策略版本；展示脱敏内容，批准必须匹配原调用。
- 明确超时、拒绝、取消和重启行为。第一版不声称 Agent 会话可无损跨进程续跑：没有持久化执行检查点时，重启后的悬挂调用安全失效并记录中断，不能自动重放可能已有副作用的命令。
- 等待状态释放队列执行资源或使用有界等待容量，避免所有 Worker 都被人工等待占满。撤销权限或策略变化后，执行前再次校验。

### 6.2 实施切片与验收

1. 策略模型与强制执行：覆盖 Agent、修复和 Verifier。
2. 持久化工具审批与等待管理：接入 M5 身份及项目权限。
3. Web 审批页和审计事件：分别展示工具审批、Spec 审批、Delivery Approval。

主要落点：`PaicliChangeWorkerRuntimeFactory`、`ExecutionRoute`、`hitl/`、`policy/`、`ToolRegistry`、Change API、SQLite 和 Web。

- [ ] 三类 profile 各有允许/确认/拒绝用例，参数或策略变更后旧批准不能使用。
- [ ] 组织拒绝和底层 Guard 拒绝不能通过人工批准绕过。
- [ ] command Verifier 和修复不会获得更宽权限；拒绝原因进入 Evidence/事件。
- [ ] 超时、取消、进程重启不自动执行未确认调用，也不自动重放结果不明的副作用调用。
- [ ] 无权限用户不能批准，审批页不泄漏 Secret；等待任务不阻塞其他可执行任务。

## 7. M4：接入一个真实 SCM 平台

### 7.1 目标与设计

完成工单 → ChangeTask → 受控分支 → PR/MR → 绑定 head 的 Check。首期只支持一个平台；建议优先 GitLab，与既有 Mock GitLab Issue 方向一致。最终平台和认证方式在本阶段开始时根据实际仓库确定。

- 从现有具体 Mock 实现提取最小 WorkItem/SCM 接口，业务资格仍由 ChangeWorkflow 决定；Adapter 不能自行把模型回答转为成功。
- 第一版通过指定工单导入触发，不要求完整 Webhook 平台；持久化来源平台、项目、工单标识和内容快照，重复导入具备明确幂等语义。
- 实现受控仓库拉取、任务分支推送、创建或复用 PR/MR、发布 Check。凭据来自服务端配置/Secret 引用，权限仅覆盖所需仓库与操作。
- 引入持久化发布意图和远程结果对账，处理限流、暂时性失败、超时结果不明、远程成功后本地保存失败。重试前查询已有远程资源，不盲目再创建。
- success 必须绑定当前有效 specDigest、run、head、判断 revision 和审批。远程 head 前进后旧 Check 不能充当新 head 的通过结果；无通过结论的最新 head 应被分支保护阻止合并。
- 保留 Mock 离线演示与原 Runtime threads 兼容；不加入自动合并、生产部署、跨平台同时接入或 Jira 全套集成。

### 7.2 实施切片与验收

1. 提取接口和共用契约测试，Mock 行为保持可验证。
2. 接入本地 HTTP 假服务，覆盖工单、分支、PR/MR、Check 与重试。
3. M3、M5、M6a 完成后，在明确授权的测试仓库执行少量真实集成验收。

主要落点：`MockWorkItemAdapter`、`MockScmAdapter`、`DefaultChangeWorkflow`、`DeliveryHeadReader`、`DeliveryRef`、`ChangePlatform` 和配置装配。

- [ ] 工单重复导入、发布重复调用和结果不明后的重试不产生重复 PR/MR。
- [ ] 验证失败只发布 failure，NEEDS_HUMAN 保持 pending，HIGH 缺少审批不发布 success。
- [ ] 本地/远程 head 不一致或审批过期时拒绝 success；真实测试仓库验证最新 head 的分支保护效果。
- [ ] 远端 401/403、限流、断网、部分成功均有可恢复或明确失败状态，不显示 COMPLETED。
- [ ] 日志、事件、PR 内容和 Artifact 不包含凭据；Mock 模式仍可无外网运行。

## 8. M5：身份认证与 RBAC

### 8.1 目标与设计

让任务访问和审批权限基于服务端验证的身份，而非用户可填写的 actorId。建议采用 OIDC 对接现有身份服务，具体提供方和会话方式在本期确定。

- 建立 Principal、项目成员与权限映射，最小角色为只读者、开发者、审批者和项目管理员；权限按动作定义，避免把所有写操作视为同一种权限。
- 覆盖任务创建/读取、Artifact/事件访问、取消/重试、人工验收、Spec/Delivery/工具审批、连接与策略配置。
- actorId 从可信身份生成；旧字段若保留兼容，只能校验与当前主体一致，不能决定操作者。服务账号与真人身份分别记录。
- 中高风险职责分离基于服务端身份执行；管理员角色不隐式绕过自批限制。机器账号不自动继承人工审批权限。
- 项目边界在服务端统一校验，含列表过滤、任务详情、事件和 Evidence；页面隐藏按钮只作为体验辅助。
- 本地单 API Key 模式可以保留，但必须显式标记本地可信模式，不能作为共享部署的身份方案。若使用 Cookie 会话，补齐 CSRF、过期、退出与安全 Cookie 设置。

### 8.2 实施切片与验收

1. Principal 与权限模型、身份 Adapter 和本地兼容模式。
2. HTTP 统一鉴权与 Workflow 职责分离接入，覆盖 M1/M2 操作。
3. Web 登录态与权限反馈，随后供 M3/M4 使用。

主要落点：`RuntimeApiServer`、`ChangeApiHandler`、`ChangeApprovalPolicy`、各类审批记录和 Web；身份模型不耦合到 LLM。

- [ ] 伪造 actorId 无法代替他人审批；未认证和权限不足分别返回清晰的 401/403。
- [ ] 跨项目 ID、Artifact 和事件访问被拒绝，列表不泄漏无权查看的项目数据。
- [ ] 同一服务端身份不能通过换展示名绕过职责分离；机器账号不能冒充真人验收。
- [ ] 会话过期、权限撤销后敏感操作失效，审计可定位真实主体。
- [ ] 原本地模式与 Runtime threads 的兼容行为有明确测试，不静默扩大网络监听范围。

## 9. M6：生产隔离与存储部署

### 9.1 M6a：共享试点前的最小执行隔离

- 将 Worker 执行移入独立容器，限制 CPU、内存、进程数和任务时间；禁止特权运行、Docker socket 与宿主私有目录挂载。
- 只挂载任务工作区和必要产物位置，控制面数据库与其他任务的工作区不可访问；取消和超时清理整个执行进程树。
- 采用默认拒绝、按项目声明的网络出口；依赖下载与允许的模型/MCP 访问走显式规则，不能访问宿主和云元数据端点。
- Secret 使用最小范围、短生命周期注入；模型凭据优先留在代理/控制面，SCM 写凭据留在发布组件；不得持久化进 Prompt、日志或 Evidence。
- Evidence 由控制面可信采集路径入库，形成内容哈希和不可变版本；不能仅相信 Worker 自报 PASS 或自报哈希。正式威胁模型应明确容器能防什么、不能防什么，再决定是否需要更强隔离。

验收：

- [ ] 两个任务之间和任务与控制面之间无法读取未授权文件、凭据和数据库。
- [ ] 禁止网络出口被阻断，允许的依赖/模型访问正常；超时/取消无遗留执行进程。
- [ ] Evidence 篡改或对象缺失被发现时拒绝 success；产物存储失败不显示完成。
- [ ] 容器退出、宿主重启、Secret 失效有明确恢复或失败记录，不重复执行结果不明的外部操作。

### 9.2 M6b：存储、部署与运维

- 根据试点容量选择 PostgreSQL、持久化队列和对象存储，先落地存储契约及迁移版本，再替换默认组件；SQLite 继续用于本地演示。
- ChangeTask、事件、审批和发布意图使用事务维护；跨 Git/SCM/对象存储通过幂等与对账收敛，不声称存在跨资源原子事务。
- 迁移方案包含备份、试迁移、数据校验、切换、回滚与旧版本兼容策略；切换期间明确写入边界。
- 提供部署配置、健康检查、队列积压/失败/成本指标、日志脱敏、审计导出、备份恢复和故障操作手册。
- 用真实试点负载确定并发、SLO、RPO/RTO；这些指标在上线验收前必须形成明确数值和实测结果，不在当前计划中虚构承诺。

验收：

- [ ] 相同存储契约测试在 SQLite 与目标数据库通过，历史 Spec/digest/审批/事件/Evidence 引用迁移后可核对。
- [ ] 至少一次备份恢复与部署回滚演练，恢复结果满足已确定的 RPO/RTO。
- [ ] 重复投递、Worker 丢失、对象存储故障和远程发布部分成功均完成故障演练。
- [ ] 按确定的并发负载完成容量验证，监控能够定位排队、Draft、LLM、工具、验证和发布耗时。

## 10. 验证与交付约定

每阶段交付代码、存储/API 迁移说明、相关文档、可重复的验收步骤和测试结果。完成定义是验收项有证据，不是勾选实现文件数量。

默认验证使用本地 stub、Mock HTTP 和临时仓库。M1/M2 优先扩展已有 Workflow、Store、API、EndToEnd 与 Web 测试；M3 增加工具策略/审批测试；M4 增加 Adapter 契约与故障测试；M5 增加权限矩阵测试；M6 增加隔离、迁移和恢复验证。新增测试名在实现时确定。

现有基础回归入口：

```bash
mvn test -Dtest=DefaultChangeWorkflowTest,SqliteChangeStoreTest,ChangeApiHandlerTest,ChangePlatformEndToEndTest -DskipTests=false
node --test src/test/js/change-web-creation.test.cjs
mvn test -Pquick
```

文档编写本身只检查引用与内容一致性，不为纯文档变更运行上述回归。实际开发按影响面执行针对性测试，在阶段收束时运行 quick；涉及页面交互时做浏览器验收。

`agent-eval`、`change-spec-eval` 和其他真实模型批量评测不属于本计划的默认验证步骤，仍需单独授权。真实 SCM 写入使用专用测试仓库和明确操作范围，开发计划本身不代替外部写入授权。

## 11. M1 执行清单（已完成）

本次已完成 **M1：Draft 异步生成与中断恢复**。以下清单保留为实施记录；M2 已随后完成，M3 及后续阶段未启动：

1. 核对现有 Worker Job 与 ChangeStore 的事务边界，完成 Draft Job 状态表和四类崩溃窗口设计。
2. 确定创建响应、取消、重试、SUPPLEMENT 重新生成及 generation 失效契约。
3. 实现并验证后台生成、持久化调度和启动恢复。
4. 接通页面进度与操作，验证刷新、取消、失败、重启和迟到结果。
5. 同步 README、AGENTS 和实施记录；验收通过后再更新 ROADMAP 为已完成。

阶段起始待落实项只在进入相应阶段时处理：M3 审批等待容量与超时、M4 实际 SCM/测试仓库与认证方式、M5 身份提供方、M6 运行环境与容量目标。它们不阻塞 M1 的开发设计。
