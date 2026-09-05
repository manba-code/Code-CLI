package com.paicli.change;

import com.paicli.config.PaiCliConfig;

import java.util.Objects;

/** 业务审批职责分离规则；与工具调用的 HITL ApprovalPolicy 无关。 */
public record ChangeApprovalPolicy(boolean forbidRequesterSelfApprovalForMediumAndHigh) {
    public static ChangeApprovalPolicy fromConfig(PaiCliConfig config) {
        Objects.requireNonNull(config, "config");
        return new ChangeApprovalPolicy(
                config.getPaiChange().isForbidRequesterSelfApprovalForMediumAndHigh());
    }

    public void requireAllowed(ChangeTask task, RiskLevel risk, String approverId) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(risk, "risk");
        String actor = Objects.requireNonNull(approverId, "approverId").trim();
        if (forbidRequesterSelfApprovalForMediumAndHigh
                && risk != RiskLevel.LOW
                && task.requesterId().equals(actor)) {
            throw new ChangeForbiddenException("中高风险 ChangeTask 禁止发起人自批");
        }
    }
}
