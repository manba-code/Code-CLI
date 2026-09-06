# PaiChange M6b：生产存储最小闭环

> 实施日期：2026-09-06  
> 范围：PostgreSQL、数据库持久化 Worker 队列、S3-compatible Evidence 对象存储、SQLite 离线迁移、本地容器化验收  
> 明确不包含：高可用、多区域、Kubernetes、性能优化、容量/SLO 承诺

## 1. 交付边界

M6b 新增三个真实 seam：

- `ChangePersistence`：ChangeTask、事件、Spec/Delivery Approval、Human Review、工具策略和工具审批。`create/update + event` 以及工具审批与审计事件保持单 PostgreSQL 事务。
- `WorkerJobScheduler`：只保存 `type + referenceId` 的技术任务。PostgreSQL adapter 使用 `FOR UPDATE SKIP LOCKED` 领取、租约、heartbeat、过期恢复和活动任务唯一索引，提供 at-least-once 技术投递；业务版本/claim 继续阻止过期结果覆盖。
- `EvidenceStore` / `ObjectStorage`：对象以 `(changeId, runId, relativePath)` 不可覆盖写入，PostgreSQL 保存对象大小、SHA-256 和 manifest 摘要。读取、人工判断、审批和发布仍先复核远端对象，再按需重建有界本地 cache。

SQLite、`DurableTaskManager` 和本地 `TrustedEvidenceStore` 继续作为离线演示 adapter。生产装配只在显式 `PAICHANGE_STORAGE=postgresql` 时启用；离线 demo 即使存在生产环境变量也固定使用 SQLite。

M6b 首次交付的 PostgreSQL schema 为 V1；M7a 已在同一 `paichange_schema_migrations` 机制上前向升级到 V2，增加成员目录与审计。每版都保存 migration checksum，程序拒绝高于自身支持版本的数据库。GitLab publication ledger 也迁入 PostgreSQL。Git/SCM、数据库和对象存储之间没有跨资源原子事务：对象上传、远端 SCM 写入和本地 ledger 都依靠不可覆盖 identity、幂等重试及远端对账收敛。

## 2. 配置

生产最小闭环当前要求 `PAICHANGE_SCM=gitlab`，并设置：

```bash
export PAICHANGE_STORAGE=postgresql
export PAICHANGE_POSTGRES_URL='jdbc:postgresql://db.example/paichange'
export PAICHANGE_POSTGRES_USER='paichange'
export PAICHANGE_POSTGRES_PASSWORD='<secret>'
export PAICHANGE_OBJECT_STORE=s3
export PAICHANGE_S3_ENDPOINT='https://s3.example.com'
export PAICHANGE_S3_BUCKET='paichange-evidence'
export PAICHANGE_S3_REGION='us-east-1'
export PAICHANGE_S3_ACCESS_KEY='<access-key>'
export PAICHANGE_S3_SECRET_KEY='<secret-key>'
export PAICHANGE_QUEUE_WORKERS=2
export PAICHANGE_QUEUE_LEASE_MS=60000
export PAICHANGE_QUEUE_POLL_MS=250
```

Bucket 必须预先存在。S3 adapter 使用 path-style request、SigV4、`If-None-Match: *` 和 `x-amz-meta-sha256`；错误只记录 HTTP status、request ID 和对象 key，不记录凭据或响应正文。数据库和对象存储健康检查由 `ChangePlatform.storageHealth()` 聚合；启动 migration 或健康检查失败会 fail closed。

## 3. SQLite 切换流程

迁移是离线操作，目标 PostgreSQL 必须为空；本切片不提供双写。

1. 停止 PaiChange 写入，记录 SQLite 文件和 `evidence-archive` 的只读备份与 SHA-256。
2. 在隔离环境恢复备份并先运行容器化试迁移。
3. 配置全部 M6b 环境变量，执行：

   ```bash
   java -cp target/paicli-1.0-SNAPSHOT.jar \
     com.paicli.change.SqliteToPostgresMigrator \
     /backup/changes.db /backup/evidence-archive /srv/paichange/evidence-cache
   ```

4. 核对工具输出的 task/event/approval/policy/Evidence 数量；抽查历史 Spec digest、审批 identity、事件顺序和每个 Evidence manifest。迁移器可在同一目标上幂等重跑，遇到相同 identity 的不同内容会中止。
5. 以 `PAICHANGE_STORAGE=postgresql` 启动应用，先调用平台存储健康检查，再开放写入。

回滚边界：切换后尚未开放写入时，可停止新版本并恢复 SQLite 只读备份。开放 PostgreSQL 写入后不能直接回切旧 SQLite，否则会丢失新写入；本切片要求停止服务、导出新增数据并人工制定反向迁移，不声称自动 RPO/RTO。旧程序看到高版本 schema 时也不得继续写。

## 4. 本地容器验收

```bash
./docker/run-paichange-m6b-tests.sh
```

脚本启动临时 PostgreSQL 17.6 与 MinIO，创建测试 bucket，运行 `M6bStorageIntegrationTest`，并在成功或失败后删除本轮容器、网络和 tmpfs 数据。验收覆盖：

- PostgreSQL migration V1→当前版本、任务/事件持久化与重开读取；
- SQLite 历史任务、Spec digest、Spec/Delivery Approval、事件和 Evidence 到 PostgreSQL/MinIO 的迁移与复核；
- S3 不可覆盖对象、manifest/对象 metadata 与内容 SHA-256、cache 丢失后的重建；
- 重复 Worker 投递收敛到同一活动 job；
- RUNNING job 的过期租约恢复、`recoveryCount` 和终态落账。

该测试只证明本机最小功能闭环，不代表生产备份恢复、容量、HA、跨区域或 SLO 验收。

2026-09-06 验证结果：`M6bStorageIntegrationTest` 在真实 PostgreSQL 17.6/MinIO 容器中 1 项通过；存储、Evidence、Worker、平台端到端和 GitLab ledger 受影响回归 36 项通过；Node Web 17 项通过；`mvn test -Pquick` 为 970 项、0 failures/errors、13 skipped；`mvn package -DskipTests` 成功。未运行付费模型评测或真实 GitLab。

## 5. 已知限制与后续运维

- 当前 `ChangePlatform` 仍用数据目录文件锁限制同一目录一个进程；PostgreSQL queue 支持多个消费者的语义不等于已交付多副本 HA。
- Spec/Draft、Git worktree 和 Evidence cache 仍需要稳定的单机/共享文件路径；对象存储只覆盖 Worker Evidence。
- 本轮未实现 Kubernetes、自动扩缩容、分区表、连接池、批量扫描优化、WORM/Object Lock、跨区域复制或生命周期策略。
- 上线前仍须在目标环境完成 PostgreSQL 备份恢复、bucket versioning/Object Lock 取舍、凭据轮换、日志/审计导出、监控告警、真实负载容量、RPO/RTO 和完整故障手册。
