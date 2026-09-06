# M6a：共享试点前的最小执行隔离

实施日期：2026-09-06。范围仅 PaiChange Worker 的 Docker 执行平面、任务资源/网络/Secret 边界、可信 Evidence 归档和重启安全；不包含真实 SCM（M4）、生产数据库/对象存储/队列（M6b）、具体 OIDC 部署或付费模型评测。

## 1. 交付结论

M6a 代码和当前目标主机验收已落地。共享试点必须显式启用 Docker；如果 Docker Engine、digest 固定的本地镜像或项目 egress 网络不可用，平台启动/任务执行会失败，不会降级到宿主命令执行。Docker Desktop 29.7.2 与本机预置、digest 固定的 Alpine 镜像已通过双任务、代理、资源、失败清理、应用级恢复和 daemon 重启故障注入；这些结果仍不能据此宣布更广泛生产部署已经就绪。

这里的“隔离”是一个有限边界：Java 编排、LLM Client、身份/RBAC、审批、SQLite 和可信 Evidence 归档仍在宿主控制面；Agent 发起的 shell 命令和锁定 command Verifier 在任务专属 Docker 容器内执行。文件工具仍由宿主 `ToolRegistry` 执行，但只绑定该任务 worktree，并继续经过 M3、PathGuard 和大小限制。容器不是绝对安全边界。

## 2. 文件与控制面隔离

`WorkerIsolation` 定义任务级 session，`DockerWorkerIsolation` 为每个 ChangeTask 创建不同容器。容器只以读写方式挂载当前任务 worktree 到 `/workspace`；不挂载源仓库、其他 worktree、Evidence staging/archive、SQLite、Docker socket、用户主目录或控制面目录。容器根文件系统只读，仅提供大小受限、`noexec/nosuid/nodev` 的 `/tmp` 与 `/run/paichange-secrets` tmpfs。

容器使用数字非 root `uid:gid`、`--init`、`--cap-drop=ALL` 和 `no-new-privileges`，禁止 privileged 模式。默认 `65532:65532`；Linux 部署方必须为 worktree 配置与宿主文件权限兼容的非 root uid/gid，不能为了兼容改成 root。

启动前先检查 Docker Engine 和本地镜像。镜像必须使用 `name@sha256:<digest>` 或 `sha256:<digest>`；执行 `docker image inspect` 后仍以 `--pull=never` 创建，平台不会安装 Docker、build 或 pull。`docker/paichange-worker.Dockerfile` 也不安装软件，部署方须提供已审批、已扫描、包含所需 JDK/构建工具和 POSIX `sh` 的预构建基础镜像。

## 3. 资源、超时与进程树

每个容器都有 CPU、内存、PID 和任务总时限：

- `--cpus`、`--memory`、`--pids-limit` 由 `paiChange.dockerIsolation` 配置并做范围校验；
- 单次命令继续受 Verifier/工具超时约束，实际等待取命令、任务和 Secret 剩余期限的最小值；
- 命令超时、取消、Secret 失效、Worker 异常、平台关闭都会执行 `docker rm -f`，清理容器内完整进程树；
- 控制面接受结果前再次检查容器仍在运行。容器提前退出时任务失败，不采信此前自报结果；
- 整体任务超时会中断宿主编排 Future，并先移除容器。后续任何工具调用还会检查 session 健康，已关闭边界不能继续操作 worktree。

## 4. 网络默认拒绝与显式放行

没有项目 egress 配置时容器固定使用 `--network none`，宿主侧 `web_search`、`web_fetch` 和 `mcp__*` 也要通过 session 的第二道项目网络判定。模型请求留在控制面，不向容器暴露模型凭据。

显式放行只接受部署方预先建立的项目专属代理网络。网络必须：

1. 是 Docker `internal` 网络，不得使用 `bridge`、`host`、`default` 或 `none` 作为放行网络；
2. 带 `com.paicli.paichange.egress=true`、精确 projectId 和当前 egress 配置摘要三个 label；
3. 只让任务容器访问同网的出站代理，由代理连接外网并落实同一 host allowlist；
4. proxy URL 不得包含凭据或 query；allowlist 不接受通配、localhost、链路本地地址或元数据域名。

平台校验网络属性和策略摘要，并对宿主 Web/MCP 工具校验工具名及 `web_fetch` 目标 host。容器同时获得大小写 `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` 变量，以覆盖 BusyBox 等只读取小写名称的客户端。Docker `internal` 网络阻止任务容器直接获得外部默认路由，但平台无法仅凭 Docker label 证明代理配置正确；生产代理的 DNS 重绑定防护、TLS、身份与审计仍属于部署方责任。

策略摘要 label 名为 `com.paicli.paichange.egress-policy`，值是 UTF-8 文本 `proxyUrl + "\n" + 按字典序逗号连接的 allowedHosts + "\n" + 按字典序逗号连接的 allowedTools` 的小写 SHA-256。project label 名为 `com.paicli.paichange.project`。更改代理、host 或工具清单后必须重建/重标并重新验收网络，旧摘要会被平台拒绝。

## 5. Secret 最小化注入

