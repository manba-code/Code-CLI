package com.paicli.change;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test/local seam matching the SQLite governance contract. */
public final class InMemoryToolGovernanceStore implements ToolGovernanceStore {
    private final Map<String, ProjectToolPolicy> policies = new LinkedHashMap<>();
    private final Map<String, ToolApproval> approvals = new LinkedHashMap<>();

    @Override
    public synchronized ProjectToolPolicy policy(String projectId) {
        return policies.getOrDefault(projectId, ProjectToolPolicy.defaults(projectId, Instant.EPOCH));
    }

    @Override
    public synchronized ProjectToolPolicy updatePolicy(String projectId, long expectedVersion,
                                                        List<ProjectToolPolicy.Rule> rules, Instant now,
                                                        String actorId, String actorType) {
        ProjectToolPolicy current = policy(projectId);
        if (current.version() != expectedVersion) throw new ChangeConflictException("工具策略版本已过期");
        ProjectToolPolicy updated = new ProjectToolPolicy(projectId, expectedVersion + 1, rules, now);
        policies.put(projectId, updated);
        return updated;
    }

    @Override
    public synchronized ToolApproval createApproval(ToolApproval approval) {
        if (approvals.putIfAbsent(approval.id(), approval) != null) throw new ChangeConflictException("工具审批已存在");
        return approval;
    }

    @Override
    public synchronized ToolApproval recordDeniedCall(ToolApproval denial) {
        if (denial.status() != ToolApproval.Status.REJECTED) throw new IllegalArgumentException("必须记录拒绝结果");
        if (approvals.putIfAbsent(denial.id(), denial) != null) throw new ChangeConflictException("工具调用记录已存在");
        return denial;
    }

    @Override public synchronized Optional<ToolApproval> findApproval(String id) { return Optional.ofNullable(approvals.get(id)); }

    @Override
    public synchronized List<ToolApproval> approvals(ChangeTaskId changeId) {
        return approvals.values().stream().filter(value -> value.changeId().equals(changeId)).toList();
    }

    @Override
    public synchronized ToolApproval updateApproval(ToolApproval approval, ToolApproval.Status expectedStatus) {
        ToolApproval current = approvals.get(approval.id());
        if (current == null) throw new ChangeNotFoundException(approval.changeId());
        if (current.status() != expectedStatus) throw new ChangeConflictException("工具审批状态已变化");
        approvals.put(approval.id(), approval);
        return approval;
    }

    @Override
    public synchronized List<ToolApproval> pendingApprovals() {
        return new ArrayList<>(approvals.values().stream()
                .filter(value -> value.status() == ToolApproval.Status.PENDING).toList());
    }
}
