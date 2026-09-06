package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.hitl.HitlHandler;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;
import com.paicli.spec.SpecRunResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Durable, exact-call HITL handler used only by PaiChange background workers. */
public final class PersistentToolApprovalHandler implements HitlHandler, AutoCloseable {
    private static final int MAX_PREVIEW = 2_000;
    private static final Set<String> SECRET_KEYS = Set.of(
            "authorization", "cookie", "password", "passwd", "secret", "token", "api_key", "apikey", "access_key");

    private final ToolGovernanceStore store;
    private final ChangeStore tasks;
    private final ChangeAuthorizer authorizer;
    private final ChangeTask task;
    private final Path workingDirectory;
    private final Clock clock;
    private final Duration timeout;
    private final Semaphore waitingCapacity;
    private final Map<String, CompletableFuture<ToolApproval>> waiters;
    private final Set<String> ownedWaiters = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SpecRunResult.RunIdentity> runIdentity = new AtomicReference<>();
    private volatile boolean enabled = true;
    private volatile boolean closed;

    PersistentToolApprovalHandler(ToolGovernanceStore store, ChangeStore tasks, ChangeAuthorizer authorizer,
                                  ChangeTask task, Path workingDirectory, Clock clock, Duration timeout,
                                  Semaphore waitingCapacity,
                                  Map<String, CompletableFuture<ToolApproval>> waiters) {
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.task = Objects.requireNonNull(task, "task");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.waitingCapacity = Objects.requireNonNull(waitingCapacity, "waitingCapacity");
        this.waiters = Objects.requireNonNull(waiters, "waiters");
    }

    public void bindRunIdentity(SpecRunResult.RunIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        SpecRef spec = Objects.requireNonNull(task.spec(), "task.spec");
        if (!identity.specId().equals(spec.specId()) || identity.revision() != spec.revision()
                || !identity.specDigest().equals(spec.digest())) {
            throw new ChangeConflictException("Spec Run identity 与工具审批上下文不一致");
        }
        SpecRunResult.RunIdentity previous = runIdentity.getAndSet(identity);
        if (previous != null && !previous.runId().equals(identity.runId())) {
            throw new ChangeConflictException("工具审批运行身份不能被替换");
        }
    }

    SpecRunResult.RunIdentity runIdentity() { return runIdentity.get(); }

    Path workingDirectory() { return workingDirectory; }

