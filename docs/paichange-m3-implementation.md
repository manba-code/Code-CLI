# M3：组织工具策略与审批执行

实施日期：2026-09-05。范围仅 PaiChange Worker 的项目级工具策略、持久化逐调用审批、M5 权限接入、Web 审批和离线验收；不包含真实 SCM（M4）、容器/网络/Secret 隔离（M6）或付费模型评测。

## 1. 交付结果

`ExecutionRoute.toolPolicy` 不再只是审计标签。`STANDARD / RESTRICTED / LOCKED_DOWN` 由 `ProjectToolPolicy` 计算 `ALLOW / REQUIRE_APPROVAL / DENY`，项目可用带版本的规则按 profile、工具名、命令正则、MCP server/tool、工作目录前缀和参数正则收窄或放行；同一调用命中多条规则时 `DENY` 优先，其次是 `REQUIRE_APPROVAL`。未知工具默认拒绝。

默认矩阵如下：

| 工具 | STANDARD | RESTRICTED | LOCKED_DOWN |
|---|---|---|---|
| 本地只读工具 | 允许 | 允许 | 允许 |
| `write_file` / `execute_command` | 允许 | 逐调用审批 | 逐调用审批 |
| `web_search` / `web_fetch`、`mcp__*` | 逐调用审批 | 拒绝 | 拒绝 |
| 未知或未列入 Worker 白名单 | 拒绝 | 拒绝 | 拒绝 |

策略只决定是否允许进入原工具执行链。批准后仍由原 `ToolRegistry` 执行 PathGuard、CommandGuard、写入大小、浏览器及 MCP 边界；工具批准不能覆盖底层拒绝。`GovernedToolRegistry` 同时供 Agent 初次执行、同会话 Evidence 修复和锁定 command Verifier 使用，因此修复与 Verifier 不会得到更宽权限。Worker ReAct 输入还会明确告知当前 profile、拒绝/超时不得声称已执行且不可换工具绕过；安全边界仍由代码强制而非依赖提示词。CLI 的交互 HITL 和微信非交互默认拒绝路径没有改动。

## 2. 持久化审批与恢复语义

每个需确认的调用保存一个 `ToolApproval`，绑定：

- changeId、projectId、runId、callId；
- 工具名、规范化 JSON 参数的 SHA-256、工作目录；
- specId、revision、specDigest；
- route profile、策略 version 和命中 rule；
- 状态、审批主体、时间和失效原因。

原始工具参数不写入 SQLite 或 Change Event。页面和事件只展示最多 2,000 字符的脱敏摘要；Secret 字段、Bearer/环境变量式凭据会替换，`content / body / data` 只保留长度。批准请求必须回传当前 policy version、参数 digest、callId、runId 和 specDigest；任一不符返回 409，不自动重放。

审批由 M5 的 `APPROVE_TOOL` 动作控制，仅 HUMAN Principal 可批准。共享身份模式下 RESTRICTED/LOCKED_DOWN 禁止 requester 自批；本地单操作者兼容模式保留显式例外。批准完成后、真正执行前会再次检查任务状态、Spec、route、策略版本和审批者当前权限；撤权或策略变化会把旧批准安全失效。

默认最多允许一个 Worker 占用审批等待槽，第二个需等待的调用立即保守拒绝，避免两个默认 Worker 全被人工等待占满。等待超时默认 300 秒，可用 `PAICHANGE_TOOL_APPROVAL_TIMEOUT_SECONDS` 或 JVM 属性 `paichange.tool.approval.timeout.seconds` 设置 1–3,600 秒，JVM 属性优先。拒绝、超时、线程中断和平台关闭都不会执行调用。

进程重启后，数据库中遗留的 `PENDING` 调用统一转为 `INTERRUPTED`。原 Java 执行栈和工具结果不被假定可恢复，对应恢复 Job 安全取消业务执行；系统不会自动执行未确认调用，也不会重放结果不明的副作用调用。第一版因此不是可跨进程续接的 Agent 检查点。

## 3. 存储、API 与 Web

