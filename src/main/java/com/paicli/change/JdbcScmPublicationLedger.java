package com.paicli.change;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite and PostgreSQL adapters behind the internal SCM publication ledger seam. */
final class JdbcScmPublicationLedger implements ScmPublicationLedger {
    private final Connection connection;
    private final boolean postgres;

    static JdbcScmPublicationLedger sqlite(Path database) throws SQLException {
        return new JdbcScmPublicationLedger(DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize()), false);
    }

    static JdbcScmPublicationLedger postgres(String jdbcUrl, String user, String password) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        PostgresStorageMigrations.migrate(connection);
        return new JdbcScmPublicationLedger(connection, true);
    }

    private JdbcScmPublicationLedger(Connection connection, boolean postgres) throws SQLException {
        this.connection = connection; this.postgres = postgres;
        if (!postgres) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000"); statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS gitlab_publications (
                            publication_key TEXT PRIMARY KEY, change_id TEXT NOT NULL REFERENCES change_tasks(id),
                            mr_iid TEXT NOT NULL, mr_url TEXT NOT NULL, spec_digest TEXT NOT NULL,
                            head_sha TEXT NOT NULL, run_id TEXT NOT NULL, judgment_revision INTEGER NOT NULL,
                            approval_id TEXT NOT NULL, task_version INTEGER NOT NULL, conclusion TEXT NOT NULL,
                            evidence_path TEXT NOT NULL, published_at TEXT NOT NULL)
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_gitlab_publications_change ON gitlab_publications(change_id, task_version)");
            }
        }
    }

    @Override public synchronized void requireCurrentVersion(ChangeTask task) {
        try (PreparedStatement query = connection.prepareStatement("SELECT version FROM change_tasks WHERE id=?")) {
            query.setString(1, task.id().value());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getLong(1) != task.version()) throw new ChangeConflictException("发布任务版本已过期");
            }
        } catch (SQLException e) { throw new IllegalStateException("读取发布任务版本失败", e); }
    }

    @Override public synchronized DeliveryRef save(ChangeTask task, String conclusion, String key,
                                                    String deliveryId, String deliveryUrl) {
        RunRef run = task.run();
        String sql = postgres ? """
                INSERT INTO scm_publications(publication_key, change_id, delivery_id, delivery_url, spec_digest,
                    head_sha, run_id, judgment_revision, approval_id, task_version, conclusion, evidence_path, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(publication_key) DO NOTHING
                """ : """
                INSERT OR IGNORE INTO gitlab_publications(publication_key, change_id, mr_iid, mr_url, spec_digest,
                    head_sha, run_id, judgment_revision, approval_id, task_version, conclusion, evidence_path, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, key); insert.setString(2, task.id().value()); insert.setString(3, deliveryId);
            insert.setString(4, deliveryUrl == null ? "" : deliveryUrl); insert.setString(5, run.specDigest());
            insert.setString(6, run.headSha()); insert.setString(7, run.runId()); insert.setLong(8, task.judgmentRevision());
            insert.setString(9, task.deliveryApproval() == null ? "" : task.deliveryApproval().id());
            insert.setLong(10, task.version()); insert.setString(11, conclusion);
            insert.setString(12, run.evidencePath().toString()); insert.setString(13, Instant.now().toString());
            insert.executeUpdate();
            return byKey(key).orElseThrow();
        } catch (SQLException e) { throw new IllegalStateException("SCM 已发布但 ledger 保存失败；重试将先远程对账", e); }
    }

    @Override public synchronized Optional<DeliveryRef> byKey(String key) {
        String table = postgres ? "scm_publications" : "gitlab_publications";
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM " + table + " WHERE publication_key=?")) {
            query.setString(1, key);
            try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(read(row)) : Optional.empty(); }
        } catch (SQLException e) { throw new IllegalStateException("读取 SCM publication ledger 失败", e); }
    }

    @Override public synchronized List<DeliveryRef> history(ChangeTaskId id) {
        String table = postgres ? "scm_publications" : "gitlab_publications";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM " + table + " WHERE change_id=? ORDER BY task_version, published_at, publication_key")) {
            query.setString(1, id.value()); List<DeliveryRef> result = new ArrayList<>();
            try (ResultSet row = query.executeQuery()) { while (row.next()) result.add(read(row)); }
            return List.copyOf(result);
        } catch (SQLException e) { throw new IllegalStateException("读取 SCM Check 历史失败", e); }
    }

    private DeliveryRef read(ResultSet row) throws SQLException {
        String id = row.getString(postgres ? "delivery_id" : "mr_iid");
        return new DeliveryRef(id, row.getString("change_id"), row.getString("spec_digest"), row.getString("head_sha"),
                row.getString("conclusion"), row.getString("evidence_path"), row.getString("run_id"),
                row.getLong("judgment_revision"), row.getString("approval_id"), row.getLong("task_version"),
                row.getString("publication_key"), row.getString("published_at"));
    }

    @Override public synchronized void close() { try { connection.close(); } catch (SQLException ignored) { } }
}
