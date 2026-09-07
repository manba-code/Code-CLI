# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `PAI.md` > 4. `README.md` > 5. `ROADMAP.md` > 6. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

- 项目名：`PaiCLI`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 23 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 通道文本 MVP）
- ChangeSpec V1 已完成前六条产品切片、首次 Pilot 修复和 GLM 同模型复跑。2026-08-23 的 `glm-4.6v-flashx` 完整 36 次复跑中 A/B/C 成功率为 58.33%/41.67%/41.67%，配对 Draft/digest 12/12，C 虚假完成率 0%；随后冻结三个代表任务运行 `deepseek-v4-pro-0813` 小样，A/B/C 均为 100%，出现成功率天花板且 C 没有修复机会。不能把前者概括为 ChangeSpec 无价值，也不能把后者概括为已提效。评测修复已落地：结构或语义资格失败共用 Draft Generator 的最多两次纠错链路；B/C 产品耗时包含配对 Draft；报告使用 `PASS / FAIL / NOT_EVALUABLE / NOT_MEASURED`，显示 A→B/B→C/A→C 配对作用、95% 区间、修复机会/条件成功率、客观正确候选/可信产品决策/失败实际/惩罚 TTA、分段耗时和单位成功成本；ReAct 内进一步分列 LLM 请求等待墙钟与工具批次墙钟，并行工具按整批等待时间统计；评测目录已扩展到 16 个 fixture（4 small / 5 medium / 7 high），其中 `clarified-display-name` 向 A/B/C 提供完全相同的统一澄清记录，用于测量明确需求契约；2026-08-26 新增 `budget-allocator`（最大余数分配 + long 溢出陷阱）、`sliding-window-limiter`（半开滑窗边界语义 + 双方法共享时钟单调性）、`deadline-retry-runner`（跨文件重试预算 × 截止时间交互、含首次尝试前检查）三个 high fixture，目标是在强模型上制造来自任务复杂度而非模型驱动能力缺陷的失败空间；所有 fixture 逐项声明公开证据契约，Draft 的 command、JUnit glob、最低测试数和 Criterion 引用在锁定前校验，并各自提供一个应被公开 Verifier 杀死的确定性单点突变；固定失败值只是历史惩罚，不是实际失败耗时或严格统计删失；Windows Oracle 日志容错解码；Agent 停滞检测覆盖重复两步工具周期；自动评测默认限制每个 ReAct 阶段 15 轮/250k Token，生产默认行为不变。B/C 继续共用锁定 Spec/digest，B 只关闭修复，C 最多一次 Evidence 修复；`NO_CHANGE_COMPLETION` 和隐藏 Oracle 继续负责识别公开误放行。任务目录、公开测试与资格契约均在历史 Pilot 后增强，后续真实结果必须建立新版基线，不能与旧报告直接归因为模型差异。真人总人时 9-session 可行性试跑已于 2026-08-24 使用 `deepseek / DeepSeek-V4-pro` 完成：9/9 session 为 `VALID`、Scope 9/9；客观正确率 A/B/C 为 1/3、1/3、2/3，false acceptance 为 2/3、2/3、1/3；平均真人总人时为 167843/270394/250754 ms，C 修复机会 0/3。测后协调者说明所有最终 `ACCEPT` 均未查看具体代码，故接受准确率和完整真人总人时不可评价；但契约锁定、范围治理、验证闭环与可审计性的工程价值已有正面证据。综合表述为“工程价值成立，量化收益未证明”，不能概括为已提效。2026-08-26 已完成 resume-mini-pilot 两个收束批次：批次 01（3 任务 × A/C × 1 次，单一 AI 评审探索性数据）客观 6/6、错误接受 0，唯一 REWORK 源于锁定 Spec 的 AC 文本矛盾而非代码错误，说明契约质量本身需要治理；批次 02（新增 3 个 high fixture × A/B/C × 2 = 18 次纯自动运行）A/B/C 均 100%、0 修复机会，确认算法复杂度不是强模型的失败轴，契约层开销约 2.5× 墙钟且集中在 Draft 阶段（P50 99.64s），ReAct 实现本身不变慢。收束表述：SpecAgent 不提高模型上限，它把静默虚假完成变成诚实 FAIL + 可审计证据；存在失败空间（弱模型或含糊需求）时降低错误接受，无失败空间时是明码标价的保险费。三格证据矩阵见 `docs/change-spec-resume-mini-pilot-guide.md` §11。13×3×2 新版基线和 13×3×3 正式研究均未获授权，不得自动运行；新增 fixture 后的 16×3×2 基线同样未获授权。`mvn test -Pchange-spec-eval` 现在默认产生 96 次产品运行并产生费用。详见 `docs/change-spec-human-effort-study.md` 和 `docs/change-spec-pilot-remediation-checklist.md`。
- 2026-08-23 已完成 Quick 历史失败归因与跨平台修复：`-Pquick` 为 846 tests，0 failures，0 errors，5 skipped；随后不带付费 Profile 的全量回归为 892 tests，0 failures，0 errors，11 个平台/显式评测项按预期 skipped。
- `PAI.md` 是 PaiCLI 的项目级记忆文件：启动时自动注入 system prompt，适合团队共享的长期稳定规则；个人/会变化的经验继续用 `/save` 长期记忆。
- PaiChange Phase 1–6 本地后端、最小 Web 与离线 Mock 演示已实现：`serve --http` 共用 localhost 提供 `/v1/changes` 创建/列表/详情/事件/两阶段决策，原 `/v1/threads` 保持兼容。M5 已将 Runtime 凭据映射为服务端可信 Principal，并按仓库派生 projectId 执行四角色动作 RBAC；旧 `actorId` 只校验与认证 subject 一致，不能决定操作者。默认单 API Key 固定为 `local-user`，仅是单操作者 localhost 兼容模式，不是共享身份方案；M7a 已在生产 PostgreSQL 路径接入单 issuer OIDC JWT/JWKS 和持久化成员目录，但不包含登录页、Refresh Token、SCIM、组织同步或多 IdP。M3 已将 STANDARD/RESTRICTED/LOCKED_DOWN 和项目版本规则接入 Worker，需确认调用使用持久化精确审批并复用 M5 权限。ChangeWorkflow 集中维护状态与发布资格；Worker Job 只携带 changeId，Mock PR/Check 幂等保存。M4 已提取最小 WorkItem/SCM 接口并接入单一服务端配置 GitLab：Issue IID 幂等导入、任务分支 push、按 source/target branch 创建或复用 MR、按 publication identity 对账并发布 commit status；默认和离线 demo 仍用 Mock。发布前继续核对当前锁定 Spec、Git 分支 head、控制面哈希验证后的原始 Verdict/Evidence、当前交付判断与审批；HIGH 无有效 Delivery Approval 不得 success，当前交付判断 NEEDS_HUMAN 保持 pending 且批准交付返回 422，当前交付判断失败不得 success。同源 `/changes` 提供最小 Web；`-Dpaichange.demo=true` 显式启用离线 fixture，Draft/ReAct 使用确定性替身，真实复用 worktree、SpecExecutionEngine、Verifier、一次修复和 SQLite。M6a 可显式启用有限 Docker 执行边界，当前 Docker Desktop 目标主机验收（含 daemon 重启故障注入）已完成；M6b 已实现 PostgreSQL/持久化队列/S3-compatible Evidence 最小闭环并通过本地 PostgreSQL/MinIO 容器验收。真实 GitLab 测试仓库、真实 IdP 和生产备份恢复、监控、容量仍待单独验收。当前默认演示仍只可称本地可演示 MVP，不代表生产交付或真实模型效果。
- PaiChange Phase 6 验证（2026-09-04）：联合针对性 149 tests 全通过；quick 899 tests，0 failures/errors，5 skipped。浏览器已验收补充/过期审批/注入防护/一次修复/Mock success。新增测试入口为 `ChangeArtifactReaderTest,OfflineChangeDemoTest`，无模型、无外网，HTTP 仅回环。
- PaiChange Web/Artifact 边界：`GET /v1/changes/{changeId}/artifacts` 只读当前任务关联的 Draft/锁定正文、历史 revision diff、代码 diff、Verifier/Criterion Results/Evidence；只接受 `fromRevision/toRevision`，不接受路径，校验历史 digest、产物根和符号链接，每文件限 4 MiB。页面所有不可信内容通过 textContent 渲染；Key 只在页面内存与认证 Header 使用。审批 409 必须刷新并重新勾选，不自动重放。Worker 结束、PASSED、APPROVED 与 COMPLETED 是四个独立事实；发布失败不显示完成。创建请求只等待保存任务和 Draft 调度记录；提交期须立即提示并禁用重复提交，finally 恢复控件。201 不表示 Draft 成功，页面按 DRAFTING_SPEC / SPEC_REVIEW / FAILED 展示；取消和失败重试绑定 version + generation，409 刷新不重放。前端延迟响应回归：`node --test src/test/js/change-web-creation.test.cjs`。
- PaiChange M1：Draft 调度记录与任务/事件同事务持久化，独立 DraftJobRunner 默认两个并发；初次创建和 SUPPLEMENT 均后台生成。generation 绑定输入与目标 revision，attempt/lease 检查阻止取消、过期、重试后的迟到回写；产物按 attempt 隔离。基础设施最多三次、600 秒/次，退避 1/2 秒；资格纠错独立最多两次，Draft HTTP scope 禁用隐式重试并支持 Call.cancel。启动恢复未完成 Draft，锁定 Spec 不重算。Web 支持失败重试、取消、1–10 秒可停止轮询和刷新重新连接后续看；迁移及不可取消实现占用并发槽的边界见 `docs/paichange-m1-implementation.md`。2026-09-04 验证：针对性 81 tests 全通过，quick 911 tests、0 failures/errors、5 skipped，Node 6 tests 通过；浏览器验收后台状态/失败重试/取消/刷新/重启。
- PaiChange M2：Human Evidence 按 Criterion 追加补录/更正，绑定 version + specDigest + runId + headSha + judgmentRevision，仅引用当前任务 Artifact ID。SQLite 同事务保存人工记录、判断历史、审批失效与事件；原始 Run/Verdict/Evidence 不覆盖。确定性失败、验证异常或缺证据不能由人工 PASS 升级；人工缺失/跳过仍 NEEDS_HUMAN。Delivery Approval 独立且绑定 run/判断版本，HIGH 始终要求有效审批。Mock Check 按判断和审批身份幂等发布，追加历史并拒绝旧版本回退；Web 区分原始 Verdict、当前交付判断、人工验收和审批。迁移与验收见 `docs/paichange-m2-implementation.md`。2026-09-04：平台针对性 63 tests、Node 11 tests 通过；quick 923 tests，0 failures/errors，5 skipped；浏览器完成补录/过期/独立审批/更正失效/确定性失败阻断/重启验收。
- PaiChange M5：`PrincipalAdapter` 在 HTTP 入口验证身份，`ChangeAuthorizer` 每次请求从 `ProjectMembershipProvider` 计算动作权限；VIEWER/DEVELOPER/APPROVER/PROJECT_ADMIN 覆盖创建、读取、列表、事件、Artifact、Draft、Human Evidence 和两阶段审批。SERVICE 主体被剥离所有真人/工具审批动作；MEDIUM/HIGH 禁止 requester 自批且 Spec/Delivery 必须不同 subject，管理员不豁免；恢复发布时重检。列表按 projectId 过滤，跨项目详情/事件/Artifact 返回 403。Web 不再收集 actorId，显示登录 Principal 与权限，并区分 401/403；按钮隐藏不是安全边界。M7a 已在该 seam 上补齐具体单 issuer JWT/JWKS verifier 和 PostgreSQL 成员目录。迁移和边界见 `docs/paichange-m5-implementation.md` 与 `docs/paichange-m7a-implementation.md`。
- PaiChange M5 验证（2026-09-05）：M1/M2/M5 联合针对性 98 tests、Node Web 14 tests 通过；quick 929 tests，0 failures/errors，5 skipped。浏览器完成 401、固定 Principal、本地边界、服务端 actor 审计、两阶段审批及 Mock success；未运行付费模型评测或真实 SCM。
- PaiChange M3：`ProjectToolPolicy` 将 route profile 与项目版本规则计算为 ALLOW/REQUIRE_APPROVAL/DENY，规则可限定工具、命令、MCP server/tool、cwd 与参数，匹配冲突时拒绝优先。`GovernedToolRegistry` 是 Agent 初次执行、Evidence 修复和 command Verifier 的唯一 Worker 工具入口；批准后仍执行 PathGuard/CommandGuard。`ToolApproval` 只持久化脱敏摘要与规范化参数 SHA-256，绑定 change/run/call/cwd/Spec/策略版本；M5 `APPROVE_TOOL`、HUMAN 限制、共享模式 requester 禁止自批和执行前权限/策略重检均生效。默认一个等待槽、300 秒超时；重启遗留请求标为 INTERRUPTED 并中止原 Job，不重放副作用。API/Web 将工具审批与 Spec/Delivery 分开。迁移、矩阵与边界见 `docs/paichange-m3-implementation.md`。
- PaiChange M3 验证（2026-09-05）：M3 + M1/M2/M5 联合回归 27 类 168 tests、Node Web 15 tests 通过；quick 941 tests，0 failures/errors，5 skipped。浏览器完成 401、LOCKED_DOWN、四条精确审批、脱敏、首轮失败/一次修复/复验、Delivery Approval 和 Mock success / COMPLETED；未运行付费模型评测或真实 SCM。
- PaiChange M4（2026-09-06）：`WorkItemAdapter` / `ScmAdapter` 已从 Mock 提取；仅当 `PAICHANGE_SCM=gitlab` 时启用单一 GitLab project，Web 只提交 Issue IID，repository/baseRef/Token 均来自服务端。GitLab 发布先 push 精确任务分支并回读远端 head，再按 source/target branch 对账或创建 opened MR，最后按 name/ref/sha/state/publication description 对账 commit status；MR/状态响应不明、远端成功后本地落账或完成事件失败均可重试且不创建重复 MR。closed/merged MR、远端 head 前进、401/403/429/断网保持未完成。离线 demo 无条件保留 Mock。本轮 Java 针对性 39 tests、Node Web 17 tests、quick 967 tests（12 skipped）通过；未调用真实 GitLab/Token 或付费模型。真实 GitLab 验收因当前无法提供专用测试 project 而暂缓，待后续单独提供 project/Issue/最小权限 Token 并授权真实写入后恢复；Webhook、多 SCM 和自动合并仍未实施。配置与剩余验收见 `docs/paichange-m4-implementation.md`。
- PaiChange M6a：共享试点可显式启用任务级 Docker 执行平面；每个任务只挂载自己的 worktree，使用 digest 固定且本机预置的镜像、非 root、只读根、cap-drop/no-new-privileges、CPU/内存/PID/总时限和整容器进程树清理。默认 `network=none`；显式 egress 只接受绑定 projectId/策略摘要的 Docker internal 代理网络，宿主 Web/MCP 还有第二道工具/host gate。模型凭据留在控制面，容器默认零 Secret；部署方只可通过 `EphemeralSecretProvider` 注入短期 `*_FILE` tmpfs 文件。Docker 不可用、镜像缺失或网络不匹配均失败，不回退宿主命令。Docker 模式重启不重放结果不明的 RUNNING Worker。
- PaiChange M6a Evidence：Worker staging 由 `TrustedEvidenceStore` 在控制面有界采集到 `evidence-archive`，SQLite 按 change/run 不可覆盖地保存对象大小/SHA-256 和 manifest 摘要；Artifact、Human Evidence、Delivery Approval、发布与 COMPLETED 前都复核。篡改、缺失、额外对象、符号链接或归档失败拒绝 success。它是本地篡改检测，不是 WORM/对象存储或跨资源原子事务。实施、配置和有限威胁模型见 `docs/paichange-m6a-implementation.md`。
- PaiChange M6a 目标主机验收（2026-09-06）：Docker Desktop 29.7.2 上以本机预置的 `alpine@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce` 运行 7 条真实容器用例，不 pull/build。实测覆盖双任务 worktree 隔离、宿主/metadata/公网默认拒绝、带 projectId/策略摘要的 internal 假代理 allowlist 与审计、错误 label/digest fail closed、cgroup 内存 OOM/PID 拒绝、命令/任务超时、主动取消、异常退出、Secret 到期、进程树清理、应用级 orphan 恢复及 Evidence 跨重启复核/篡改阻断。发现并修复三项真实缺陷：补齐小写代理环境变量、解除阻塞 `docker exec` 对并发取消的 session 锁、将 Secret tmpfs 设为配置的非 root uid/gid。初次 M6a 针对性 36 tests、M1/M2/M3/M5 扩展回归 133 tests、Node Web 16 tests、quick 964 tests（5 skipped）均 0 failures/errors，打包成功；浏览器在应用重启后复核同一 COMPLETED 任务及 `VERIFIED` Evidence manifest。随后已获授权执行 Docker Desktop daemon 重启：重启前全量清单仅有本轮 M6a 验收容器，活动 `docker exec` 随重启以 255 退出，容器成为 `Exited (137)`；daemon 恢复后平台启动清理精确 owner label 遗留，隔离 Worker 的未知结果恢复路径保持安全取消且不重放，既有 COMPLETED 任务的 Evidence 仍为 `VERIFIED`，manifest SHA-256 保持 `f9bb9a7ac93929439c998e8fc1d19bae7dc8e4df9bd099e4b34011da2f4c7b92`。重启后 M6a 针对性 39 tests 全通过（含 7 条真实 Docker 测试），最终无容器或验收网络遗留。未运行付费模型评测或真实 SCM，也不能把 Docker 称为绝对安全边界。
- PaiChange M6b（2026-09-06）：新增 `ChangePersistence`、`WorkerJobScheduler`、`EvidenceStore` / `ObjectStorage` 深接口及 SQLite/文件系统兼容 adapter；生产模式显式装配 PostgreSQL V1 migration、`FOR UPDATE SKIP LOCKED` 租约队列、S3-compatible 不可覆盖 Evidence 和 PostgreSQL SCM publication ledger。离线迁移器在停写窗口验证旧 M6a archive，迁移任务、事件、策略/工具审批及 Evidence，并拒绝同 identity 异内容；本地 PostgreSQL 17 + MinIO 容器测试验证历史 Spec digest、两阶段审批、事件/Evidence 引用、重开、cache 重建、重复投递和过期 Worker lease 恢复。它不包含高可用、多区域、Kubernetes、性能优化，也不替代目标环境备份恢复、RPO/RTO、监控或容量验收。详见 `docs/paichange-m6b-implementation.md`。
- PaiChange M7a（2026-09-06）：生产 PostgreSQL 路径强制使用单 issuer OIDC + 持久化成员目录；JWT 校 issuer/audience、RS256/384/512 allowlist、JWKS、exp、nbf，未知 key/签名失败刷新一次后 fail closed。PostgreSQL schema 当前为 V2，成员保存 project/subject/principal type/roles/version/时间/审计 actor，变更与追加审计同事务。成员 GET/PUT/DELETE 和审计只允许 HUMAN PROJECT_ADMIN；SERVICE 禁止管理成员，目录 principal type 与 Token 不一致不授权，并发旧版本 409，不能删除最后一个 HUMAN 管理员。空目录只允许配置的 bootstrap subject 原子初始化自己；完成后配置不再绕过数据库。默认 localhost 与离线 demo 继续固定 API Key。已通过本地假 OIDC/JWKS + PostgreSQL 容器闭环；真实 IdP、登录页、Refresh Token、SCIM、组织同步、多 IdP、HA/Kubernetes 未做。详见 `docs/paichange-m7a-implementation.md`。
- PaiChange M7a 验证（2026-09-06）：假 OIDC/JWKS + PostgreSQL 17 容器 1 项通过，覆盖 V1→V2、合法/伪造/过期/nbf/issuer/audience、rotation、跨项目、SERVICE、即时改权撤权、并发版本、最后管理员、重启和审计；M6b PostgreSQL/MinIO 容器复跑 1 项通过；Node Web 17 项通过；quick 975 tests，0 failures/errors，14 skipped；打包成功。容器与网络已清理，未连接真实 IdP/SCM 或付费模型。
- PaiChange M7b（2026-09-06）：复用 M6b/M7a seam 增加 liveness/readiness、Prometheus 聚合指标、生产 TLS/RPO/RTO/queue/repository 启动校验、带 SHA-256 与条数的成员审计 JSONL 导出、日志最后防线脱敏和 `ProductionRecoveryVerifier`。本地容器脚本使用 PostgreSQL 17.6、主/备独立 MinIO、假 OIDC/JWKS 与假 GitLab，实际 `pg_dump/pg_restore`、S3→S3 mirror 后删除原数据库/bucket 再恢复；全量复核 Evidence 内容/metadata/manifest、COMPLETED publication 和发布身份。本机小 fixture 从停写恢复点计算的实测 RPO 2 秒、RTO 2 秒、备份耗时 1 秒，满足本地声明 300/600 秒但不代表生产 SLO。故障注入覆盖 JWKS、数据库、对象存储、SCM、服务重启、Worker 过期租约、重复投递、成员权限和 GitLab 响应不明对账，失败均保持不误标 COMPLETED、不重复发布、不放宽权限。M7b 容器 profile 5 tests、独立恢复 1 test、M6b 兼容容器 1 test、quick 983 tests（16 skipped）均通过。真实 IdP/GitLab、目标环境备份产品/跨故障域/容量/监控阈值/生产镜像与代理仍待验收；没有 HA/Kubernetes、登录页、Refresh Token、SCIM、多 IdP、组织同步或付费模型评测。详见 `docs/paichange-m7b-implementation.md`。
- PaiChange M8（2026-09-07）：GitHub-first 发布收口已完成。新增 GitHub Settings/Client/WorkItem/SCM Adapter，复用既有 Workflow、publication ledger、RBAC、Evidence 与两阶段审批；`ConfiguredScm` 集中支持 `PAICHANGE_SCM=mock|gitlab|github` 严格单选，默认及离线 demo 保持 Mock，既有 GitLab Adapter 与回归测试作为兼容能力保留，但真实 GitLab 不再属于 M8 退出条件。SCM/装配/启动/健康针对性 18 tests、Node Web 18 tests、M7b 容器 5+1 tests、quick 990 tests（16 skipped）和 package 均通过。普通 CI、手动容器矩阵与 `v16.1.1` Tag Release 均已在 GitHub Actions 实跑通过；托管容器报告为 M6b 1、M7a 1、M7b 6 tests，全部 0 failure/error/skip；下载 JAR 的 SHA-256 与无凭据 `--help` smoke 已独立核验。真实 GitHub Issue #1 → 精确任务分支 → PR #3 → head-bound Status 已验收：Issue 导入/PR/Status/publication ledger 均为 1，完成事件响应不明后保持 `PUBLISHING`，清除本地 publication 回执并重启仍只对账既有远端对象后进入 `COMPLETED`；无效 Token、跨项目主体和 SERVICE 审批均 fail closed。诊断尝试 PR #2 是不同 ChangeTask/publication，保留 open 且未合并。详见 `docs/paichange-m8-implementation.md`。
- 离线演示只由 JVM 属性 `paichange.demo=true` 启用，不新增 `/demo` 命令、不读取个人 provider 配置或启动 MCP；默认使用新的系统临时目录，可用 `paichange.demo.dir` 复用目录。仅接受固定退款 fixture，补充文本保留为记录，固定验收条件不随自然语言改变。重新演示用新目录，不删除已有任务。演示完整步骤与 Phase 6 验收见 README 和实施计划 §27。
- PaiChange 隔离边界：M3 的项目级策略与持久化 Worker HITL 是宿主控制面；M6a 只把不可信 shell/command Verifier 放进任务容器，Java 编排、LLM、文件工具、审批、SQLite 与 Evidence 归档留在宿主。文件工具仍受任务 worktree + PathGuard 约束。Docker/宿主内核/控制面/预置镜像/代理均在信任基内，不能把容器称为绝对安全边界；当前主机含 daemon 重启的 Docker/代理验收不能替代部署环境的生产镜像、代理/DNS/TLS、daemon 加固、容量与更广泛恢复验收。数据目录通过 `PAICHANGE_DATA_DIR` / `paichange.data.dir` 配置，同目录仅一个服务进程。
- 下一步：围绕 GitHub 使用体验、公开 CI 与 Release 证据继续迭代；真实 GitLab 如未来恢复，必须作为独立验收另行提供资源和授权，不阻塞 M8。OAuth / sampling / recovery 保留为后续 MCP 增强
- Banner 版本：`v16.1.1`，Maven 产物：`paicli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）
- 新安装或没有 `~/.paicli/config.json` 时默认 provider/model 为 `deepseek / DeepSeek-V4-pro`；已有持久配置继续优先，不自动覆盖用户选择。

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/paicli-1.0-SNAPSHOT.jar
java -jar target/paicli-1.0-SNAPSHOT.jar wechat setup   # 主动绑定微信 iLink 通道，默认不开启
java -jar target/paicli-1.0-SNAPSHOT.jar wechat start   # 前台启动微信通道
/wechat                   # 交互式 CLI 内扫码绑定并后台启动微信通道
java -Dpaichange.demo=true -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8086 # 离线 Web；仍需 PAICLI_RUNTIME_API_KEY
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Pagent-eval     # 真实 LLM 三组 A/B 质量评测；显式运行才产生 Token 费用
mvn test -Pchange-spec-eval # ChangeSpec A/B/C：默认 16×3×2=96；显式运行才产生 Token 费用
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
/init                    # 生成精简项目级记忆 PAI.md；已有文件不覆盖，/init --force 可重写
/export                  # 导出当前 ReAct 会话为 Markdown，包含完整 system prompt
/spec <需求>             # 生成、确认并锁定 ChangeSpec，执行 ReAct、确定性 Verifier、最多一次修复与最终 Verdict
```

