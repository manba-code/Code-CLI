# M1：Draft 异步生成与中断恢复

实施日期：2026-09-04。范围仅 M1；不包含 Human Evidence、组织工具策略、真实 SCM、RBAC 或生产隔离。

后续：M2 已实现 Human Evidence 补录和判断版本绑定，见 [M2 实施记录](paichange-m2-implementation.md)。本文件的 M1 验收数量与范围保留为历史记录。

## 运行与 API 契约

`POST /v1/changes` 在校验、保存任务和调度记录后返回 201 + Location + changeId，不等待模型。通常状态为 `DRAFTING_SPEC`；快速完成或幂等重放也可能返回其他当前状态。201 表示任务已存在，不能据此提示 Draft 成功。`SUPPLEMENT` 同样只保存新输入和调度记录，返回 200。

- `POST /v1/changes/{id}/draft-cancel`：仅接受 `DRAFTING_SPEC`，转为 `CANCELED`。
- `POST /v1/changes/{id}/draft-retry`：仅接受 Draft 失败的 `FAILED`，创建新的 generation，保留原输入、目标 revision 和全部旧事件。
- M1 当时两个操作请求体包含 `expectedVersion + expectedGeneration + actorId`。M5 后 `actorId` 改为可选一致性断言，服务端以认证 Principal 为操作者；版本或 generation 过期仍返回 409，不支持复用旧版本自动重放。
- 取消是终止本次任务；已取消任务不提供恢复执行入口，需要新幂等键重新创建。Spec 审批仍要求当前 version + digest，锁定后不再生成 Draft。
- 详情和列表新增 `draftJob`：generation、revision、status、attempts、availableAt、deadlineAt、error。时间字段为 Unix 毫秒；内部输入快照和 lease 不出现在此对象的 HTTP 表示中。

`ChangeWorkflow` 维护业务状态。`DraftJobRunner` 是独立技术执行器，不改写 Worker Job 的含义。默认两个并发，每 100ms 检查待调度任务、取消和租约超时。扫描失败保留 SQLite 事实并在下次扫描重试；所有生成调用在 Workflow 锁外执行。

## 数据与故障协议

SQLite 新增 nullable `change_tasks.draft_job_json` 列。现有数据库通过检查列并 `ALTER TABLE ADD COLUMN` 自动迁移；任务、Draft 调度记录和事件使用原有同一事务及 version CAS。调度记录本身就是可领取的持久化队列，不另建需要双写的内存入队事实。

| Job 状态 | 业务状态 | 转换 |
|---|---|---|
| PENDING | DRAFTING_SPEC | 等待领取 |
| RUNNING | DRAFTING_SPEC | 原子增加 attempt、生成 lease、保存截止时间 |
| RETRY_WAIT | DRAFTING_SPEC | 暂时性错误或中断，等待退避 |
| SUCCEEDED | SPEC_REVIEW 及后续执行阶段 | 与 Spec 引用、生成事件同事务提交 |
| FAILED | FAILED | 非暂时性错误或预算耗尽；允许显式重试 |
| CANCELED | CANCELED | 用户取消；不允许迟到回调推进状态 |

每个 generation 保存 changeId、specId、目标 revision、完整需求、Project Context、引用上下文。每次领取消耗一次 attempt，包括领取后尚未调用就崩溃的情况。回写核对 generation、lease、业务状态、租约时间，再校验磁盘 Draft 的 specId/revision/digest；成功只允许提交一次。

| 崩溃窗口 | 恢复方式 |
|---|---|
| 保存后未入队 | 无分离入队步骤；持久化 PENDING 在下次扫描或启动后被领取 |
| 领取后未调用 | 启动将遗留 RUNNING 记录为中断，保留 attempt 并进入有界重试 |
| 调用后未保存 | 使用原输入和剩余预算重算；孤立 Draft 文件不成为有效 Spec |
| 保存后未确认 | 成功 Job、Spec 引用、审批阶段和事件同事务，重启/重复完成不再生成、不再进入审批 |

Draft 文件新增独立 attempt 子目录：`.paicli/spec-drafts/{changeId}/{generation}-{attempt}-{lease}/{specId}-r{revision}.md`。取消或过期生成只能留下不被引用的文件，不能覆盖当前版本。历史 revision 从已提交的生成事件解析路径和 digest，继续经过 Artifact 根目录、符号链接和大小检查；兼容旧平铺路径。锁定 Spec 路径不变。M1 不自动删除孤立文件或历史事件。

启动恢复只处理未锁定的 `CREATED / DRAFTING_SPEC`：旧版本没有 Job 的行从已保存输入补建 Job；已有 RUNNING 消耗的 attempt 不重置；已审批/锁定、失败、取消任务不会静默重新生成。旧版 FAILED 行缺少生成身份，保持历史状态，不能直接走新重试端点。

