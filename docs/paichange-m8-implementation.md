# PaiChange M8：GitHub/GitLab 与简历发布本地收口

> 状态：本地实现完成；真实 SCM 写入、托管 CI 运行与 Release Tag 待验收

## 实现范围

M8 复用 `WorkItemAdapter`、`ScmAdapter`、`ScmPublicationLedger` 与 `DefaultChangeWorkflow`。新增 `GitHubSettings`、`GitHubClient`、`GitHubWorkItemAdapter`、`GitHubScmAdapter`，并由 `ConfiguredScm` 在唯一装配点严格选择 `mock|gitlab|github`。Workflow、Worker、API、RBAC、OIDC、成员目录、Evidence 和业务状态机均未加入 provider 条件。

GitHub 闭环使用 Issue number 导入固定 owner/repository，推送精确任务分支，回读远端 ref，按 head/base 对账或创建 open PR，并发布绑定已验证 head SHA 的 Commit Status。PR/status 请求在超时、5xx 或响应丢失后先读取远端精确身份；本地 ledger 或完成事件落账失败时重放也不会创建第二个 PR/status。

## 安全与 fail-closed 边界

- 远端 branch 或 PR head 与已验证 SHA 不一致时拒绝完成。
- closed/merged PR 不被当作 open PR 复用，也不会被盲目替换。
- 401/403/429/5xx、超时、协议字段缺失和本地落账失败均保持任务未完成。
- 发布资格仍只由 `DefaultChangeWorkflow` 根据当前 Spec、Verdict/Evidence、Human Evidence、成员权限及两阶段审批决定。
- GitHub/GitLab Token 只从进程环境读取。REST 使用鉴权 Header，HTTP Git 使用子进程环境中的临时 Header；Token 不进入浏览器、请求正文、Git 命令参数、事件、Artifact、指标或错误正文。
- 远程 API 必须 HTTPS，只有 loopback 测试允许 HTTP；生产启动还验证 checkout、base ref、remote 与远程仓库身份。

## 本地确定性验证

`GitHubScmEndToEndTest` 使用 `127.0.0.1` 假 GitHub 和临时 bare Git remote，覆盖 Issue 幂等、真实 push、PR/status HTTP timeout 后对账、完成事件落账失败重放、head 冲突、closed PR、401/429、畸形响应和 Token 脱敏。`GitLabScmEndToEndTest` 保持同批回归。

2026-09-07 本机结果：SCM/装配/启动/健康针对性 18 tests、Node Web 18 tests、M7b 容器 profile 5 tests、独立恢复 1 test、quick 989 tests（0 failures、0 errors、16 skipped）全部通过；`mvn package -DskipTests` 成功生成 shaded JAR。容器恢复演练仍为小 fixture，实测备份 1 秒、RPO 2 秒、RTO 2 秒，不代表生产 SLO。

GitHub Actions 文件：

- `.github/workflows/ci.yml`：push/PR 的 quick、Node Web 和 package。
- `.github/workflows/container-integration.yml`：仅 `workflow_dispatch` 的 M6b/M7a/M7b 本地容器脚本。
- `.github/workflows/release.yml`：`v*` Tag 上完成 deterministic regression、JAR、SHA-256 和 GitHub Release artifact。

这些 workflow 不配置模型 Key，不运行 `agent-eval` / `change-spec-eval`，也不执行真实外部 SCM 写入。

## 尚未完成

真实 GitHub/GitLab 验收需要专用测试仓库、Issue、最小权限 Token 和操作者明确授权；本轮未执行。GitHub Actions 需在远端仓库实际运行后才能形成公开 CI 证据；Release Tag、可下载 JAR/SHA-256、真实 PR/MR URL 和录屏也尚未生成。登录页、Refresh Token、SCIM、多 IdP、Jira、Webhook、自动合并、HA、Kubernetes、MCP OAuth、完整 LSP 和微信媒体不属于本期。
