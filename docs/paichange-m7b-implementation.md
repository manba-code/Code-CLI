# PaiChange M7b：生产验收与运行保障最小切片

> 实施日期：2026-09-06  
> 范围：健康检查、Prometheus 指标、生产启动校验、成员审计导出、PostgreSQL + S3 Evidence 备份恢复验证和故障注入  
> 明确不包含：真实 IdP/GitLab、登录页、Refresh Token、SCIM、多 IdP、组织同步、HA、Kubernetes、容量承诺或付费模型评测

## 1. 交付边界

M7b 复用 M6b/M7a 的 `ChangePersistence`、`WorkerJobScheduler`、`EvidenceStore` / `ObjectStorage`、`ScmAdapter` 和 `PrincipalAdapter` seam。新增的 `ChangeOperations` 是聚合健康与指标的深 module；它不决定 Change 状态、不发布 SCM、不修改成员，也不缓存授权结果。`ProductionRecoveryVerifier` 只读恢复后的 PostgreSQL 和 S3 对象，完整性失败或 RPO/RTO 超标时拒绝验收。

业务工作流、RBAC、OIDC claims、成员目录和 GitLab 幂等发布逻辑没有重写。PostgreSQL schema 仍为 V2；本切片没有为了运维接口引入业务表或第二套状态源。

默认 localhost 和 `-Dpaichange.demo=true` 仍使用 SQLite、本地 Evidence、Mock SCM 和固定 API Key。离线 demo 也可查询健康与指标，但不会读取生产环境变量或联网。

## 2. 健康检查和指标

Runtime API 仍只监听 `127.0.0.1`。传入 PaiChange 运维 module 时新增：

| 方法 | 路径 | 语义 |
|---|---|---|
| `GET` | `/health/live` | 只判断进程 module 未关闭；不探测远端依赖 |
| `GET` | `/health/ready` | 实时探测 PostgreSQL、Worker queue、Evidence/S3、SCM 和 JWKS；任一失败返回 503 |
| `GET` | `/metrics` | Prometheus text exposition；为保证监控可抓取，即使 readiness 为 0 仍返回 200 |

这三个入口不走 OIDC，且只应通过 loopback/受控 sidecar 抓取；反向代理不得把它们直接暴露到公网。readiness 响应只给 `UP/DOWN` 和 `dependency_unavailable` / `integrity_check_failed`，不会返回 JDBC URL、Token、对象正文或远端错误正文。

当前指标：

- `paichange_liveness`、`paichange_readiness`、`paichange_uptime_seconds`；
- `paichange_dependency_up{name=...}`、readiness 检查/失败累计数；
- Worker `enqueued/running/completed/failed/canceled/recoveries` 和最老排队秒数；
- Change 总数、活动数、FAILED、COMPLETED、DELIVERY_REVIEW 和 `dispatch.failed` 累计数；
- 部署声明的 `paichange_declared_rpo_seconds` / `paichange_declared_rto_seconds`。

建议最小告警：

1. `paichange_liveness == 0` 立即重启并 page；`paichange_readiness == 0` 持续 2 分钟 page，按 dependency label 定位。
2. `paichange_worker_oldest_enqueued_age_seconds > 120` 持续 5 分钟告警；阈值须按真实任务时长和并发重新标定。
3. `increase(paichange_worker_jobs_failed_total[15m]) > 0` 或 `increase(paichange_dispatch_failures_total[15m]) > 0` 告警并检查任务事件，不能通过手工改状态消警。
4. `increase(paichange_worker_job_recoveries_total[15m]) > 0` 触发 Worker/数据库连接调查；恢复是安全机制，不是正常吞吐路径。
5. 外部备份作业必须另报最后成功时间；超过声明 RPO 即告警。当前应用指标只展示声明目标，不把“配置了目标”误报成“已备份”。

## 3. 生产启动校验

生产 PostgreSQL 路径在创建业务 adapter 前解析全部 M6b/M7a/M7b 配置，并要求：

