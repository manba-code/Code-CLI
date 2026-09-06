package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Single-project GitLab SCM adapter with remote reconciliation before every non-idempotent write. */
public final class GitLabScmAdapter implements ScmAdapter {
    private static final String STATUS_NAME = "PaiChange";
    private final GitLabSettings settings;
    private final GitLabClient client;
    private final BranchPusher pusher;
    private final ScmPublicationLedger ledger;

    public GitLabScmAdapter(Path database, GitLabSettings settings) throws SQLException {
        this(database, settings, new GitLabClient(settings), GitLabScmAdapter::pushBranch);
    }

    public GitLabScmAdapter(String jdbcUrl, String user, String password, GitLabSettings settings) throws SQLException {
        this(JdbcScmPublicationLedger.postgres(jdbcUrl, user, password), settings,
                new GitLabClient(settings), GitLabScmAdapter::pushBranch);
    }

    GitLabScmAdapter(Path database, GitLabSettings settings, GitLabClient client, BranchPusher pusher)
            throws SQLException {
        this(JdbcScmPublicationLedger.sqlite(database), settings, client, pusher);
    }

    private GitLabScmAdapter(ScmPublicationLedger ledger, GitLabSettings settings, GitLabClient client, BranchPusher pusher) {
        this.settings = Objects.requireNonNull(settings);
        this.client = Objects.requireNonNull(client);
        this.pusher = Objects.requireNonNull(pusher);
        this.ledger = Objects.requireNonNull(ledger);
    }

    @Override public String publicationKey(ChangeTask task) { return ScmPublicationKey.compute(task); }

    @Override
    public synchronized DeliveryRef publish(ChangeTask task, String conclusion) {
        if (!Set.of("pending", "success", "failure").contains(conclusion)) {
            throw new IllegalArgumentException("未知 Check 结论");
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
        } catch (Exception e) {
            throw new IllegalStateException("GitLab 任务分支推送失败或结果未知；可安全重试并对账", e);
        }
        String remoteHead = client.branchHead(run.branch());
        if (!run.headSha().equals(remoteHead)) {
            throw new ChangeConflictException("GitLab 远程分支 headSha 与已验证结果不一致");
        }
        JsonNode mr = ensureMergeRequest(task);
        String mrIid = mr.path("iid").asText();
        String mrUrl = mr.path("web_url").asText();
        if (mrIid.isBlank()) throw new IllegalStateException("GitLab Merge Request 响应缺少 iid");
        if (!"opened".equals(mr.path("state").asText())) {
            throw new ChangeConflictException("任务分支已有非 opened Merge Request；为避免重复 MR 已停止发布");
        }
        if (!run.headSha().equals(mr.path("sha").asText())) {
            throw new ChangeConflictException("GitLab Merge Request headSha 与已验证结果不一致");
        }

        String status = switch (conclusion) {
            case "success" -> "success";
            case "failure" -> "failed";
            default -> "pending";
        };
        String description = "PaiChange " + conclusion + " publication=" + key;
        ensureStatus(run, status, description, mrUrl);
        if (!run.headSha().equals(client.branchHead(run.branch()))) {
            throw new ChangeConflictException("GitLab 分支在 Check 发布期间前进，不能完成当前交付");
        }
        ledger.requireCurrentVersion(task);
        return save(task, conclusion, key, mrIid, mrUrl);
    }

    private JsonNode ensureMergeRequest(ChangeTask task) {
        RunRef run = task.run();
        Optional<JsonNode> existing = findRemoteMergeRequest(run.branch(), task.repository().baseRef());
        if (existing.isPresent()) return existing.get();
        String description = "PaiChange task " + task.id().value() + "\n\n"
                + "Source: " + task.source().url() + "\n"
                + "Spec digest: " + task.spec().digest() + "\n"
                + "Run: " + run.runId() + "\n"
                + "Head: " + run.headSha();
        try {
            return client.createMergeRequest(run.branch(), task.repository().baseRef(), task.title(), description);
        } catch (RuntimeException uncertain) {
            // Covers a timeout/5xx after GitLab committed the MR. Exact branch reconciliation prevents duplicates.
            Optional<JsonNode> reconciled = findRemoteMergeRequest(run.branch(), task.repository().baseRef());
            if (reconciled.isPresent()) return reconciled.get();
            throw uncertain;
        }
    }

    private Optional<JsonNode> findRemoteMergeRequest(String sourceBranch, String targetBranch) {
        JsonNode response = client.mergeRequests(sourceBranch, targetBranch);
        if (!response.isArray()) throw new IllegalStateException("GitLab Merge Request 列表响应无效");
        JsonNode selected = null;
        for (JsonNode candidate : response) {
            if (!sourceBranch.equals(candidate.path("source_branch").asText())
                    || !targetBranch.equals(candidate.path("target_branch").asText())) continue;
            if (selected == null || candidate.path("iid").asLong(Long.MAX_VALUE)
                    < selected.path("iid").asLong(Long.MAX_VALUE)) selected = candidate;
        }
        return Optional.ofNullable(selected);
    }

