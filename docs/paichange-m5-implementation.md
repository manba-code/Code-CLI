# M5：身份认证与 RBAC

实施日期：2026-09-05。本文记录 M5 交付时的服务端身份边界、项目级 RBAC、M1/M2 动作鉴权、职责分离与 Web 登录态；当时不包含组织工具策略（M3）、真实 SCM（M4）、执行隔离/生产部署（M6）或任何付费模型评测。M3 后续已完成并复用本期的 `APPROVE_TOOL` / `MANAGE_TOOL_POLICY` 权限，见 [M3 实施记录](paichange-m3-implementation.md)。

## 身份与认证边界

`RuntimeApiServer` 在进入 threads 或 Change API 前通过 `PrincipalAdapter` 验证凭据，并把不可由请求体覆盖的 `Principal` 放入当前 `HttpExchange`。Principal 使用稳定 `subjectId`，另存展示名、`HUMAN / SERVICE` 类型、issuer、可选过期时间和仅供本地模式使用的 `localTrusted` 标记；职责分离只比较 subjectId，不比较展示名。

- `LocalApiKeyPrincipalAdapter` 保留原单 API Key 入口，但一个 Key 永远映射为服务端固定的 `local-user`。它只适合监听 `127.0.0.1` 的单操作者可信模式，不是共享部署身份方案。
- `LocalPrincipalAdapter` 用服务端 token→Principal 映射支持本地开发、权限矩阵、过期和撤销测试；token 不能充当 actorId。
- `OidcPrincipalAdapter` 与 `OidcPrincipalVerifier` 定义可插拔边界。具体部署必须在 Verifier 内校验签名、issuer、audience、expiry 和必需 claims；当前没有指定 IdP，因此没有虚构任何外部 OIDC 登录、发现端点或密钥轮换集成。
- 本期不使用 Cookie 会话，因此没有新增 CSRF/Cookie 生命周期。Web 凭据只在页面内存中使用；401 会清除页面登录态和轮询。

`GET /v1/changes/capabilities` 返回当前 Principal、认证模式、成员关系和部署边界。未认证或过期返回 401 与 `WWW-Authenticate`；已认证但无权限返回 403。

## 项目与权限模型

项目边界由持久化 `RepositoryRef.repository` 的 SHA-256 派生为稳定、不直接泄漏本地路径的 `projectId`。任务创建前按请求仓库检查项目；既有任务的详情、事件、Artifact 和动作全部先从持久化任务解析同一 projectId，再进入统一 `ChangeAuthorizer`。列表只返回具有 `READ_TASK` 的项目，不能通过 changeId、事件游标或 Artifact 参数跨项目读取。

角色到动作的矩阵如下：

| 角色 | 允许动作 |
|---|---|
| `VIEWER` | 任务详情、列表、事件、Artifact |
| `DEVELOPER` | VIEWER + 创建任务、取消/重试 Draft、SUPPLEMENT Spec |
| `APPROVER` | VIEWER + Spec APPROVE/REJECT、Human Evidence、Delivery APPROVE/REJECT，以及供 M3 复用的工具审批权限 |
| `PROJECT_ADMIN` | 全部当前项目动作和连接/策略/成员管理权限；不豁免职责分离 |

成员关系通过 `ProjectMembershipProvider` 每次请求重新读取；撤权不依赖旧页面快照或长期 session 缓存。`InMemoryProjectMemberships` 是当前可测试实现，平台构造器可注入其他持久化/组织目录实现。服务账号可以拥有读取、开发和管理类动作，但 `APPROVE_SPEC / RECORD_HUMAN_EVIDENCE / APPROVE_DELIVERY` 会被强制剥离；机器账号不能冒充真人验收。

## actor、审计与职责分离

