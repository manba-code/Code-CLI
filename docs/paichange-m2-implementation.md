# M2：Human Evidence 补录闭环

实施日期：2026-09-04。范围仅人工验收、交付判断、Mock 发布和 Web；不包含 M3 工具策略、真实 SCM、RBAC、上传/远程抓取或生产隔离。沿用 M1 单进程数据目录锁和后台调度。

## 身份与 API

`POST /v1/changes/{changeId}/human-evidence` 每次追加一条 Human Criterion 判断；再次提交同一 Criterion 是更正，旧记录保留。请求示例：

```json
{
  "expectedVersion": 8,
  "expectedSpecDigest": "从详情复制当前锁定 digest",
  "expectedRunId": "从详情复制 run.runId",
  "expectedHeadSha": "从详情复制 run.headSha",
  "expectedJudgmentRevision": 0,
  "criterionId": "AC-H1",
  "decision": "PASS",
  "reason": "已对照代码 diff 核对显示结果",
  "artifactRefs": ["code-diff"],
  "actorId": "reviewer"
}
```

- decision 为 `PASS / FAIL / SKIPPED`。理由必填，最多 16000 字符；actorId 必填，最多 200 字符。一次最多 32 个引用。
- 只接受锁定 Spec 的 Human Criterion。确定性 Criterion、未知 Criterion、未知 Artifact ID 返回 422；缺字段、错误类型、非法枚举、未知字段返回 400。所有身份版本不匹配返回 409，不自动重放。
- 引用只能选择 `GET /artifacts` 返回的 `artifactRefs`：`locked-spec`、`run-result`、`code-diff`、`evidence:<evidenceId>`。接口不接受路径、URL、附件；实际内容从当前任务关联解析，复用产物根、符号链接、digest 和每文件 4 MiB 限制。
- 可补录状态为 `DELIVERY_REVIEW / FAILED / PUBLISHING / COMPLETED`，且必须已有持久化 Run；已拒绝、取消、执行中任务不能通过补录重新开启。
- 保存返回 200，表示记录和重算已提交；发布由独立后台步骤完成。该请求不会直接发布 success。
- `delivery-decisions` 的 APPROVE/REJECT 现在必须同时提交 `expectedVersion / expectedSpecDigest / expectedRunId / expectedHeadSha / expectedJudgmentRevision`，原有 decision、actorId、reason 保留。旧 HTTP 客户端缺少 run/判断版本返回 400，须随 Web 一并升级。

详情新增 `humanReview`（全部人工记录及判断历史）、`judgmentRevision`、`deliveryVerdict`、`deliveryApprovalValid`、`deliveryHistory`。`run.verdict` 始终是原始 Run Verdict；页面分别展示它与当前交付判断。`deliveryApprovalValid` 表示持久化身份绑定匹配，实际批准和发布仍重新读取当前分支 head、锁定文件与 Run 产物，不能把查询快照视作后续发布授权。

## 判断与审批

`HumanReview.Entry` 保存 changeId、specDigest、runId、headSha、Criterion、判断、理由、Artifact ID、actorId、时间和产生的判断 revision。`HumanReview.Judgment` 保存该 revision 的身份、逐项结果和有效 Verdict。

初始 revision 0 由不可变原始 Run 表示。首次补录时将原始 Verdict 及逐项基线纳入判断历史；每次补录 revision 加一，全部更正保留。纯函数 `DeliveryJudgmentReducer` 仅使用锁定 acceptance、原始最终确定性结果/最后一次 Verifier 结果及当前身份匹配的最新人工记录：

| 条件 | 当前交付判断 |
|---|---|
| 任一有效 Criterion FAIL | FAILED |
| 确定性证据缺失、ERROR、INCONCLUSIVE、NOT_RUN 或运行未正常结束 | INCOMPLETE（SPEC_INVALID 仍不通过） |
| 确定性全部通过，人工缺失或 SKIPPED | NEEDS_HUMAN |
| 全部 Criterion 通过且 Run 正常结束 | PASSED |

FAIL 优先于未定结论；未正常完成的运行始终不通过。重复 Criterion/Verifier 结果拒绝，未知 Verifier 状态按证据不完整处理。人工判断只作用于 Human Criterion，不能升级原始确定性 FAIL/ERROR/INCONCLUSIVE。

原始 `RunRef`、磁盘 `result.json`、VerificationAttempt、Evidence 和 `change.diff` 不修改。批准与发布时再次按相同输入重算并比对当前判断，发现依据或身份变化拒绝操作。

每次更正清除当前 Delivery Approval，事件记录失效的 approvalId；旧审批仍留在审批表及事件中。新的有效 PASSED 按已有路由进入交付审批，显式免批路由才可进入 PUBLISHING；HIGH 始终要求有效审批。审批额外绑定 runId 和 judgmentRevision，普通批准不能把 NEEDS_HUMAN 或失败结果变成 PASSED。已经 COMPLETED 的任务被更正后立即退出完成状态，再发布新的 pending/failure 或重新等待审批。

## SQLite 与迁移