    private void ensureStatus(RunRef run, String status, String description, String mrUrl) {
        if (hasStatus(run, status, description)) return;
        try {
            client.publishStatus(run.headSha(), status, STATUS_NAME, run.branch(), description, mrUrl);
        } catch (RuntimeException uncertain) {
            // A response can be lost after the status was persisted remotely.
            if (!hasStatus(run, status, description)) throw uncertain;
        }
    }

    private boolean hasStatus(RunRef run, String status, String description) {
        JsonNode statuses = client.statuses(run.headSha(), STATUS_NAME, run.branch());
        if (!statuses.isArray()) throw new IllegalStateException("GitLab Check 列表响应无效");
        for (JsonNode candidate : statuses) {
            if (STATUS_NAME.equals(candidate.path("name").asText())
                    && run.branch().equals(candidate.path("ref").asText())
                    && run.headSha().equals(candidate.path("sha").asText())
                    && status.equals(candidate.path("status").asText())
                    && description.equals(candidate.path("description").asText())) return true;
        }
        return false;
    }

    private DeliveryRef save(ChangeTask task, String conclusion, String key, String mrIid, String mrUrl) {
        DeliveryRef saved = ledger.save(task, conclusion, key, mrIid, mrUrl);
        requireSame(saved, task, conclusion);
        return saved;
    }

    private void requireConfiguredTask(ChangeTask task) {
        if (task.run() == null || task.spec() == null) throw new ChangeValidationException("GitLab 发布缺少 Spec 或 Run");
        if (!settings.repository().equals(Path.of(task.repository().repository()).toAbsolutePath().normalize())
                || !settings.baseRef().equals(task.repository().baseRef())) {
            throw new ChangeValidationException("ChangeTask 不属于当前配置的 GitLab 仓库或 baseRef");
        }
    }

    private static void requireSame(DeliveryRef saved, ChangeTask task, String conclusion) {
        RunRef run = task.run();
        String approval = task.deliveryApproval() == null ? "" : task.deliveryApproval().id();
        if (!saved.changeId().equals(task.id().value()) || !saved.specDigest().equals(run.specDigest())
                || !saved.headSha().equals(run.headSha()) || !saved.runId().equals(run.runId())
                || saved.judgmentRevision() != task.judgmentRevision() || !saved.approvalId().equals(approval)
                || !saved.conclusion().equals(conclusion)) {
            throw new ChangeConflictException("同一 GitLab 发布身份已有不同结果");
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

    @Override public synchronized List<DeliveryRef> history(ChangeTaskId id) {
        return ledger.history(id);
    }

    @Override public String type() { return "GITLAB"; }
    @Override public void checkHealth() { client.branchHead(settings.baseRef()); }
    @Override public synchronized void close() { ledger.close(); }

    @FunctionalInterface
    interface BranchPusher { void push(GitLabSettings settings, String branch, String headSha) throws Exception; }

    private static void pushBranch(GitLabSettings settings, String branch, String headSha) throws Exception {
        Path repository = settings.repository();
        String localHead = git(repository, null, "rev-parse", "--verify", "refs/heads/" + branch + "^{commit}");
        if (!headSha.equals(localHead.trim())) throw new ChangeConflictException("本地任务分支 headSha 已变化");
        String remoteUrl = git(repository, null, "remote", "get-url", settings.remote()).trim();
        ProcessBuilder builder = new ProcessBuilder("git", "-C", repository.toString(), "push", "--porcelain",
                settings.remote(), "refs/heads/" + branch + ":refs/heads/" + branch);
        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            if (java.net.URI.create(remoteUrl).getUserInfo() != null) {
                throw new ChangeValidationException("GitLab remote URL 不得内嵌凭据");
            }
            String basic = Base64.getEncoder().encodeToString(("oauth2:" + settings.token()).getBytes(StandardCharsets.UTF_8));
            builder.environment().put("GIT_CONFIG_COUNT", "1");
            builder.environment().put("GIT_CONFIG_KEY_0", "http.extraHeader");
            builder.environment().put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        run(builder, "推送 GitLab 任务分支失败");
    }

    private static String git(Path repository, String error, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        return run(new ProcessBuilder(command), error == null ? "读取 Git 配置失败" : error);
    }

    private static String run(ProcessBuilder builder, String error) throws Exception {
        Process process = builder.redirectErrorStream(true).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
                throw new IOException(error + "：命令超时");
            }
            byte[] output = process.getInputStream().readAllBytes();
            if (process.exitValue() != 0) throw new IOException(error + " (exit=" + process.exitValue() + ")");
            return new String(output, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            Thread.currentThread().interrupt(); throw new IOException(error + "：命令被中断", e);
        }
    }
}
