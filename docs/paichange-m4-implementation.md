# PaiChange M4：单一 GitLab SCM 最小闭环

> 实施日期：2026-09-06  
> 状态：代码与本地 HTTP 假 GitLab 闭环完成；真实 GitLab 验收已暂缓（当前无法提供专用测试 project），等待后续单独提供测试 project、Issue 与最小权限 Token 后恢复

## 1. 交付范围

M4 只接入一个服务端配置的 GitLab project，完成：

```text
GitLab Issue IID -> ChangeTask -> paichange/* 分支 -> GitLab MR -> commit status
```

本阶段没有实现 Webhook、多 SCM、自动合并、具体 OIDC、M6b 或生产部署。原 `-Dpaichange.demo=true` 和 Mock fixture/PR/Check 保持离线、无网络。

## 2. 最小接口与装配

- `WorkItemAdapter`：按外部引用导入工单，并暴露固定仓库用于 API 权限检查。
- `ScmAdapter`：计算发布身份、发布、读取当前 delivery 和历史。
- `MockWorkItemAdapter` / `MockScmAdapter` 继续实现上述接口，不改变 SQLite Mock 表和离线演示语义。
- `GitLabWorkItemAdapter` 读取 `/api/v4/projects/:id/issues/:iid`，把标题、描述、URL、labels 和 `priority::*` 快照映射为 `ChangeRequest`。幂等键固定为 `gitlab:<project>:issue:<iid>`。
- `GitLabScmAdapter` 使用 Git 推送配置仓库中的 Worker 分支，再调用 GitLab v4 Merge Request 与 commit status API。
- `ChangePlatform` 默认仍装配 Mock；只有显式 `PAICHANGE_SCM=gitlab` 才装配 GitLab。离线 demo 即使进程环境中存在 GitLab 配置也强制使用 Mock。

浏览器在 capability 为 `GITLAB` 时只接受 Issue IID。repository、base ref、project 与 Token 均来自服务端，不能由请求覆盖。`POST /v1/changes` 的 GitLab 导入请求为：

```json
{"workItem":"42"}
```

## 3. 发布顺序与不变量

`DefaultChangeWorkflow` 仍是发布资格唯一所有者。调用 SCM 前继续重读并验证：

- 当前 task version；
- 锁定 Spec 的 `specId / revision / specDigest`；
- `result.json` 的 `runId / specDigest / status / Verdict / Evidence`；
- 当前本地分支 `headSha`；
- Human judgment revision（存在时重新归约）；
- Spec Approval、风险路由和需要时的 Delivery Approval。

GitLab Adapter 不推导 success。它只接收 Workflow 已决定的 `success / failure / pending`，依次执行：

1. 确认任务仓库和 base ref 与服务端 GitLab 配置一致；
2. 将精确的 `refs/heads/<taskBranch>` 推送到配置 remote，不 force；
3. 通过 GitLab Branch API 回读远端 head，必须等于已验证 `headSha`；
4. 以精确 `source_branch + target_branch` 查询全部状态的 MR；没有才创建，已有 closed/merged MR 时停止而不创建第二个；
5. MR 必须是 opened 且 `sha` 等于当前 run head；
6. 以固定名称 `PaiChange` 和包含完整 publication key 的 description 对账 commit status，没有才发布；
7. 再次回读远端 branch head；仍一致才把发布记录保存到 `gitlab_publications`；
8. Workflow 保存 `pr.check_published` 后，success 才能推进 `COMPLETED`。

publication key 绑定 `changeId + specDigest + headSha + runId + judgmentRevision + approvalId`。它不绑定 task version，因为 Check 成功后事件事务会正常推进 version；这允许“远端已成功、本地事件事务失败”在同一业务身份上恢复。

## 4. 结果不明与幂等恢复

- 重复工单导入：稳定幂等键返回同一个 ChangeTask。
- 重复分支推送：同一 ref/head 的 Git push 可安全重试；非 fast-forward 或本地 head 变化会失败，不 force 覆盖。
- MR 创建响应丢失或 5xx：重新按精确 source/target branch 查询；查询到即复用，查询不到才保留失败等待后台重试。
- status 响应丢失或 5xx：重新读取该 commit 的 statuses，并匹配 name、ref、sha、state 和完整 publication description。
- 远端成功但 `gitlab_publications` 或 Workflow 事件保存失败：下次扫描先完成远端对账，再补本地记录/事件，不创建第二个 MR 或同身份 status。
- 401/403、429、断网或 head/MR 冲突：抛出不含 Token/远端正文的安全错误，任务保持 `PUBLISHING` 或当前未完成状态，不显示 `COMPLETED`。