SQLite 增量创建 `project_tool_policies`、`tool_approvals` 和查询索引。审批创建、决策、失效及对应 `tool.approval_*` 事件在同一数据库事务提交；策略更新使用 expectedVersion，并立即使旧版本的待审批请求失效。

新增 API：

- `GET /v1/changes/projects/{projectId}/tool-policy`：读取项目策略，要求 `READ_TASK`。
- `PUT /v1/changes/projects/{projectId}/tool-policy`：以 `expectedVersion + rules` 更新，要求 `MANAGE_TOOL_POLICY`。
- `GET /v1/changes/{changeId}/tool-approvals`：读取当前任务的审批/策略拒绝历史，要求 `READ_TASK`。
- `POST /v1/changes/{changeId}/tool-approvals/{approvalId}/decisions`：`APPROVE / REJECT`，要求 `APPROVE_TOOL` 并携带精确调用身份。

`GET /v1/changes/capabilities` 通过 `toolPolicyEnforced` 和 `workerHitl` 报告装配状态。Web 将 “Worker 工具审批” 与 Spec、Human Evidence、Delivery Approval 分开展示，只渲染服务端脱敏摘要；409 会刷新并要求重新核对，不会自动重放决策。

## 4. 离线验收入口

常规离线演示继续使用 STANDARD，保持原五分钟流程不额外阻塞。需要验收完整工具审批链时显式增加：

```bash
java -Dpaichange.demo=true \
  -Dpaichange.demo.tool.approvals=true \
  -Dpaichange.demo.dir=/absolute/path/to/new-demo-data \
  -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8086
```

该模式把固定退款 fixture 路由为 LOCKED_DOWN：初次写入、首轮 command Verifier、一次修复写入和修复后 Verifier 各产生一条独立审批。四条调用都批准后仍由真实 worktree、Verifier 和一次修复路径完成；随后按原流程执行 Delivery Approval 与 Mock success。每次演示使用新目录，不删除旧任务。

## 5. 验收与边界

针对性测试覆盖三 profile 矩阵、规则优先级及参数/命令/MCP/cwd 匹配、精确身份、策略更新失效、权限与服务账号拒绝、脱敏、等待容量、超时/关闭、PathGuard/CommandGuard 不可绕过、SQLite 重启、启动中断不重放、HTTP RBAC、Web 409 和 LOCKED_DOWN 初次执行/Verifier/修复完整闭环。

2026-09-05 验收结果：M3 定向并包含 M1/M2/M5 的联合回归为 27 个测试类、168 项，0 failures/errors/skipped；Node Web 15 项全部通过；`mvn test -Pquick` 为 941 项，0 failures/errors、5 skipped。浏览器以全新离线目录验证错误 Key 401、LOCKED_DOWN route、初次写入/首轮 Verifier/修复写入/修复后 Verifier 四条独立审批、不同 callId、正文长度化脱敏、事件审计、首轮 FAIL、一次修复后 PASS、Delivery Approval、Mock success 与 COMPLETED；页面视觉状态正常。没有运行付费模型评测或连接真实 SCM。

M3 仍不是生产沙箱。Worker 继续运行在本机进程和 worktree 中；没有容器资源限制、网络出口隔离、短期 Secret、不可变 Evidence 存储或跨节点队列。项目策略与成员提供方仍是本地控制面实现，具体 OIDC/组织目录属于部署集成；真实 SCM 仍为 SQLite Mock。这些边界分别留给 M6、身份部署和 M4，不能把 M3 描述为共享不可信代码的生产就绪方案。

后续状态说明（2026-09-06）：上述段落是 M3 收束时的历史边界。M6a 已为 PaiChange shell/command Verifier 增加可选任务级 Docker 执行平面、默认拒绝网络、短期文件型 Secret seam 和控制面 Evidence 哈希归档，详见 [M6a 实施记录](paichange-m6a-implementation.md)。M3 策略仍在宿主控制面先行裁决，批准不能覆盖 M6a 网络/容器或原 PathGuard/CommandGuard 的拒绝；普通 CLI 与未启用 Docker 的本地演示仍无容器边界。
