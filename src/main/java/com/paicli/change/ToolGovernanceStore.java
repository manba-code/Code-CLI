package com.paicli.change;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ToolGovernanceStore {
    ProjectToolPolicy policy(String projectId);

    ProjectToolPolicy updatePolicy(String projectId, long expectedVersion,
                                   List<ProjectToolPolicy.Rule> rules, Instant now,
                                   String actorId, String actorType);

    ToolApproval createApproval(ToolApproval approval);

    ToolApproval recordDeniedCall(ToolApproval denial);

    Optional<ToolApproval> findApproval(String approvalId);

    List<ToolApproval> approvals(ChangeTaskId changeId);

    ToolApproval updateApproval(ToolApproval approval, ToolApproval.Status expectedStatus);

    List<ToolApproval> pendingApprovals();
}
