# PaiChange M7a：生产身份与成员目录最小切片

> 实施日期：2026-09-06  
> 范围：单 issuer OIDC JWT/JWKS 验证、PostgreSQL 项目成员目录、成员管理 API/审计、生产装配和本地容器验收  
> 明确不包含：登录页面、Refresh Token、SCIM、组织目录同步、多 IdP、多租户联合登录、高可用或 Kubernetes

## 1. 交付边界

M7a 沿用 M5 的 `PrincipalAdapter`、`ProjectMembershipProvider`、`ChangeAuthorizer` 和既有四角色 RBAC，没有建立第二套授权矩阵。生产请求先由 `JwksOidcPrincipalVerifier` 验证 JWT，再由 `OidcPrincipalAdapter` 建立服务端可信 `Principal`；`ChangeAuthorizer` 在每个请求上读取 PostgreSQL 当前成员关系，因此新增、改权和移除会在下一请求即时生效。

当前只支持一个显式配置的 issuer、audience 和 JWKS URI。JWT 必须带 `alg`、`kid`、`iss`、`aud`、`sub`、`exp`；`nbf` 存在时必须有效。验签只接受服务端 allowlist 中的 `RS256 / RS384 / RS512`，拒绝 `none`、算法替换、未知 key、弱于 2048 bit 的 RSA key、重复 `kid`、非签名用途 key、过期或尚未生效 Token、issuer/audience 不匹配。JWKS 有 TTL cache；未知 `kid` 或签名失败会强制刷新一次，以覆盖 key rotation，刷新后仍失败则返回 401。JWKS 响应上限 1 MiB，生产只允许 HTTPS；loopback HTTP 只供本地测试。

`principal_type` claim 缺省为 `HUMAN`，只接受 `HUMAN` 或 `SERVICE`。成员目录同时保存预期 principal type；Token claim 的类型与目录不一致时，该成员关系不授权，防止把 SERVICE subject 重新签成 HUMAN 后获得真人权限。M5 原有职责分离继续有效；M7a 另将 `MANAGE_MEMBERS` 设为 human-only，SERVICE 即使被赋予 `PROJECT_ADMIN` 也不能查询或变更成员。

默认 localhost 模式和 `-Dpaichange.demo=true` 仍使用固定 `PAICLI_RUNTIME_API_KEY -> local-user`，不读取或启用生产 OIDC/成员目录。只有显式 `PAICHANGE_STORAGE=postgresql` 的生产路径才强制装配 OIDC + PostgreSQL directory；配置不完整时启动 fail closed。

## 2. PostgreSQL V2

`PostgresStorageMigrations.CURRENT_VERSION` 升级为 V2。迁移仍是持有 migration 表排他锁、按版本顺序执行、每版校验 checksum 的前向迁移；旧 V1 数据原样保留。

`project_members` 以 `(project_id, subject_id)` 为主键，保存：

- `principal_type`、JSONB roles、乐观锁 `version`；
- `created_at / updated_at`；
- `created_by_subject / created_by_type / updated_by_subject / updated_by_type`。

`project_member_audit` 追加保存 `BOOTSTRAP / ADD / UPDATE / REMOVE`、变更前后版本、principal type、roles、actor 和时间。成员行与审计行在同一数据库事务提交。写操作使用 project-scoped PostgreSQL advisory transaction lock，再检查 expected version；同一旧版本的并发修改只能有一个成功。降级或移除最后一个 `HUMAN + PROJECT_ADMIN` 会返回 409。

## 3. 首次 bootstrap

生产配置必须提供一个稳定的 `PAICHANGE_BOOTSTRAP_ADMIN_SUBJECT`。只有该 subject 的有效 HUMAN OIDC Token，且目标 project 当前完全没有成员时，才能用成员 PUT 将自己初始化为唯一 `PROJECT_ADMIN`：

```json
{
  "subjectId": "bootstrap-admin-subject",
  "principalType": "HUMAN",
  "roles": ["PROJECT_ADMIN"],
  "expectedVersion": 0
}
```

空目录判断、project 锁、插入和 `BOOTSTRAP` 审计在一个 PostgreSQL 事务内完成。项目一旦存在任意成员，bootstrap 配置不再绕过数据库权限；后续所有查询和写入都要求数据库中的 `PROJECT_ADMIN` 成员关系。它不是全局 super-admin，也不创建登录 session。

## 4. 成员 API

