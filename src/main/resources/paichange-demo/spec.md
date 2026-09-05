---
schema: paicli/change-spec/v1
id: SPEC_ID
revision: SPEC_REVISION
title: 退款超时边界修复（离线模拟）
intent:
  goal: 超过 24 小时的退款进入人工审核，24 小时整不进入；保留自动取消行为
  non_goals: [真实支付请求, 远程 SCM, 生产部署]
scope:
  mode: bounded
  include: [payment/RefundPolicy.java]
  exclude: []
acceptance:
  - id: AC-REFUND
    kind: behavior
    statement: 23 和 24 小时不人工审核，25 小时人工审核；自动取消行为保持不变
    oracle:
      type: deterministic
      verifiers: [VT-REFUND]
  - id: AC-SCOPE
    kind: scope
    statement: 仅修改退款策略文件
    oracle:
      type: deterministic
      verifiers: [VT-SCOPE]
verifiers:
  - id: VT-REFUND
    type: command
    command: java payment/RefundPolicy.java
    expect:
      exit_code: 0
  - id: VT-SCOPE
    type: path_scope
---
离线固定契约：Draft / ReAct 使用确定性替身；验证执行本地 Java fixture。
补充文本会保留在下方，固定演示的可执行验收条件不随自然语言改写。
