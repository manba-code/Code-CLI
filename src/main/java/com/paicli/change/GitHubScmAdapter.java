package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Single-repository GitHub SCM adapter with reconciliation before every non-idempotent write. */
public final class GitHubScmAdapter implements ScmAdapter {
    private static final String STATUS_CONTEXT = "PaiChange";
    private final GitHubSettings settings;
    private final GitHubClient client;
    private final BranchPusher pusher;
    private final ScmPublicationLedger ledger;

    public GitHubScmAdapter(Path database, GitHubSettings settings) throws SQLException {
        this(database, settings, new GitHubClient(settings), GitHubScmAdapter::pushBranch);
    }

    public GitHubScmAdapter(String jdbcUrl, String user, String password, GitHubSettings settings) throws SQLException {
        this(JdbcScmPublicationLedger.postgres(jdbcUrl, user, password), settings,
                new GitHubClient(settings), GitHubScmAdapter::pushBranch);
    }

    GitHubScmAdapter(Path database, GitHubSettings settings, GitHubClient client, BranchPusher pusher)
            throws SQLException {
        this(JdbcScmPublicationLedger.sqlite(database), settings, client, pusher);
    }

    private GitHubScmAdapter(ScmPublicationLedger ledger, GitHubSettings settings,
                             GitHubClient client, BranchPusher pusher) {
        this.settings = Objects.requireNonNull(settings);
        this.client = Objects.requireNonNull(client);
        this.pusher = Objects.requireNonNull(pusher);
        this.ledger = Objects.requireNonNull(ledger);
    }

    @Override public String publicationKey(ChangeTask task) { return ScmPublicationKey.compute(task); }

    @Override
    public synchronized DeliveryRef publish(ChangeTask task, String conclusion) {
        if (!Set.of("pending", "success", "failure").contains(conclusion)) {
            throw new IllegalArgumentException("未知 Commit Status 结论");
        }
        requireConfiguredTask(task);
        ledger.requireCurrentVersion(task);
        String key = publicationKey(task);
        Optional<DeliveryRef> existing = ledger.byKey(key);
        if (existing.isPresent()) {
            requireSame(existing.get(), task, conclusion);
            return existing.get();
        }

        RunRef run = Objects.requireNonNull(task.run());
        try {
            pusher.push(settings, run.branch(), run.headSha());
        } catch (ChangeConflictException | ChangeValidationException deterministic) {
            throw deterministic;
        } catch (Exception uncertain) {
            // A git push may reach the remote before its result becomes unavailable locally.
            try {
                if (!run.headSha().equals(client.branchHead(run.branch()))) {
                    throw new IllegalStateException("GitHub 任务分支推送失败或结果未知；远端 head 未对账", uncertain);
                }
            } catch (RuntimeException reconciliationFailure) {
                if (reconciliationFailure.getCause() == uncertain) throw reconciliationFailure;
                uncertain.addSuppressed(reconciliationFailure);
                throw new IllegalStateException("GitHub 任务分支推送失败或结果未知；可安全重试并对账", uncertain);
            }
        }
        String remoteHead = client.branchHead(run.branch());
        if (!run.headSha().equals(remoteHead)) {
            throw new ChangeConflictException("GitHub 远程分支 headSha 与已验证结果不一致");
        }
        JsonNode pull = ensurePullRequest(task);
        String pullNumber = pull.path("number").asText();
        String pullUrl = pull.path("html_url").asText();
        if (pullNumber.isBlank() || pullUrl.isBlank()) {
            throw new IllegalStateException("GitHub Pull Request 响应缺少 number 或 html_url");
        }
        if (!"open".equals(pull.path("state").asText()) || pull.path("merged").asBoolean(false)) {
            throw new ChangeConflictException("任务分支已有 closed/merged Pull Request；为避免重复 PR 已停止发布");
        }
        if (!run.headSha().equals(pull.path("head").path("sha").asText())) {
            throw new ChangeConflictException("GitHub Pull Request headSha 与已验证结果不一致");
        }

        String description = "PaiChange " + conclusion + " publication=" + key;
        ensureStatus(run, conclusion, description, pullUrl);
        if (!run.headSha().equals(client.branchHead(run.branch()))) {
            throw new ChangeConflictException("GitHub 分支在 Commit Status 发布期间前进，不能完成当前交付");
        }
        ledger.requireCurrentVersion(task);
        return save(task, conclusion, key, pullNumber, pullUrl);
    }

    private JsonNode ensurePullRequest(ChangeTask task) {
        RunRef run = task.run();
        Optional<JsonNode> existing = findRemotePullRequest(run.branch(), task.repository().baseRef());
        if (existing.isPresent()) return existing.get();
        String body = "PaiChange task " + task.id().value() + "\n\n"
                + "Source: " + task.source().url() + "\n"
                + "Spec digest: " + task.spec().digest() + "\n"
                + "Run: " + run.runId() + "\n"
                + "Head: " + run.headSha();
        try {
            return client.createPullRequest(run.branch(), task.repository().baseRef(), task.title(), body);
        } catch (RuntimeException uncertain) {
            // A timeout/5xx may occur after GitHub persisted the PR. Exact head/base reconciliation prevents duplicates.
            Optional<JsonNode> reconciled = findRemotePullRequest(run.branch(), task.repository().baseRef());
            if (reconciled.isPresent()) return reconciled.get();
            throw uncertain;
        }
    }