HTTP 请求中的 `actorId` 现在是可选兼容断言：存在时必须与当前 Principal.subjectId 完全一致，不一致返回 403；缺失时由服务端直接注入当前 subject。请求不能决定 actor 类型。任务 requesterId、审批 approverId、Human Evidence actorId 和事件 actorId 均来自该服务端主体；新事件记录 `HUMAN / SERVICE`，Human Evidence 条目也保存 actorType。内部非 HTTP 旧调用保留 `LEGACY` 类型构造器兼容。

默认策略对 MEDIUM/HIGH 强制：

1. requester 不能批准自己的 Spec 或 Delivery；
2. 同一主体不能同时完成 Spec Approval 和 Delivery Approval；
3. `PROJECT_ADMIN` 也必须遵守以上规则；
4. 恢复/重试发布前会重新执行同一 Delivery 职责分离检查。

离线固定演示只有一个本地身份，显式关闭了既有的中高风险自批限制以保持单操作者演示闭环；页面和文档明确这是本地模拟例外。正常 `serve --http` 仍使用默认强制策略。共享环境必须装配多 Principal Adapter 与项目成员源，不能复用这一演示例外。

## Web 与 API 迁移

Web 不再收集发起人、验收人或审批人 actorId；登录成功后展示服务端 Principal 类型，任务响应包含 `projectId` 和当前动作 `permissions`。页面按权限隐藏 Draft、Human Evidence 和审批按钮，并对 401/403 给出不同反馈；这些隐藏不构成安全边界，服务端对每次请求重新鉴权。

旧 HTTP 客户端迁移规则：

- 可以删除所有 `actorId` 字段；服务端将使用认证 subject。
- 暂时保留字段时，值必须等于认证 subject；依赖“同一 API Key + 任意 actorId”模拟多人会收到 403。
- 原单 Key 仍可访问 threads 和本地 Change 数据，但身份固定为 `local-user`。默认职责分离下，单一主体不能完成自己发起的 MEDIUM/HIGH 审批。
- 旧数据库无需新增 RBAC 列；既有 requester/approval 保留。旧审计类型保持历史值，新可信请求写 `HUMAN / SERVICE`。HumanReview JSON 对缺少 actorType 的旧条目按 `LEGACY` 读取。

## 验收入口与剩余边界

```bash
mvn test -Dtest='com.paicli.change.*Test,FileChangeSpecModuleTest,SpecDraftGeneratorTest,LlmCallCancellationTest,AbstractOpenAiCompatibleClientImageInputTest' -DskipTests=false
node --test src/test/js/change-web-creation.test.cjs
mvn test -Pquick
```

`ChangeAuthorizerTest` 覆盖四角色动作矩阵、项目隔离、服务账号和撤权；`PrincipalAdapterTest` 覆盖 token 映射、过期、撤销和 OIDC 边界；`ChangeRbacApiTest` 覆盖 401/403、伪造 actor、创建/读取/列表过滤、事件/Artifact、Draft、Spec/Human/Delivery 动作、跨项目访问、审计和管理员自批。M1/M2 API、Workflow、恢复、Mock 发布和 Web 测试继续作为回归。

2026-09-05 验收结果：M1/M2/M5 联合针对性 98 项通过；Node Web 14 项通过；`mvn test -Pquick` 为 929 项、0 failures/errors、5 skipped。浏览器以全新离线目录验证了错误 Key 的 401 展示、固定 `local-user / HUMAN` Principal、本地部署警告、无 actor 输入的创建、审计时间线中的服务端主体，以及 Spec Approval → 确定性一次修复 → Delivery Approval → Mock success / COMPLETED 完整链路。浏览器验收使用离线确定性替身，没有真实模型或真实 SCM 调用。

剩余边界：没有具体 OIDC 提供方、登录跳转/回调、组织目录同步、持久化成员管理 UI、SCIM、真实 SCM 权限映射或生产 Secret/会话基础设施。`InMemoryProjectMemberships` 不是生产成员数据库；`LocalApiKeyPrincipalAdapter` 不是共享身份方案。M5 当期只预留工具审批及连接/策略管理动作；M3 现已把工具审批和策略管理接入 Worker/API，但没有改变上述身份部署边界。