默认平台装配 `EphemeralSecretProvider.none()`，容器得到零个 Secret。模型 API Key 留在宿主 LLM Client；M4 尚未实现，SCM 写凭据也没有进入 Worker。需要私有依赖凭据时，部署装配可注入 `EphemeralSecretProvider`：

- 每项只接受 `*_FILE` 名称和 1–64 KiB 内容；
- 内容经 `docker exec -i` stdin 写入容器 tmpfs，凭据值不进入 Docker argv、配置、Prompt、日志或 Evidence；
- 后续命令只获得文件路径环境变量；
- lease 到期立即拒绝/中止，关闭时内存副本清零并删除整个容器。

配置文件只描述资源和网络，不保存 Secret 值。Secret Provider 应从外部 Secret Manager 取得短期租约；当前仓库没有虚构具体厂商集成。

## 6. Evidence 完整性与发布门槛

Worker 生成的 `result.json` 和 `change.diff` 先写任务 staging。`TrustedEvidenceStore` 从控制面执行有界、禁止符号链接的采集：每文件最多 4 MiB、总计最多 32 MiB、最多 512 个普通文件；M6a 当前只接受 `SpecRunStore` 的扁平产物。控制面生成 manifest，对每个对象和 manifest 计算 SHA-256，并把路径、大小、哈希以 `(changeId, runId)` 不可覆盖主键写入 SQLite，归档到数据目录的 `evidence-archive` 后再删除 Worker staging。

Artifact 读取、Human Evidence 判断、Delivery Approval、Check 发布和 `COMPLETED` 前都重新核对可信记录、文件集合、大小和内容哈希。对象被修改、删除、增加、替换为符号链接，或归档/数据库记录缺失时返回冲突并拒绝 success；归档失败发生在业务 Run 完成之前，因此任务进入失败而不是显示完成。Web 显示 `evidenceIntegrity=VERIFIED` 和 manifest digest。

本地只读权限用于减少误改，不是对同宿主高权限攻击者的不可篡改保证；SQLite 与文件系统也没有跨资源原子事务。M6b 才会选择生产对象存储和数据库。本切片提供不可覆盖身份和篡改检测，不声称 WORM 或分布式 exactly-once。

## 7. 重启与失败语义

平台在恢复审批/队列前按数据目录 owner label 清理上一进程遗留容器。M3 未决工具审批仍标记 `INTERRUPTED`。Docker 隔离启用时，崩溃前为 RUNNING 的 Worker Job 一律安全中止并记录“结果未知”，不重跑整个 Agent，避免重复结果不明的外部动作；本地兼容模式保留 M1 原恢复行为。已成功归档的 Evidence 可在重启后从 SQLite 哈希记录重新验证。

镜像缺失、Docker 失联、internal egress/label 不匹配、Secret 失效、容器提前退出或遗留容器无法清理都保守失败，无宿主执行 fallback。

## 8. 配置与真实 Docker 验收

环境变量只负责显式开关和镜像：

```bash
PAICHANGE_DOCKER_ENABLED=true
PAICHANGE_DOCKER_IMAGE=registry.example/paichange-worker@sha256:<64-hex-digest>
```

其余配置放在 `~/.paicli/config.json`，示意如下；projectId 是规范化仓库位置与 baseRef 的稳定摘要，实际值应从任务 API 获取，不能猜测：

```json
{
  "paiChange": {
    "dockerIsolation": {
      "enabled": true,
      "image": "registry.example/paichange-worker@sha256:<64-hex-digest>",
      "cpus": 1.0,
      "memoryMb": 1024,
      "pidsLimit": 128,
      "taskTimeoutSeconds": 900,
      "user": "10001:10001",
      "projectEgress": {
        "project_<actual-id>": {
          "network": "paichange-egress-project",
          "proxyUrl": "http://egress-proxy:3128",
          "allowedHosts": ["repo.example"],
          "allowedTools": ["web_fetch", "mcp__deps__resolve"]
        }
      }
    }
  }
}
```

真实 Docker 测试不会 pull/build；先由部署方准备 digest 固定的本地镜像，再运行：

```bash
mvn test -Dtest=DockerWorkerIsolationIntegrationTest \
  -Dpaichange.docker.integration.image='registry.example/paichange-worker@sha256:<64-hex-digest>' \
  -DskipTests=false
```

它检查实际容器的双任务 worktree 隔离、`network=none`、只读根、CPU/内存/PID、非 root 用户、唯一 workspace mount、无 Docker socket/控制面目录；还会创建带精确 projectId/策略摘要 label 的临时 internal 网络与同镜像假代理，验证 allowlist、拒绝路径、审计和错误 label/digest；并覆盖命令/任务超时、并发取消、异常退出、Secret 到期、应用级 orphan 恢复、Evidence 跨重开复核和篡改阻断。测试只删除自己随机命名或精确 owner label 的容器/网络，不删除现有镜像或用户容器。

## 9. 威胁模型与非目标

本切片假设 Docker Engine、宿主内核、控制面进程、预置镜像和部署方代理受信。它用于限制普通不可信仓库代码的文件可见范围、资源、进程生命周期和默认网络出口，并在本地归档被改动时阻止发布。