- `PAICHANGE_STORAGE=postgresql`、`PAICHANGE_OBJECT_STORE=s3`、`PAICHANGE_SCM=gitlab`、`PAICHANGE_AUTH=oidc`；
- 远程 PostgreSQL 使用 `jdbc:postgresql://...?...sslmode=verify-full`，用户名/密码不得放在 JDBC URL；
- 远程 S3、GitLab、issuer/JWKS 使用 HTTPS；只有 loopback 测试允许 HTTP；
- GitLab checkout 目录已存在；queue lease 至少是 poll interval 的三倍；
- 显式声明 `PAICHANGE_BACKUP_RPO_SECONDS`（60–604800）和 `PAICHANGE_RECOVERY_RTO_SECONDS`（60–86400）。

配置样例见 [`paichange-m7b-production.env.example`](paichange-m7b-production.env.example)。真实 Secret 必须来自进程 Secret 管理，不写入样例、Git、命令行或日志。

## 4. 成员审计导出

原成员审计查询继续保留。新增：

```text
GET /v1/changes/projects/{projectId}/members/audit/export
```

它仍先执行 M7a 的 OIDC 验证与 HUMAN `PROJECT_ADMIN` 授权；SERVICE、跨项目主体和已撤权主体均返回 403。成功返回 `application/x-ndjson`，每行一个完整 `ProjectMemberAudit`，并带 `X-Content-SHA256`、`X-Record-Count`、`Cache-Control: no-store`。单次上限 100000 条和 32 MiB；超限必须分期扩展持久化 seam，不能静默截断。subject/actor 是授权审计数据，导出文件应按敏感内部数据保护。

## 5. 日志脱敏规则

代码继续遵循“源头不记录 Secret”，`SensitiveValueRedactor` 仅作最后防线：

- Bearer/JWT、Authorization、Cookie、PRIVATE-TOKEN；
- password/passwd/secret/token/api-key/access-key 的 `key=value` 或 `key:value`；
- HTTP(S) URL user-info；
- 运维错误最多保留 1024 字符。

健康响应不含异常文本。S3 只允许记录 operation、HTTP status、request ID 和对象 key；GitLab 不记录 Token/响应正文；OIDC 不记录原 Token/JWKS 正文。任务 requirement、Evidence 内容、成员审计正文和工具参数不得进入指标 label。集中日志平台仍须配置二次 DLP，并对历史日志做抽样扫描。

## 6. 备份、恢复与可验证 RPO/RTO

PostgreSQL 与 S3 不存在跨资源原子快照。生产演练应先停写/摘除 readiness，再记录恢复点：

1. 停写后、开始 `pg_dump --format=custom` 前记录 `recoveryPointAt`；保存 dump SHA-256、`backupCompletedAt` 和数据库 schema version。RPO 从恢复点计算，不能用较晚的备份完成时间代替。
2. 将 Evidence bucket mirror 到独立 S3 账号/bucket；必须保留 `x-amz-meta-sha256`。普通文件复制会丢 metadata，恢复校验将 fail closed。
3. 在隔离环境创建空 PostgreSQL 和空恢复 bucket；执行 `pg_restore --no-owner --no-privileges`，再从备份 bucket S3→S3 mirror。
4. 以恢复 bucket 和新的本地 cache root 运行 `ProductionRecoveryVerifier`。它检查 V2 schema、所有 Evidence/manifest 的远端 metadata 与内容 SHA-256、对象集合无缺失/额外对象、COMPLETED 必须存在 success publication、同任务版本无重复 publication，并统计任务/事件/成员审计。
5. 只有完整性通过且 `recoveryPointAt → recoveryStartedAt <= RPO`、`recoveryStartedAt → verifiedAt <= RTO` 才能恢复 readiness 和写入；报告另列 `recoveryPointAt → backupCompletedAt` 备份耗时。

恢复环境设置 PostgreSQL/S3 与 RPO/RTO 环境变量后可直接执行：

```bash
java -cp target/paicli-1.0-SNAPSHOT.jar \
  com.paicli.change.ProductionRecoveryVerifier \
  2026-09-06T00:00:00Z 2026-09-06T00:00:30Z \
  2026-09-06T00:01:00Z /srv/paichange/evidence-cache
```

命令只向 stdout 输出无 Secret 的 JSON 报告；任一完整性或目标检查失败时以非零状态退出。

本地可重复演练：

```bash
./docker/run-paichange-m7b-tests.sh
```

