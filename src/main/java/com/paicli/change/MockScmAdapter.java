package com.paicli.change;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Local current Check projection plus immutable publication history. Never contacts a real SCM. */
public final class MockScmAdapter implements AutoCloseable {
    private final Connection connection;

    public MockScmAdapter(Path database) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS mock_pull_requests (
                        change_id TEXT PRIMARY KEY REFERENCES change_tasks(id),
                        pr_id TEXT NOT NULL UNIQUE, title TEXT NOT NULL, branch TEXT NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS mock_pr_checks (
                        change_id TEXT NOT NULL REFERENCES mock_pull_requests(change_id),
                        spec_digest TEXT NOT NULL, head_sha TEXT NOT NULL,
                        conclusion TEXT NOT NULL CHECK(conclusion IN ('success','failure','pending')),
                        evidence_path TEXT NOT NULL,
                        PRIMARY KEY(change_id, spec_digest, head_sha))
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS mock_check_history (
                        publication_key TEXT PRIMARY KEY,
                        change_id TEXT NOT NULL REFERENCES mock_pull_requests(change_id),
                        spec_digest TEXT NOT NULL, head_sha TEXT NOT NULL, run_id TEXT NOT NULL,
                        judgment_revision INTEGER NOT NULL, approval_id TEXT NOT NULL,
                        task_version INTEGER NOT NULL, conclusion TEXT NOT NULL,
                        evidence_path TEXT NOT NULL, published_at TEXT NOT NULL)
                    """);
            // Retain pre-M2 Checks without guessing approval/run identity for historical heads.
            s.execute("""
                    INSERT OR IGNORE INTO mock_check_history
                    SELECT 'legacy:' || c.change_id || ':' || c.spec_digest || ':' || c.head_sha,
                           c.change_id, c.spec_digest, c.head_sha,
                           CASE WHEN t.run_spec_digest = c.spec_digest AND t.run_head_sha = c.head_sha
                                THEN COALESCE(t.run_id, '') ELSE '' END,
                           0, '', 0, c.conclusion, c.evidence_path, t.updated_at
                    FROM mock_pr_checks c JOIN change_tasks t ON t.id = c.change_id
                    WHERE NOT EXISTS (SELECT 1 FROM mock_check_history h WHERE h.change_id = c.change_id
                        AND h.spec_digest = c.spec_digest AND h.head_sha = c.head_sha)
                    """);
        }
    }

    public String publicationKey(ChangeTask task) {
        try {
            String identity = ChangeJson.MAPPER.writeValueAsString(List.of(task.id().value(), task.run().specDigest(),
                    task.run().headSha(), task.run().runId(), task.judgmentRevision(),
                    task.deliveryApproval() == null ? "" : task.deliveryApproval().id()));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // Only ChangeWorkflow chooses eligibility and conclusion. Same identity is immutable and idempotent.
    synchronized DeliveryRef publish(ChangeTask task, String conclusion) {
        if (!Set.of("pending", "success", "failure").contains(conclusion)) throw new IllegalArgumentException("未知 Check 结论");
        RunRef run = Objects.requireNonNull(task.run());
        String key = publicationKey(task);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement current = connection.prepareStatement("SELECT version FROM change_tasks WHERE id = ?")) {
                current.setString(1, task.id().value());
                try (ResultSet row = current.executeQuery()) {
                    if (!row.next() || row.getLong(1) != task.version()) throw new ChangeConflictException("发布任务版本已过期");
                }
            }
            try (PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO mock_pull_requests(change_id, pr_id, title, branch)
                    VALUES (?, ?, ?, ?) ON CONFLICT(change_id) DO NOTHING
                    """)) {
                s.setString(1, task.id().value()); s.setString(2, "mock-pr-" + task.id().value());
                s.setString(3, task.title()); s.setString(4, run.branch()); s.executeUpdate();
            }
            List<DeliveryRef> history = history(task.id());
            DeliveryRef latest = history.isEmpty() ? null : history.get(history.size() - 1);
            if (latest != null && latest.taskVersion() > task.version()) {
                throw new ChangeConflictException("迟到发布不能回退更新的 Check");
            }
            Optional<DeliveryRef> existing = history.stream().filter(h -> h.publicationKey().equals(key)).findFirst();
            if (existing.isPresent()) {
                DeliveryRef ref = existing.get();
                if (!ref.conclusion().equals(conclusion) || !ref.evidencePath().equals(run.evidencePath().toString())) {
                    throw new ChangeConflictException("同一判断与审批版本已有不同 Check");
                }
                if (latest != null && !latest.publicationKey().equals(key)) throw new ChangeConflictException("发布版本已被替代");
                connection.commit();
                return ref;
            }
            if (latest != null && latest.taskVersion() == task.version() && !latest.publicationKey().startsWith("legacy:")) {
                throw new ChangeConflictException("同一任务版本不能发布不同身份");
            }
            try (PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO mock_check_history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                s.setString(1, key); s.setString(2, task.id().value()); s.setString(3, run.specDigest());
                s.setString(4, run.headSha()); s.setString(5, run.runId()); s.setLong(6, task.judgmentRevision());
                s.setString(7, task.deliveryApproval() == null ? "" : task.deliveryApproval().id());
                s.setLong(8, task.version()); s.setString(9, conclusion); s.setString(10, run.evidencePath().toString());
                s.setString(11, java.time.Instant.now().toString()); s.executeUpdate();
            }
            try (PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO mock_pr_checks(change_id, spec_digest, head_sha, conclusion, evidence_path)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT(change_id, spec_digest, head_sha)
                    DO UPDATE SET conclusion = excluded.conclusion, evidence_path = excluded.evidence_path
                    """)) {
                s.setString(1, task.id().value()); s.setString(2, run.specDigest()); s.setString(3, run.headSha());
                s.setString(4, conclusion); s.setString(5, run.evidencePath().toString()); s.executeUpdate();
            }
            DeliveryRef ref = find(task).orElseThrow();
            connection.commit();
            return ref;
        } catch (SQLException | RuntimeException e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("保存 Mock PR Check 失败", e);
        } finally {
            try { connection.setAutoCommit(true); }
            catch (SQLException e) { throw new IllegalStateException("恢复 Mock SCM 事务失败", e); }
        }
    }

    public synchronized Optional<DeliveryRef> find(ChangeTask task) {
        if (task.run() == null) return Optional.empty();
        List<DeliveryRef> history = history(task.id());
        if (history.isEmpty()) return Optional.empty();
        DeliveryRef latest = history.get(history.size() - 1);
        return latest.specDigest().equals(task.run().specDigest()) && latest.headSha().equals(task.run().headSha())
                && latest.runId().equals(task.run().runId()) ? Optional.of(latest) : Optional.empty();
    }

    public synchronized List<DeliveryRef> history(ChangeTaskId id) {
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT p.pr_id, h.* FROM mock_check_history h JOIN mock_pull_requests p USING(change_id)
                WHERE change_id = ? ORDER BY task_version, h.rowid
                """)) {
            s.setString(1, id.value());
            List<DeliveryRef> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) result.add(new DeliveryRef(r.getString("pr_id"), r.getString("change_id"),
                        r.getString("spec_digest"), r.getString("head_sha"), r.getString("conclusion"),
                        r.getString("evidence_path"), r.getString("run_id"), r.getLong("judgment_revision"),
                        r.getString("approval_id"), r.getLong("task_version"), r.getString("publication_key"), r.getString("published_at")));
            }
            return List.copyOf(result);
        } catch (SQLException e) { throw new IllegalStateException("读取 Mock Check 历史失败", e); }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