它不能防御 Docker daemon/root 或内核攻陷、容器逃逸漏洞、恶意/被替换的基础镜像、错误配置的出站代理、宿主控制面或 SQLite 被同权限攻击者同时篡改、硬件/内核侧信道，也不提供多节点配额、生产 HA、远程分支保护、真实 SCM exactly-once 或 microVM 级隔离。风险超过该假设时应在共享试点前选用更强隔离，而不是扩大本切片的安全表述。

## 10. 测试记录

新增/扩展入口：

- `DockerWorkerIsolationTest`：容器参数、任务间 mount、镜像不拉取、默认拒网、internal 项目代理、Secret、超时/清理、异常退出和遗留恢复；
- `DockerWorkerIsolationIntegrationTest`：真实 Docker opt-in，缺少显式镜像时跳过；
- `TrustedEvidenceStoreTest`：不可覆盖采集、篡改/缺失/额外对象/符号链接、进程重启后复核；
- `DefaultChangeWorkerTest`：任务总超时、归档失败不完成；
- `ToolApprovalCoordinatorTest`：Agent/Verifier 统一容器命令、第二网络门和隔离 Worker 重启不重放；
- `ChangePlatformEndToEndTest`、`OfflineChangeDemoTest` 与 Node Web 测试：可信归档、篡改阻断、capabilities 和页面状态。

2026-09-06 初次收束与目标主机续验结果：

- 首次离线收束后，用户安装 Docker Desktop 29.7.2；拉取 `alpine:3.22` 后固定本地 RepoDigest 为 `alpine@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce`；
- 首次真实运行发现 Docker 29 拒绝 `--mount` 的裸 `rw` 字段；实现改为使用 bind mount 默认可写语义，单元测试禁止该非法参数，真实测试新增 `RW=true` 与 `/workspace` 断言；
- 本轮增加到 7 条真实 Docker 用例，实测双任务隔离、默认断网、internal 假代理 allowlist/审计、错误 label/digest、cgroup OOM/PID 拒绝、命令/任务超时、主动取消、异常退出、Secret 到期、应用级 orphan 恢复与 Evidence 重开；测试后无测试标签容器或网络遗留；
- 真实续验发现并修复三项问题：补齐小写 proxy 环境变量；将 session 关闭状态改为原子状态，允许 `abort()` 并发删除阻塞命令所在容器；为 Secret tmpfs 设置配置的非 root uid/gid，文件保持 0600；
- M6a 针对性回归 36 项、M1/M2/M3/M5 与 PaiChange 扩展回归 133 项均 0 failures/errors/skipped；后者按当前明确的 26 类命令计数，不沿用历史 224 项口径；
- Node Web 回归 16 项全部通过；带 7 条真实 Docker 用例的 `mvn test -Pquick` 为 964 项，0 failures/errors、5 skipped；
- `mvn package -DskipTests` 成功；浏览器使用全新离线目录完成创建、Spec 审批、首轮 Verifier FAIL、一次 Evidence 修复、复验 PASS、Delivery Approval、Mock success 和 COMPLETED。应用重启后回读同一任务 `change_c4e0dd5685d9`，状态仍为 COMPLETED，run `RUN-20260906-062111-808-6083ee19` 的 Evidence 仍为 `VERIFIED`，manifest SHA-256 仍为 `f9bb9a7ac93929439c998e8fc1d19bae7dc8e4df9bd099e4b34011da2f4c7b92`；
- 未运行 `agent-eval`、`change-spec-eval`，未连接真实 SCM，也未实施 M4/M6b。

2026-09-06 经单独授权完成 Docker Desktop daemon 重启故障注入。重启前两次全量枚举均确认无用户容器；故障注入时仅运行使用上述本地 digest、`--pull=never` 和精确 M6a owner label 创建的验收容器。活动 `docker exec` 在重启时以 255 退出，容器保留为 `Exited (137)`；daemon 恢复为 29.7.2 后，使用同一数据目录启动 PaiChange，真实 `recoverOrphans()` 路径只清理匹配 owner 的遗留容器。隔离 Worker 结果未知的恢复测试确认任务安全取消且不重放。既有 `change_c4e0dd5685d9` 仍为 `COMPLETED`，Artifact API 重新执行 Evidence 校验并返回 `VERIFIED`，manifest SHA-256 重启前后均为 `f9bb9a7ac93929439c998e8fc1d19bae7dc8e4df9bd099e4b34011da2f4c7b92`。随后 M6a 针对性回归按当前测试集为 39 项，0 failures/errors/skipped，其中 7 条真实 Docker 用例全部执行；最终容器和带验收 label 的网络均零遗留。该故障注入未发现新的代码缺陷。

上述结果验证了当前目标主机上的控制面归档、应用重开与 daemon 重启复核、双任务攻击面、代理 ACL/审计和单机 Docker 命令边界。它不替代生产镜像扫描、生产代理/DNS/TLS 验收、宿主内核与 daemon 加固、长期压力/容量测试、宿主整机重启和异常断电恢复，也不把容器提升为绝对安全边界。