脚本用真实 PostgreSQL 17.6、主 MinIO 和独立备份 MinIO；先写入数据，再 `pg_dump`、S3→S3 mirror，随后删除原数据库与原 bucket，只从备份恢复。2026-09-06 本机小 fixture 从停写恢复点计算的实测 RPO 2 秒、RTO 2 秒，备份耗时 1 秒，满足本地声明的 RPO 300 秒/RTO 600 秒；恢复报告 integrity digest 为 `24c9bb2ab28699594b676950120bb659d8415d0f6e1e556cfadf65dae7cd5b39`。这些数值只证明本轮本机演练，不是生产 SLO；目标环境必须用真实数据量、网络和备份系统重新测量并保存报告。

## 7. 故障与恢复手册

- **JWKS 不可用**：readiness 503、未缓存/未知 key 的认证返回 401；不得切回固定 API Key。恢复后先验证 rotation 和撤权，再开放流量。
- **PostgreSQL 不可用**：readiness 503；调度扫描停止，不用内存猜测状态。恢复后检查 schema checksum、queue lease 和任务事件，再开放流量。
- **S3 不可用或校验失败**：readiness 503；Evidence 读取、审批和发布保持失败，不能把 cache 当可信来源。恢复后运行全量 `verifyAll()`。
- **GitLab 不可用/响应未知**：readiness 503；任务不得标 COMPLETED。恢复后使用 publication identity 和远端 branch/MR/status 对账，不能盲目重发。
- **Worker 丢失**：等待 lease 过期；新进程将 job 重新入队并增加 recovery count。业务 expected version/claim 阻止迟到结果覆盖。
- **重复投递**：相同 `job_type + reference_id` 的活动 job 收敛为同一记录；GitLab adapter 按 branch、MR、status 和 publication ledger 对账。
- **服务重启**：先保持流量关闭，检查 `/health/ready`、全量 Evidence 完整性、成员即时权限和 queue backlog；确认无异常后开放。

所有故障都以“不继续发布/不标 COMPLETED/不放宽权限”为恢复原则。严禁直接改 `change_tasks.state`、删 migration row、跳过 Evidence 校验或临时给 SERVICE/HUMAN 提权。

## 8. 升级与回滚

升级前完成数据库和 bucket 备份、在隔离恢复环境运行 verifier，并保存审计导出。部署新二进制后先检查 startup validation、liveness/readiness、metrics 和一个无副作用读取，再开放流量。

本切片没有 schema V3；可在停写后回滚到支持 PostgreSQL V2 的上一二进制。若升级期间产生新任务、审计或发布记录，先备份当前数据，不能恢复旧快照覆盖新写入。若后续版本引入更高 schema，旧程序必须继续按现有机制拒绝启动，优先修复前滚。

## 9. 本地故障注入证据与剩余验收

M7b profile 同时运行 M7b 运维闭环、M7a 身份/成员闭环和 M4 假 GitLab 幂等测试。覆盖 JWKS、数据库、S3 和 SCM 暂不可用、服务重开、Worker 过期租约、重复投递、成员导出权限、MR/status 响应不明对账，以及恢复后全量 Evidence 完整性。故障期间任务保持原状态；没有人工注入 COMPLETED，也没有绕过成员权限。

2026-09-06 本机验收结果：M7b 运维/启动/审计针对性 7 tests、M7b 容器 profile 5 tests、独立恢复校验 1 test、M6b PostgreSQL/MinIO 兼容容器 1 test、`mvn test -Pquick` 983 tests（0 failures、0 errors、16 skipped）均通过；随后完成跳过测试的可执行 JAR 打包验证。

共享试点前仍须在真实目标环境完成：

- 真实 IdP claim/TLS/代理/JWKS rotation 与紧急撤权演练；
- 专用 GitLab project、最小 Token、branch protection、MR/status 对账与 Token 轮换；
- 真实 PostgreSQL/S3 备份产品、加密、保留、跨故障域、versioning/Object Lock 取舍；
- 以真实数据量和并发重新验证 RPO/RTO、容量、连接数、队列积压阈值和成本；
- 生产镜像、Docker daemon、DNS/TLS/egress proxy、宿主加固和日志/SIEM 接入。

因此 M7b 可称“本地生产验收与运行保障最小切片已闭环”，不能称为已完成真实生产环境验收、高可用或灾备认证。