## 架构概览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

ChangeSpec 是现有执行路径之上的可选契约层。当前 `/spec <需求>` 使用无工具的 `SpecDraftGenerator` 读取用户需求、Project Context 和显式本地引用，结构校验失败最多重生成一次；command Verifier 的 `expect` 必须是嵌套 YAML 对象，Prompt 和纠错提示都明确 `expect.exit_code` 只是字段路径而不是带点号的键。每条非 scope deterministic Criterion 必须至少引用一个 command Verifier，`path_scope` 只能证明修改范围。模型在完整文档前附带说明或代码围栏时只提取其中以 `---` 开始的完整 front matter 文档，Codec 仍保持严格校验。Enter 确认、I 补充、ESC 取消。确认后 `SpecRunCoordinator` 会稳定编码并不可覆盖地锁定完整文档，回读核对 specId/revision/digest，在 ReAct 前记录独立 workspace baseline，把包含补充要求的最终确认需求、锁定 YAML 与 digest 注入现有 ReAct。ReAct 正常结束后，command Verifier 按声明顺序串行运行，JUnit 只采信本次命令新建或更新的 XML，最后基于最终 workspace 执行 `path_scope`；命令继续经过 HITL/CommandGuard。首轮至少一个 deterministic Criterion 为 `FAIL` 且没有 Verifier `ERROR` 时，Coordinator 向同一个 ReAct 会话追加脱敏、截断后的失败 Evidence 和首次 changed-files 数量，最多修复一次；若首次零改动，修复提示明确要求实际调用工具修改而不是只描述计划。修复后基于原 baseline 重跑全部 Verifier。每条最终 deterministic Criterion 按 `FAIL > INCONCLUSIVE > PASS` 聚合，全部确定性条件通过后才进入 `P / F / S` Human 判断；`result.json` 保存 `repairCount`、一至两轮 VerificationAttempt 和最终 Verdict，Evidence ID 带 attempt，`change.diff` 只保存 final diff。ReAct/修复取消、失败或准备/验证异常也会保留锁定文件、changed-files/diff，并以 `INCOMPLETE` 持久化；Agent 自述不能生成 PASS。评测器在 B/C 锁定前校验 command 是否精确命中任务允许列表，以及每条非 scope deterministic Criterion 是否引用允许的 command；首次不满足时会把资格错误反馈给 Draft Generator 的第二次生成，最终仍不满足才按 `DRAFT_INVALID` 保存诊断且不产生配对 digest。评测器还把完整结束、隐藏任务失败且 changed-files 为空的 Spec Run 标为 `NO_CHANGE_COMPLETION`，不改变生产 Verdict；付费评测为每个 ReAct 阶段注入独立 15 轮/250k Token 预算，生产 CLI 默认不变。