    ChangeTask task() { return task; }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled || closed) return ApprovalResult.reject("后台工具审批处理器不可用");
        SpecRunResult.RunIdentity identity = runIdentity.get();
        if (identity == null) return ApprovalResult.reject("Spec Run 尚未绑定，拒绝工具执行");
        ProjectToolPolicy policy = store.policy(ChangeProject.id(task.repository()));
        ProjectToolPolicy.Decision decision;
        try {
            decision = policy.evaluate(task.route().toolPolicy(), request.toolName(), request.arguments(), workingDirectory);
        } catch (RuntimeException e) {
            return ApprovalResult.reject("组织工具策略无法评估调用");
        }
        if (decision.effect() == ProjectToolPolicy.Effect.DENY) return ApprovalResult.reject(decision.reason());
        if (decision.effect() == ProjectToolPolicy.Effect.ALLOW) return ApprovalResult.approve();
        if (!waitingCapacity.tryAcquire()) return ApprovalResult.reject("工具审批等待容量已满，保守拒绝；其他 Worker 可继续执行");

        String canonical = canonicalArguments(request.arguments());
        Instant now = clock.instant();
        ToolApproval approval = new ToolApproval(
                "tool_approval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                task.id(), ChangeProject.id(task.repository()), identity.runId(),
                "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                request.toolName(), sha256(canonical), redactedPreview(canonical), workingDirectory.toString(),
                identity.specId(), identity.revision(), identity.specDigest(), task.route().toolPolicy(),
                decision.policyVersion(), decision.ruleId(), ToolApproval.Status.PENDING, "", "", "", false,
                now, null, now.plus(timeout));
        CompletableFuture<ToolApproval> waiter = new CompletableFuture<>();
        try {
            waiters.put(approval.id(), waiter);
            ownedWaiters.add(approval.id());
            store.createApproval(approval);
            ToolApproval outcome;
            try {
                outcome = waiter.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                outcome = failPending(approval.id(), ToolApproval.Status.TIMED_OUT, "工具审批等待超时");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome = failPending(approval.id(), ToolApproval.Status.CANCELED, "Worker 已取消，未执行待审批调用");
            } catch (java.util.concurrent.ExecutionException e) {
                outcome = failPending(approval.id(), ToolApproval.Status.CANCELED, "工具审批等待被中断");
            }
            if (outcome.status() != ToolApproval.Status.APPROVED) {
                return ApprovalResult.reject(outcome.decisionReason().isBlank() ? outcome.status().name() : outcome.decisionReason());
            }
            String invalid = executionRevalidationFailure(outcome);
            if (invalid != null) {
                ToolApproval stale = outcome.invalidate(invalid, clock.instant());
                try { store.updateApproval(stale, ToolApproval.Status.APPROVED); }
                catch (ChangeConflictException ignored) { /* A concurrent shutdown can only make this more restrictive. */ }
                return ApprovalResult.reject(invalid);
            }
            return ApprovalResult.approve();
        } finally {
            waiters.remove(approval.id());
            ownedWaiters.remove(approval.id());
            waitingCapacity.release();
        }
    }

    private String executionRevalidationFailure(ToolApproval approval) {
        ChangeTask current = tasks.find(task.id()).orElse(null);
        if (current == null || current.spec() == null || current.route() == null
                || !current.spec().digest().equals(approval.specDigest())
                || current.route().toolPolicy() != approval.profile()
                || !Set.of(ChangeState.RUNNING, ChangeState.VERIFYING).contains(current.state())) {
            return "任务、Spec 或执行 route 已变化，旧工具批准失效";
        }
        ProjectToolPolicy policy = store.policy(approval.projectId());
        if (policy.version() != approval.policyVersion()) return "工具策略版本已变化，旧批准失效";
        Principal approver;
        try {
            approver = new Principal(approval.approverId(), approval.approverId(),
                    PrincipalType.valueOf(approval.approverType()), "persisted-tool-approval", null,
                    approval.approverLocalTrusted());
        } catch (RuntimeException e) {
            return "审批主体身份无效";
        }
        if (!authorizer.permissions(approver, approval.projectId()).contains(ChangePermission.APPROVE_TOOL)) {
            return "审批主体的工具批准权限已撤销";
        }
        return null;
    }

    private ToolApproval failPending(String id, ToolApproval.Status status, String reason) {
        ToolApproval current = store.findApproval(id).orElseThrow();
        if (current.status() != ToolApproval.Status.PENDING) return current;
        try { return store.updateApproval(current.expire(status, reason, clock.instant()), ToolApproval.Status.PENDING); }
        catch (ChangeConflictException e) { return store.findApproval(id).orElseThrow(); }
    }

    @Override public boolean isEnabled() { return enabled && !closed; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public void close() {
        closed = true;
        for (String approvalId : Set.copyOf(ownedWaiters)) {
            CompletableFuture<ToolApproval> waiter = waiters.get(approvalId);
            if (waiter == null) continue;
            ToolApproval canceled = failPending(approvalId, ToolApproval.Status.CANCELED,
                    "平台关闭，待审批调用未执行");
            waiter.complete(canceled);
        }
    }

    static String canonicalArguments(String raw) {
        try {
            JsonNode root = ChangeJson.MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
            return ChangeJson.MAPPER.writeValueAsString(sorted(root));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("工具参数不是有效 JSON", e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = ChangeJson.MAPPER.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new java.util.ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> result.set(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = ChangeJson.MAPPER.createArrayNode();
            node.forEach(value -> result.add(sorted(value)));
            return result;
        }
        return node.deepCopy();
    }

    static String redactedPreview(String canonical) {
        try {
            JsonNode root = ChangeJson.MAPPER.readTree(canonical);
            redact(root);
            String value = ChangeJson.MAPPER.writeValueAsString(root)
                    .replaceAll("(?i)Bearer\\s+[^\\s\\\"'}]+", "Bearer ***")
                    .replaceAll("(?i)([A-Z0-9_]*(?:TOKEN|KEY|SECRET|PASSWORD)[A-Z0-9_]*=)[^\\s]+", "$1***");
            return value.length() <= MAX_PREVIEW ? value : value.substring(0, MAX_PREVIEW) + "...(truncated)";
        } catch (java.io.IOException e) {
            return "{\"redacted\":true}";
        }
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String normalized = name.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
                if (normalized.equals("content") || normalized.equals("body") || normalized.equals("data")) {
                    JsonNode value = object.get(name);
                    object.put(name, "<redacted payload: " + (value == null ? 0 : value.asText("").length()) + " chars>");
                } else if (SECRET_KEYS.stream().anyMatch(key -> normalized.equals(key) || normalized.endsWith("_" + key))) {
                    object.put(name, "***");
                } else redact(object.get(name));
            }
        } else if (node != null && node.isArray()) node.forEach(PersistentToolApprovalHandler::redact);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
