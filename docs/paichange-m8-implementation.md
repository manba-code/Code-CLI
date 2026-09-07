# PaiChange M8：GitHub/GitLab 与简历发布本地收口

> 状态：本地实现、真实 GitHub、普通/容器 CI 与 Release 已完成；真实 GitLab 待验收

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

2026-09-07 本机结果：SCM/装配/启动/健康针对性 18 tests、Node Web 18 tests、M7b 容器 profile 5 tests、独立恢复 1 test、quick 990 tests（0 failures、0 errors、16 skipped）全部通过；`mvn package -DskipTests` 成功生成 shaded JAR。容器恢复演练仍为小 fixture，实测备份 1 秒、RPO 2 秒、RTO 2 秒，不代表生产 SLO。

GitHub Actions 文件：

- `.github/workflows/ci.yml`：push/PR 的 quick、Node Web 和 package。
- `.github/workflows/container-integration.yml`：仅 `workflow_dispatch` 的 M6b/M7a/M7b 本地容器脚本。
- `.github/workflows/release.yml`：`v*` Tag 上完成 deterministic regression、JAR、SHA-256 和 GitHub Release artifact。

这些 workflow 不配置模型 Key，不运行 `agent-eval` / `change-spec-eval`，也不执行真实外部 SCM 写入。

## 真实 GitHub 验收

2026-09-07 使用专用 [Issue #1](https://github.com/manba-code/Code-CLI/issues/1) 和最小权限环境 Token 完成真实写入。最终验收对象为 [PR #3](https://github.com/manba-code/Code-CLI/pull/3)：

- ChangeTask：`change_5a79452cea98`
- 任务分支：`paichange/change_5a79452cea98/febd7e01`
- head SHA：`9193af3d8b19bde685a880523a320234b7ff2c4a`
- Spec digest：`9ef9a7d2da2cc6b7e504832214779cd5897aba5190741d1e758db48059fe6f04`
- run：`RUN-20260907-044215-773-9991ac14`
- publication identity：`7e232f14346f9d7efa7bc3e08f8be9db762177d8190d77a081e07e62c8c9e3ff`
- 幂等计数：Issue 导入任务 1、精确分支 1、open PR 1、head-bound `PaiChange` success Status 1、publication ledger 1。
- Evidence 在 Delivery Approval 前由 `TrustedEvidenceStore` 复核为 `VERIFIED`；Spec 与 Delivery 分别由不同 HUMAN 主体批准。跨项目主体读取和 SERVICE 审批均返回 403，无效 GitHub Token 返回无凭据内容的鉴权失败。
- 在远端 PR/Status 写入后用 SQLite trigger 注入 `change.completed` 事件落账失败，HTTP 返回 500 且业务状态保持 `PUBLISHING`，没有误标 `COMPLETED`。随后删除隔离验收库中的本地 publication 回执并重启，以远端精确 branch/head/PR/status 对账，最终进入 `COMPLETED`；远端计数仍为 1/1，没有重复 PR 或 Status。

验收 harness 断言诊断阶段产生的 [PR #2](https://github.com/manba-code/Code-CLI/pull/2) 属于独立 ChangeTask `change_facebb6e6924` 和 publication `8f69e5bdbb83232024ed222bfa5eadb253d686fdde09b33d1de2e8e21073830b`，不是 PR #3 的重复投递。它保持 open、未合并，任务分支也未删除，符合本轮禁止合并和删除分支的约束。

普通 CI [run 34078784313](https://github.com/manba-code/Code-CLI/actions/runs/34078784313) 与 `v16.1.1` Release [run 34079109698](https://github.com/manba-code/Code-CLI/actions/runs/34079109698) 均通过。可下载 [Release](https://github.com/manba-code/Code-CLI/releases/tag/v16.1.1) 的 JAR 为 35,069,550 bytes，SHA-256 为 `d0ad490d5a3def6c693eed22fea2f630d9e1d98867597eb7571046619b208347`；独立下载校验一致，空环境 `--help` smoke 退出码为 0。

手动容器矩阵 [run 34089756890](https://github.com/manba-code/Code-CLI/actions/runs/34089756890) 绑定分支 head `b4ee84924971ad9fe0a8facca79f83101df390ce`，M6b、M7a、M7b 三个 job 均一次通过。脱敏 Surefire Artifact 复核数字为 M6b 1 test、M7a 1 test、M7b 6 tests，全部 0 failures、0 errors、0 skipped；三份 Artifact 均由 GitHub 返回独立 SHA-256 digest。

## 尚未完成

真实 GitLab 验收仍需要专用 project、Issue IID、最小权限 Token 和操作者明确授权；本轮按授权只执行 GitHub。真实 GitLab MR URL 和录屏尚未生成。登录页、Refresh Token、SCIM、多 IdP、Jira、Webhook、自动合并、HA、Kubernetes、MCP OAuth、完整 LSP 和微信媒体不属于本期。