所有端点都需要有效 Bearer JWT；只有 HUMAN `PROJECT_ADMIN` 可用。响应设置 `Cache-Control: no-store`。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/v1/changes/projects/{projectId}/members` | 按 subject 查询项目当前成员 |
| `PUT` | `/v1/changes/projects/{projectId}/members` | 添加或更新；body 提供 subjectId、principalType、roles、expectedVersion |
| `DELETE` | `/v1/changes/projects/{projectId}/members` | 移除；body 提供 subjectId、expectedVersion |
| `GET` | `/v1/changes/projects/{projectId}/members/audit` | 顺序读取不可覆盖的成员变更审计 |

创建时 `expectedVersion=0`，成功返回 version 1；更新/移除必须携带当前正版本。过期版本、重复初始化、目标不存在和最后管理员保护返回 409；无项目权限或 SERVICE 管理请求返回 403；Token/JWKS/claims 验证失败返回 401。

## 5. 生产配置样例

M6b 的 PostgreSQL、S3-compatible、GitLab 配置保持不变，额外增加：

```bash
export PAICHANGE_AUTH=oidc
export PAICHANGE_OIDC_ISSUER='https://id.example.com/'
export PAICHANGE_OIDC_AUDIENCE='paichange-api'
export PAICHANGE_OIDC_JWKS_URI='https://id.example.com/.well-known/jwks.json'
export PAICHANGE_OIDC_ALGORITHMS='RS256'
export PAICHANGE_OIDC_JWKS_CACHE_SECONDS=300
export PAICHANGE_OIDC_HTTP_TIMEOUT_SECONDS=10
export PAICHANGE_BOOTSTRAP_ADMIN_SUBJECT='stable-idp-subject'
```

issuer 按字符串精确匹配，配置中的尾斜杠必须与 Token `iss` 一致。不要把 client secret、Refresh Token 或浏览器登录回调配置到本切片；M7a 只验证调用方已经取得的 Bearer JWT。部署前应限制数据库账号权限、保护 PostgreSQL/S3/GitLab 凭据，并按 IdP 的实际 rotation 时序设置 JWKS cache TTL。

## 6. 迁移与回滚

升级：

1. 备份 PostgreSQL，并在停写窗口验证备份可恢复。
2. 用新版本在副本或临时库执行启动 migration，确认 V1、V2 checksum 和新表。
3. 配置 OIDC 与 bootstrap subject，再启动单实例；配置缺失时不要退回本地 API Key。
4. 使用 bootstrap admin 初始化每个需要开放的 project，再添加至少一个备用 HUMAN `PROJECT_ADMIN`。
5. 验证成员查询、即时撤权、审计导出和正常 Change RBAC 后开放流量。

回滚：V2 是前向 schema，旧二进制会检测到数据库版本高于支持版本并拒绝写入。若尚未创建成员，可停服务、恢复升级前数据库备份并回退二进制。若已经写入成员或审计，不能直接删除 V2 或恢复旧备份，否则会丢失授权事实；应保持新版本、修复前滚，或先导出并人工迁移新增数据。紧急手工删表/删 migration row 不是受支持的在线回滚流程。

## 7. 本地容器验收

```bash
./docker/run-paichange-m7a-tests.sh
```

脚本启动临时 PostgreSQL 17.6；测试进程内启动 loopback 假 OIDC/JWKS，然后运行 `M7aIdentityIntegrationTest`，最终删除容器、网络和 tmpfs 数据。覆盖合法登录、JWKS 新 `kid` rotation、过期/伪造 Token、issuer/audience mismatch、跨项目 403、SERVICE 管理拒绝、存储 principal type 防提权、一次性 bootstrap、成员改权/撤权即时生效、并发 expectedVersion 冲突、最后 HUMAN 管理员保护、重启恢复和 actor 审计。

2026-09-06 本机验收：`M7aIdentityIntegrationTest` 1 项通过，并显式重建历史 V1 状态后验证 V1→V2 前向升级；M6b PostgreSQL/MinIO 容器闭环 1 项复跑通过；受影响身份、RBAC、成员、API、工具审批与存储回归通过；Node Web 17 项通过；`mvn test -Pquick` 为 975 项、0 failures/errors、14 skipped；`mvn package -DskipTests` 成功。所有临时容器与网络已清理。未连接真实 IdP/SCM，未运行付费模型评测；这些结果不证明 HA、容量、备份恢复或生产 IdP 兼容性。

## 8. 已知边界

- 没有 Authorization Code 登录、浏览器页面、Refresh Token、logout 或 session；调用方自行取得短期 Bearer JWT。
- 没有 OIDC discovery、多 issuer/multi-tenant 路由、SCIM、组织目录同步或组到角色映射。
- JWKS 是单进程内存 cache，不跨节点协调；这不构成 HA 身份基础设施。
- 成员 API 没有配套 Web UI；审计保存在 PostgreSQL，尚未接 SIEM、保留策略或外部不可变审计库。
- 本地假 IdP/PostgreSQL 只证明最小闭环。真实 IdP claim 约定、TLS/代理、JWKS rotation 演练、数据库备份恢复和故障手册仍需目标环境验收。