升级前停止单一服务并备份数据目录。迁移是增量兼容读取；不要同时运行两个版本，也不要在存在 M1 活跃任务时降级旧服务（旧服务不了解 Job 和嵌套 Draft 路径）。需要回退时停止服务并恢复升级前备份。

## 重试、超时与取消

每个 generation 最多 3 次基础设施 attempt，默认每次租约/总生成超时 600 秒；第一次失败退避 1 秒、第二次 2 秒，时间和次数均持久化。网络 I/O、HTTP 408/425/429/5xx 和进程中断可重试；HTTP 400/401/403/404 等、文件系统异常、内容资格失败不自动重试。公开失败原因只包含安全分类/HTTP 状态，不回灌 provider 响应体或密钥。

Draft Generator 内容结构/语义纠错仍最多调用两次，独立于基础设施 attempt；单 generation 最多六次模型请求起始机会。Draft 的 HTTP scope 关闭 OkHttp 隐式重试，避免绕过调度预算；原 CLI 不改变重试设置。显式用户重试建立新的 generation/预算并记录事件。

用户取消、超时或停机先持久化失效，再中断线程；OpenAI-compatible HTTP 在本次 Draft scope 内直接 `Call.cancel()`，不会取消共享 client 的其他任务。不可取消的第三方实现返回后，其结果被版本/租约校验丢弃。为避免无限增建线程，这类调用在真正退出前仍占一个并发槽；两个实现都永久不响应取消时，需重启服务恢复调度。此边界不影响取消状态的持久化和页面响应，也不等于生产进程隔离。

## Web 与验收

提交请求期间立即禁用重复提交；收到 201 后区分后台生成、草稿完成与失败。页面展示目标 revision、attempt、后台状态、退避时间和安全失败原因，并提供取消/重试。自动轮询从 1 秒退避到最多 10 秒，状态变化后重置；终态/待审批停止，暂停按钮、页面隐藏、离开页面和断开连接均停止轮询。URL fragment 只保存 changeId；刷新后重新输入 Key，可继续跟踪所选任务。Key 仍只驻留页面内存。所有不可信文本继续用 textContent，409 刷新快照并要求重新操作，不自动重放。

验收入口：

```bash
mvn test -Dtest='com.paicli.change.*Test,FileChangeSpecModuleTest,SpecDraftGeneratorTest,LlmCallCancellationTest,AbstractOpenAiCompatibleClientImageInputTest' -DskipTests=false
node --test src/test/js/change-web-creation.test.cjs
mvn test -Pquick
```

- `DraftRecoveryTest`：四类崩溃窗口、SQLite 重新打开、预算/退避、手动重试历史、取消/替代/租约过期回写、并发重复领取、旧库迁移、锁定 digest 保持。
- `DraftJobRunnerTest`：挂起 stub 时 HTTP 已返回、幂等重复创建、取消中断、不可取消调用的迟到结果、真实时钟超时与预算、内容资格失败、HTTP retry/cancel 和过期版本。
- `LlmCallCancellationTest`：真实回环 HTTP 取消只影响当前 scope、后续同线程调用正常、HTTP 错误分类。
- 原 Workflow/Store/Artifact/API/EndToEnd/OfflineDemo 测试显式等待或驱动 Draft 阶段，继续覆盖补充 revision、审批、验证与 Mock 发布。
- Web 测试：延迟创建、后台状态、失败、按钮恢复、退避/停止、hash 恢复跟踪、重试身份绑定、409 不重放和不可信文本。

2026-09-04 收束结果：

- 针对性：81 tests，0 failures/errors，0 skipped。之后补充的整个平台 Draft 停机/启动测试一并进入 quick。
- quick：911 tests，0 failures/errors，5 个预期 skipped；没有运行付费 Profile。
- Node Web 回归：6 tests 全通过；`mvn package -DskipTests` 成功。
- 浏览器：使用独立临时目录和回环延迟/失败 stub，确认创建返回 DRAFTING_SPEC 并恢复提交按钮；资格失败显示失败原因和重试入口；新 generation 生成后进入 SPEC_REVIEW，旧失败事件保留；刷新清空 Key，重新连接自动回到 URL fragment 的任务；服务重启保留 attempt 并记录中断恢复；长耗时生成可取消并显示 CANCELED。含 HTML 片段的需求显示为文本。页面布局已检查。
- 浏览器发现创建区提示可能停留在生成中，已让它跟随后台结果更新，并增加相应前端断言。浏览器辅助入口最初采用 Java source-file 运行，访问测试 helper 时触发类加载问题；改为编译后运行完成验收。辅助文件和数据仅留在系统临时目录，不进入产品或仓库。