核心内置工具 11 个：`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn`

`execute_command` 按宿主平台使用原生命令壳：Windows 为 `cmd.exe`，Linux/macOS 为 POSIX `sh`，不把 Bash/WSL 作为额外运行前提；命令超时或取消时必须清理子进程树。`glob_files` / `grep_code` 面向模型返回的项目相对路径统一使用 `/`，不能泄漏 Windows `\` 分隔符。

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。`search_code` 是 RAG 语义辅助，适合模糊自然语言、关键词不明确、常规搜索无果、巨型/跨知识检索场景，不作为精确代码定位的首选。`VectorStore` 会把 project path 规范化为宿主绝对路径；RAG 单元测试必须注入本地 stub Embedding，不能依赖 Ollama 或远程 API 是否在线。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

MCP 配置会合并用户级 `~/.paicli/mcp.json` 与项目级 `.paicli/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。
DeepSeek 是新安装的默认 provider，代码默认模型为 `DeepSeek-V4-pro`；`DEEPSEEK_MODEL` 和持久配置仍可覆盖该模型 ID。
DeepSeek SSE 调用默认强制 HTTP/1.1，避免部分网络/网关下 HTTP/2 长流被远端重置成 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前按文本 provider 处理：`supportsImageInput()` 返回 false，历史或工具回灌里的图片 `ContentPart` 会在请求序列化时替换为文本提示，不能把 `image_url` block 发给 DeepSeek API。

