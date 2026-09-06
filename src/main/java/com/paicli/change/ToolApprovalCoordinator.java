package com.paicli.change;

import com.paicli.runtime.auth.Principal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Shared control-plane service for Worker approval waiters and HTTP decisions. */
public final class ToolApprovalCoordinator implements AutoCloseable {
    private final ToolGovernanceStore store;
    private final ChangeStore tasks;
    private final ChangeAuthorizer authorizer;
    private final Clock clock;
    private final Duration timeout;
    private final Semaphore waitingCapacity;
    private final Map<String, CompletableFuture<ToolApproval>> waiters = new ConcurrentHashMap<>();
    private final Set<ChangeTaskId> interruptedChanges = ConcurrentHashMap.newKeySet();

    public ToolApprovalCoordinator(ToolGovernanceStore store, ChangeStore tasks, ChangeAuthorizer authorizer) {
        this(store, tasks, authorizer, Clock.systemUTC(), configuredTimeout(), 1);
    }

    ToolApprovalCoordinator(ToolGovernanceStore store, ChangeStore tasks, ChangeAuthorizer authorizer,
                            Clock clock, Duration timeout, int maxWaiting) {
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (maxWaiting < 1) throw new IllegalArgumentException("maxWaiting 必须 >= 1");
        this.waitingCapacity = new Semaphore(maxWaiting, true);
    }

    public PersistentToolApprovalHandler handler(ChangeTask task, Path workingDirectory) {
        return new PersistentToolApprovalHandler(store, tasks, authorizer, task, workingDirectory,
                clock, timeout, waitingCapacity, waiters);
    }

    public ProjectToolPolicy.Decision decision(ChangeTask task, String toolName, String arguments, Path cwd) {
        return store.policy(ChangeProject.id(task.repository()))
                .evaluate(task.route().toolPolicy(), toolName, arguments, cwd);
    }

    public void recordDenied(PersistentToolApprovalHandler handler, String toolName, String arguments,
                             ProjectToolPolicy.Decision decision, String reason) {
        var identity = handler.runIdentity();
        if (identity == null) return;
        String canonical;
        try { canonical = PersistentToolApprovalHandler.canonicalArguments(arguments); }
        catch (RuntimeException ignored) { canonical = "{}"; }
        java.time.Instant now = clock.instant();
        ToolApproval denial = new ToolApproval(
                "tool_denial_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                handler.task().id(), ChangeProject.id(handler.task().repository()), identity.runId(),
                "call_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                toolName, sha256(canonical), PersistentToolApprovalHandler.redactedPreview(canonical),
                handler.workingDirectory().toString(), identity.specId(), identity.revision(), identity.specDigest(),
                handler.task().route().toolPolicy(), decision.policyVersion(), decision.ruleId(),
                ToolApproval.Status.REJECTED, reason, "tool-policy", "SYSTEM", false,
                now, now, now);
        store.recordDeniedCall(denial);
    }

    public List<ToolApproval> approvals(ChangeTaskId changeId) { return store.approvals(changeId); }

    public ProjectToolPolicy policy(String projectId) { return store.policy(projectId); }

    public ProjectToolPolicy updatePolicy(String projectId, long expectedVersion,
                                          List<ProjectToolPolicy.Rule> rules, Principal actor) {
        authorizer.require(actor, projectId, ChangePermission.MANAGE_TOOL_POLICY);
        ProjectToolPolicy updated = store.updatePolicy(projectId, expectedVersion, rules, clock.instant(),
                actor.subjectId(), actor.actorType());
        for (ToolApproval pending : store.pendingApprovals()) {
            if (!pending.projectId().equals(projectId) || pending.policyVersion() == updated.version()) continue;
            ToolApproval stale;
            try {
                stale = store.updateApproval(pending.expire(ToolApproval.Status.STALE,
                        "工具策略版本已更新，旧请求安全失效", clock.instant()), ToolApproval.Status.PENDING);
            } catch (ChangeConflictException ignored) { continue; }
            CompletableFuture<ToolApproval> waiter = waiters.get(stale.id());
            if (waiter != null) waiter.complete(stale);
        }
        return updated;
    }