    private Optional<JsonNode> findRemotePullRequest(String sourceBranch, String targetBranch) {
        JsonNode response = client.pullRequests(sourceBranch, targetBranch);
        if (!response.isArray()) throw new IllegalStateException("GitHub Pull Request 列表响应无效");
        JsonNode selected = null;
        for (JsonNode candidate : response) {
            if (!sourceBranch.equals(candidate.path("head").path("ref").asText())
                    || !targetBranch.equals(candidate.path("base").path("ref").asText())
                    || !settings.slug().equalsIgnoreCase(candidate.path("head").path("repo").path("full_name").asText())) {
                continue;
            }
            if (selected == null
                    || (reusable(candidate) && !reusable(selected))
                    || (reusable(candidate) == reusable(selected) && candidate.path("number").asLong(Long.MAX_VALUE)
                    < selected.path("number").asLong(Long.MAX_VALUE))) selected = candidate;
        }
        return Optional.ofNullable(selected);
    }

    private static boolean reusable(JsonNode pull) {
        return "open".equals(pull.path("state").asText()) && !pull.path("merged").asBoolean(false);
    }

    private void ensureStatus(RunRef run, String state, String description, String pullUrl) {
        if (hasStatus(run, state, description, pullUrl)) return;
        try {
            client.publishStatus(run.headSha(), state, STATUS_CONTEXT, description, pullUrl);
        } catch (RuntimeException uncertain) {
            // Commit Status has no client idempotency key; reconcile the exact immutable publication identity.
            if (!hasStatus(run, state, description, pullUrl)) throw uncertain;
        }
    }

    private boolean hasStatus(RunRef run, String state, String description, String pullUrl) {
        JsonNode statuses = client.statuses(run.headSha());
        if (!statuses.isArray()) throw new IllegalStateException("GitHub Commit Status 列表响应无效");
        for (JsonNode candidate : statuses) {
            if (STATUS_CONTEXT.equals(candidate.path("context").asText())
                    && state.equals(candidate.path("state").asText())
                    && description.equals(candidate.path("description").asText())
                    && pullUrl.equals(candidate.path("target_url").asText())) return true;
        }
        return false;
    }

    private DeliveryRef save(ChangeTask task, String conclusion, String key, String pullNumber, String pullUrl) {
        DeliveryRef saved = ledger.save(task, conclusion, key, pullNumber, pullUrl);
        requireSame(saved, task, conclusion);
        return saved;
    }

    private void requireConfiguredTask(ChangeTask task) {
        if (task.run() == null || task.spec() == null) throw new ChangeValidationException("GitHub 发布缺少 Spec 或 Run");
        if (!settings.repository().equals(Path.of(task.repository().repository()).toAbsolutePath().normalize())
                || !settings.baseRef().equals(task.repository().baseRef())) {
            throw new ChangeValidationException("ChangeTask 不属于当前配置的 GitHub 仓库或 baseRef");
        }
    }

    private static void requireSame(DeliveryRef saved, ChangeTask task, String conclusion) {
        RunRef run = task.run();
        String approval = task.deliveryApproval() == null ? "" : task.deliveryApproval().id();
        if (!saved.changeId().equals(task.id().value()) || !saved.specDigest().equals(run.specDigest())
                || !saved.headSha().equals(run.headSha()) || !saved.runId().equals(run.runId())
                || saved.judgmentRevision() != task.judgmentRevision() || !saved.approvalId().equals(approval)
                || !saved.conclusion().equals(conclusion)) {
            throw new ChangeConflictException("同一 GitHub 发布身份已有不同结果");
        }
    }

    @Override public synchronized Optional<DeliveryRef> find(ChangeTask task) {
        if (task.run() == null) return Optional.empty();
        List<DeliveryRef> entries = ledger.history(task.id());
        if (entries.isEmpty()) return Optional.empty();
        DeliveryRef latest = entries.get(entries.size() - 1);
        return latest.specDigest().equals(task.run().specDigest()) && latest.headSha().equals(task.run().headSha())
                && latest.runId().equals(task.run().runId()) ? Optional.of(latest) : Optional.empty();
    }

    @Override public synchronized List<DeliveryRef> history(ChangeTaskId id) { return ledger.history(id); }
    @Override public String type() { return "GITHUB"; }
    @Override public void checkHealth() { client.branchHead(settings.baseRef()); }
    @Override public synchronized void close() { ledger.close(); }

    @FunctionalInterface
    interface BranchPusher { void push(GitHubSettings settings, String branch, String headSha) throws Exception; }

    private static void pushBranch(GitHubSettings settings, String branch, String headSha) throws Exception {
        GitBranchPusher.push(settings.repository(), settings.remote(), branch, headSha,
                "x-access-token", settings.token(), "GitHub");
    }
}