讯飞星辰 MaaS provider 名为 `xfyun`，默认 Base URL 为 `https://maas-api.cn-huabei-1.xf-yun.com/v2`。`model` 必须使用服务管控页展示的 `modelId`；公开模型名 / Hugging Face 仓库名不一定可直接调用。微调模型用 `/config provider xfyun --lora-id <resourceId>` 配置服务卡片上的 resourceId，PaiCLI 会作为 HTTP header `lora_id` 发出。`xfyun` 当前按 MaaS 文档走纯对话请求，不向上游发送 PaiCLI 内置工具列表。
Agnes provider 名为 `agnes`，默认 Base URL 为 `https://apihub.agnes-ai.com/v1`，默认模型 `agnes-2.0-flash`，走 OpenAI-compatible Chat Completions，默认 1M context window，支持流式输出和 tools。

## 仓库结构

```
src/main/java/com/paicli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         GLMClient, DeepSeekClient, StepClient, KimiClient, FreeLlmApiClient, AgnesClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── wechat/      iLink client, account store, message loop, non-interactive policy
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillContextBuffer, SkillIndexFormatter
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 π 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。关键字段可用克制的 JLine `AttributedString` 彩色样式突出，但纯文本格式和宽度裁剪逻辑要保持稳定。`ctx` 表示当前仍会带入下一轮请求的上下文估算；`in/out/cache` 表示最近任务的 LLM 调用统计，二者不要混用。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 conversationHistory、shortTermMemory、待注入 Skill buffer，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。`/compact` 会手动压缩当前 ReAct conversationHistory，不等待上下文阈值触发，保留最近 1 个 user 轮次和 tool_call/tool_result 边界。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`PAICLI_MCP_STARTUP_WAIT_SECONDS` / `-Dpaicli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `PaiCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `PaiCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `@image:file://...` 在 Windows 同时接受标准 `file:///C:/...` 和常见的 `file://C:\...`，并宽容处理未编码空格、非 ASCII 和合法 `%XX`；驱动器路径不能被误转成当前盘根下的 `/C:` 路径。
- `LineReader` 使用 `PaiCliHistory` 持久化输入历史到 `~/.paicli/history/input.history`；如果 `paicli.history.file` / `PAICLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- 启动期会加载 `~/.paicli/PAI.md`、项目根 `PAI.md`、项目根 `.paicli/PAI.md`、`PAI.local.md`、`.paicli/PAI.local.md`，按此顺序注入 Project Context；`@relative/path.md` 可导入项目根内文件，总注入内容有字符预算，避免项目记忆变成 token 噪音。
- `/init` 会根据当前项目生成短 `PAI.md`，只放 commands / project positioning / architecture / pitfalls / don'ts；默认不覆盖已有文件。
- `/export` 导出当前 ReAct `conversationHistory` 为 Markdown 到 `~/.paicli/exports/session-*.md`；只支持无参数命令，包含完整 system prompt，便于检查 LLM 实际接收前的指令。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实
- `PAI.md` 管团队共享的项目规则，长期记忆管个人或项目作用域的稳定事实；不要把一次性协作经验写进 `PAI.md`
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆必须可审计和可删除：`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear`
- 两道压缩不要混淆：shortTermMemory 压缩 vs conversationHistory 压缩（后者是防 window 超限的关键）
- 自动压缩阈值按 Claude Code 风格预留摘要输出和安全缓冲：大窗口使用 `window - 20k - 13k`，例如 200k 窗口约 167k 触发、1M 窗口约 967k 触发；小窗口按比例缩小预留。

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线；除 Unix 高危模式外，也快速拒绝 Windows 盘符绝对路径上的 `del` / `rmdir`、`format` 和 `diskpart`
- 微信 iLink 通道没有人工审批面板，必须走非交互式默认拒绝策略：只读工具默认允许，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝，文件写入仍由 PathGuard 限定在绑定 workspace 内。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序

### Multi-Agent 生命周期

- `/team` 每次创建任务级 `AgentOrchestrator`，CLI/TUI 使用 `try-with-resources` 确定性关闭
- WorkerPool 默认预热 2 个 Worker，按同一 DAG 批次压力动态扩容至 4 个，批次结束缩容回 2 个
- Worker 必须通过 Lease 独占租借；归还前清理任务历史，缩容或 Team 结束时执行 `close()` 并移除池引用
- 并行步骤使用独立 Reviewer；SubAgent 关闭只释放自身 conversationHistory，不关闭共享 LlmClient / ToolRegistry / MemoryManager

### Web + Browser

- 每轮 system prompt 会注入当前日期/时区，用于相对日期理解；联网搜索不再由 prompt 的 Freshness Policy 强制，是否调用 `web_search` 交给模型基于工具 schema 和用户目标自主决定。
- “当前项目/当前 README/当前文件/当前代码”等表达属于本地上下文任务，通常应由模型选择 `glob_files` / `grep_code` / `read_file`，而不是联网工具。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=WorkerPoolTest,SubAgentTest,AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| Agent A/B 质量 | `mvn test -Pagent-eval`（真实 API、非确定性、有费用） |
| ChangeSpec A/B/C | `mvn test -Pchange-spec-eval`（真实 API、默认 96 次产品运行、有费用；需单独批准） |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 工具调用 | ToolRegistry.java + Agent.java |
| 代码搜索 | ToolRegistry.java (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG 语义辅助 | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| Multi-Agent | AgentOrchestrator.java + SubAgent.java |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