    public ToolApproval decide(ChangeTaskId changeId, String approvalId, ToolApproval.Status decision,
                               long expectedPolicyVersion, String expectedArgumentsDigest,
                               String expectedCallId, String expectedRunId, String expectedSpecDigest,
                               String reason, Principal actor) {
        ToolApproval current = store.findApproval(approvalId)
                .orElseThrow(() -> new ChangeNotFoundException(changeId));
        if (!current.changeId().equals(changeId)) throw new ChangeNotFoundException(changeId);
        authorizer.require(actor, current.projectId(), ChangePermission.APPROVE_TOOL);
        if (actor.type() != com.paicli.runtime.auth.PrincipalType.HUMAN) {
            throw new ChangeForbiddenException("工具审批必须由 HUMAN Principal 完成");
        }
        if (current.policyVersion() != expectedPolicyVersion
                || !current.argumentsDigest().equals(expectedArgumentsDigest)
                || !current.callId().equals(expectedCallId)
                || !current.runId().equals(expectedRunId)
                || !current.specDigest().equals(expectedSpecDigest)) {
            throw new ChangeConflictException("工具调用身份或策略版本已变化，请刷新");
        }
        ChangeTask task = tasks.find(changeId).orElseThrow(() -> new ChangeNotFoundException(changeId));
        if (!actor.localTrusted() && current.profile() != ExecutionRoute.ToolPolicyProfile.STANDARD
                && task.requesterId().equals(actor.subjectId())) {
            throw new ChangeForbiddenException("RESTRICTED/LOCKED_DOWN 工具调用不能由任务发起人自批");
        }
        if (store.policy(current.projectId()).version() != current.policyVersion()) {
            throw new ChangeConflictException("工具策略已更新，旧请求不能批准");
        }
        if (!waiters.containsKey(approvalId)) {
            throw new ChangeConflictException("工具执行等待已结束；不会重放该调用");
        }
        ToolApproval updated = current.decide(decision, reason, actor.subjectId(), actor.actorType(),
                actor.localTrusted(), clock.instant());
        updated = store.updateApproval(updated, ToolApproval.Status.PENDING);
        CompletableFuture<ToolApproval> waiter = waiters.get(approvalId);
        if (waiter != null) waiter.complete(updated);
        return updated;
    }

    /** Startup recovery: pending calls belonged to lost stacks and must never be replayed. */
    public void recoverPending() {
        for (ToolApproval pending : store.pendingApprovals()) {
            ToolApproval interrupted = pending.expire(ToolApproval.Status.INTERRUPTED,
                    "服务重启，原执行栈已丢失；未确认调用不会自动执行或重放", clock.instant());
            try {
                store.updateApproval(interrupted, ToolApproval.Status.PENDING);
                interruptedChanges.add(pending.changeId());
            } catch (ChangeConflictException ignored) { /* A completed decision wins. */ }
        }
    }

    public boolean hadInterruptedApproval(ChangeTaskId changeId) { return interruptedChanges.contains(changeId); }

    @Override
    public void close() {
        for (Map.Entry<String, CompletableFuture<ToolApproval>> entry : waiters.entrySet()) {
            ToolApproval current = store.findApproval(entry.getKey()).orElse(null);
            if (current != null && current.status() == ToolApproval.Status.PENDING) {
                try {
                    current = store.updateApproval(current.expire(ToolApproval.Status.CANCELED,
                            "平台关闭，待审批调用未执行", clock.instant()), ToolApproval.Status.PENDING);
                } catch (ChangeConflictException ignored) { current = store.findApproval(entry.getKey()).orElse(current); }
            }
            entry.getValue().complete(current);
        }
        waiters.clear();
    }

    private static Duration configuredTimeout() {
        String configured = System.getProperty("paichange.tool.approval.timeout.seconds");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICHANGE_TOOL_APPROVAL_TIMEOUT_SECONDS");
        }
        if (configured == null || configured.isBlank()) configured = "300";
        long seconds;
        try { seconds = Long.parseLong(configured); }
        catch (NumberFormatException e) { seconds = 300; }
        return Duration.ofSeconds(Math.max(1, Math.min(3600, seconds)));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