- `change_tasks` 增量添加 nullable `human_review_json`、`delivery_binding_json`。人工记录/判断历史作为追加快照，与任务 version CAS、状态、审批失效和 `human.evidence_recorded` 事件在同一事务提交；事件同时保存本次 entry/judgment，事务失败不会留下半条记录。
- `change_approvals` 添加 `run_id`、`judgment_revision`，新审批保存完整绑定。当前有效绑定另存 task JSON；原始审批行保持追加历史。
- 新增 `mock_check_history`，保存 publication key、change/spec/run/head、判断版本、审批 ID、task version、结论、产物目录和发布时间；`mock_pr_checks` 保留为当前代码身份的投影。
- 旧 Mock Check 在首次打开时增量导入 legacy 历史；只有和当前持久化 Run 的 spec/head 对应时才附上 runId，不猜历史 head 的运行或审批身份。重复打开不重复导入。
- 旧版未绑定 run/判断版本的审批保留历史，但不能作为新的 success 发布授权。旧 COMPLETED 和历史 Check 不删除；旧 PUBLISHING 中缺少 run 绑定的审批由后台原子失效并退回 DELIVERY_REVIEW，记录 `delivery.approval_invalidated`，重新核对后可批准；不会沿用旧批准静默发布。当前没有重新验证新 head/替换 Spec/run 的 API，身份已变化时须新建任务，不能用人工补录绕过重新执行。

升级前停止服务并备份整个数据目录；迁移后禁止新旧版本同时运行。回退须停止服务并恢复升级前备份，不能让 M1 服务写入已有 M2 判断的数据库。

## Mock 发布与故障恢复

幂等 publication key 使用 `(changeId, specDigest, headSha, runId, judgmentRevision, approvalId)` 的稳定哈希。相同判断及审批身份只能有一种结论；HIGH 的“判断已通过但未审批”发布 pending，后来独立批准以新的 approvalId 发布 success。这样既保留同判断下的审批事实，也不会把 pending 覆盖历史。

每次发布核对数据库当前任务 version，并禁止较旧 task version 或被替代 publication key 回退当前 Check。当前投影和追加发布历史在同一 SCM SQLite 事务中提交；历史不能被重试改写。任务/事件和 Mock SCM 是分开的可恢复事务：

| 中断点 | 恢复行为 |
|---|---|
| 人工保存事务失败 | 记录、判断、版本、审批失效与事件全部回滚 |
| 人工保存后尚未发布 | 扫描当前 FAILED / DELIVERY_REVIEW / PUBLISHING，重新核对资格并发布 |
| Mock 事务失败 | 保留当前判断与未完成状态，后续扫描重试 |
| Check 已提交，发布事件/完成状态保存失败 | 同 key 幂等读取 Check，补齐该 key 的事件，再完成；不产生重复历史 |
| 旧 pending/success 迟到 | 版本或发布身份检查拒绝，不改变新结论 |

不会重放已经被新判断替代的过渡状态。全部判断历史始终保存，发布历史只记录实际发布过的状态。Mock 不提供真实分支保护，不执行自动合并/部署。

## Web 与验收

页面在 Human Criterion 下提供判断、必填理由、服务端 Artifact 选项与独立确认；更正不预选旧 PASS，也不保留旧确认。人工验收与 Delivery Approval 分开展示，后者只在有效 PASSED 时允许批准。所有输入、结果及事件继续通过 textContent 渲染。提交期间禁用按钮，409 刷新后必须重新勾选；产物读取失败移除操作入口。待审批/失败状态若仍等待新判断的 Check 发布，继续有界退避轮询，发布对齐后停止。刷新清除 Key，重新连接恢复 fragment 指定任务。

复现测试：

```bash
mvn test -Dtest='com.paicli.change.*Test' -DskipTests=false
node --test src/test/js/change-web-creation.test.cjs
mvn test -Pquick
```

- `HumanEvidenceWorkflowTest`：缺项/跳过、HIGH 独立审批、免批路由、确定性失败/异常/证据不足、五类身份过期、当前 head 变化、非法 Criterion/Artifact、更正及审批失效、重启、原始文件字节保持、并发 CAS、事务故障、旧库迁移、未绑定旧审批退回审批、发布后事件故障恢复和迟到发布。
- `HumanEvidenceApiTest`：真实回环认证、必填版本、400/409/422、禁止未知字段/任意引用、原始与当前判断的 HTTP 展示。
- 前端 11 项测试覆盖既有 M1 交互及人工提交、双击防护、409 清除确认、普通审批不能代替人工验收、独立 HIGH 审批、绑定字段、文本安全和发布失败不显示完成。
- 浏览器使用系统临时目录、已编译测试 fixture 和回环服务，无模型请求。已实际完成：缺项 pending → 第一项 PASS 仍 NEEDS_HUMAN → 全部 PASS 但 HIGH 待审批 → 独立审批 success → 追加 FAIL 更正清除审批并发布 failure；双页面旧提交 409 且不重放；两人工项均 PASS 时确定性 FAIL 仍 failure；含 HTML 理由原文显示、Artifact 选择、页面布局、刷新清空 Key、服务停止/重启后记录和失效状态保持。辅助服务及数据不进入产品演示模式。

2026-09-04 最终验证结果：

- 平台针对性：63 tests，0 failures/errors，0 skipped。
- Node 前端：11 tests 全部通过。
- quick：923 tests，0 failures/errors，5 个预期 skipped。
- `mvn package -DskipTests` 成功，手工验收 jar 已更新；`git diff --check` 通过。
- 浏览器验收全部完成，临时服务已关闭，测试数据保留在系统临时目录。最初沙箱内 HTTP/子进程测试因权限失败，经授权在沙箱外重跑通过；没有通过跳过用例规避失败。

## 保留的边界

actorId 仍是本地可信模式输入，不提供服务端真人身份认证；职责分离沿用现有审批策略。没有上传、URL 抓取、组织工具策略、Worker HITL、真实 SCM、生产沙箱或不可变对象存储。已有固定退款演示 fixture 不含 Human Criterion，继续展示原离线修复流程；M2 浏览器验收使用独立测试 fixture。没有运行付费模型评测。