GitLab HTTP client 不跟随重定向，不把 Token、响应正文或带凭据 URL 写入事件。HTTP(S) remote 不得内嵌凭据；Git push 通过子进程环境注入 Basic Authorization header，命令参数和异常不包含 Token。

## 5. 配置

正常服务启用 GitLab M4 需要以下服务端环境变量。除 Token 外，同名 JVM property 使用 `paichange.gitlab.*`；Token 只读取 `PAICHANGE_GITLAB_TOKEN`，避免出现在 Java 命令行：

| 配置 | 含义 |
|---|---|
| `PAICHANGE_SCM=gitlab` | 唯一启用开关；缺省为 `mock` |
| `PAICHANGE_GITLAB_BASE_URL` | GitLab 实例根 URL，例如 `https://gitlab.example.com` |
| `PAICHANGE_GITLAB_PROJECT_ID` | 数字 project id 或 `group/project` |
| `PAICHANGE_GITLAB_TOKEN` | 服务端 Token；不进入浏览器、SQLite 或事件 |
| `PAICHANGE_GITLAB_REPOSITORY` | 已存在的本地 Git checkout 绝对路径 |
| `PAICHANGE_GITLAB_BASE_REF` | 目标分支，缺省 `main` |
| `PAICHANGE_GITLAB_REMOTE` | 指向同一 GitLab project 的 remote，缺省 `origin` |
| `PAICHANGE_GITLAB_TIMEOUT_SECONDS` | 单次 GitLab HTTP 超时，缺省 15 秒 |

本地 checkout 必须能解析 base ref，remote 必须指向配置的同一 project。PaiChange 不在 M4 中自动 clone、自动合并或修改 GitLab 项目设置。

## 6. 本地验证

`GitLabScmEndToEndTest` 启动 `127.0.0.1` 随机端口假 GitLab，同时创建真实本地 checkout 与 bare remote。测试覆盖 Issue 导入、重复导入、真实 Git 分支 push、MR、success status、远端 head 绑定、401/429 安全错误，以及 MR/status 已落地但返回 500 和本地 COMPLETED 事件失败后的恢复。全程没有真实 Token、真实 GitLab 或模型调用。

本轮结果：Java 针对性 39 tests、Node Web 17 tests、quick 967 tests 均通过；quick 有 12 个显式评测/目标环境项按预期 skipped。针对性验证命令：

```bash
mvn test -Dtest=GitLabScmEndToEndTest,MockScmAdapterTest,ChangeApiHandlerTest,HumanEvidenceWorkflowTest,ChangePlatformEndToEndTest -DskipTests=false
node --test src/test/js/change-web-creation.test.cjs
mvn test -Pquick
```

## 7. 距离真实 GitLab 验收尚缺

**暂缓记录（2026-09-06）**：当前无法提供真实 GitLab 专用测试 project，因此本节验收不继续执行，也不视为 M4 代码缺陷或验收失败。恢复条件是用户后续单独提供专用测试 project、测试 Issue、最小权限 Token、base ref/remote 与分支保护要求，并明确授权真实写入；在此之前不得连接真实 GitLab 或尝试使用真实 Token。

在获得单独授权前不执行以下操作：

1. 准备专用 GitLab 测试 project 和一个不会触碰生产代码的 issue。
2. 准备最小权限 project access token（至少能读 issue/API、向任务分支 push、创建 MR、发布 commit status），并确认过期/轮换方式。
3. 将本地 checkout 的 remote 指向该测试 project，确认 base ref、任务分支命名规则和保护规则允许 PaiChange 工作账号执行所需动作。
4. 配置合并规则，使最新 head 缺少 `PaiChange` success 时不可合并；验证旧 head 的 success 不能放行新 head。
5. 在真实实例上各执行一次正常闭环、响应超时后重试、401/403、429/临时故障和远端 head 前进场景，并核对 MR/状态/审计日志不含 Token。

完成这些配置与少量写入验收后，才能把 M4 表述为“已通过真实 GitLab 验收”。当前准确表述是“单一 GitLab Adapter 与本地假服务闭环完成，真实实例待验收”。
