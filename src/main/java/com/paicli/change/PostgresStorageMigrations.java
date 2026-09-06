package com.paicli.change;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Versioned, forward-only PostgreSQL schema migrations used by every M6b adapter. */
public final class PostgresStorageMigrations {
    public static final int CURRENT_VERSION = 2;

    private static final List<String> V1 = List.of(
            """
            CREATE TABLE IF NOT EXISTS change_tasks (
                id TEXT PRIMARY KEY,
                idempotency_key TEXT NOT NULL UNIQUE,
                version BIGINT NOT NULL,
                state TEXT NOT NULL,
                task_json JSONB NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_change_tasks_created ON change_tasks(created_at, id)",
            """
            CREATE TABLE IF NOT EXISTS change_events (
                id BIGSERIAL PRIMARY KEY,
                change_id TEXT NOT NULL REFERENCES change_tasks(id),
                event_type TEXT NOT NULL,
                actor_type TEXT NOT NULL,
                actor_id TEXT,
                previous_state TEXT,
                new_state TEXT,
                payload_json JSONB NOT NULL,
                created_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_change_events_change ON change_events(change_id, id)",
            """
            CREATE TABLE IF NOT EXISTS change_approvals (
                id TEXT PRIMARY KEY,
                change_id TEXT NOT NULL REFERENCES change_tasks(id),
                stage TEXT NOT NULL,
                approval_json JSONB NOT NULL,
                created_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS project_tool_policies (
                project_id TEXT PRIMARY KEY,
                version BIGINT NOT NULL,
                policy_json JSONB NOT NULL,
                updated_at TEXT NOT NULL,
                actor_id TEXT NOT NULL,
                actor_type TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS tool_approvals (
                id TEXT PRIMARY KEY,
                change_id TEXT NOT NULL REFERENCES change_tasks(id),
                status TEXT NOT NULL,
                approval_json JSONB NOT NULL,
                created_at TEXT NOT NULL,
                decided_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_tool_approvals_change ON tool_approvals(change_id, created_at, id)",
            "CREATE INDEX IF NOT EXISTS idx_tool_approvals_pending ON tool_approvals(status, created_at, id)",
            """
            CREATE TABLE IF NOT EXISTS worker_jobs (
                id TEXT PRIMARY KEY,
                job_type TEXT NOT NULL,
                reference_id TEXT NOT NULL,
                status TEXT NOT NULL,
                recovery_count INTEGER NOT NULL DEFAULT 0,
                lease_owner TEXT,
                lease_expires_at TEXT,
                result TEXT,
                error TEXT,
                created_at TEXT NOT NULL,
                started_at TEXT,
                finished_at TEXT,
                updated_at TEXT NOT NULL
            )
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_worker_jobs_active_reference
            ON worker_jobs(job_type, reference_id)
            WHERE status IN ('ENQUEUED', 'RUNNING')
            """,
            "CREATE INDEX IF NOT EXISTS idx_worker_jobs_claim ON worker_jobs(status, created_at)",
            """
            CREATE TABLE IF NOT EXISTS evidence_archives (
                change_id TEXT NOT NULL REFERENCES change_tasks(id),
                run_id TEXT NOT NULL,
                object_prefix TEXT NOT NULL,
                manifest_sha256 TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY(change_id, run_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS evidence_objects (
                change_id TEXT NOT NULL,
                run_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                size_bytes BIGINT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY(change_id, run_id, relative_path),
                FOREIGN KEY(change_id, run_id) REFERENCES evidence_archives(change_id, run_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS scm_publications (
                publication_key TEXT PRIMARY KEY,
                change_id TEXT NOT NULL REFERENCES change_tasks(id),
                delivery_id TEXT NOT NULL,
                delivery_url TEXT NOT NULL,
                spec_digest TEXT NOT NULL,
                head_sha TEXT NOT NULL,
                run_id TEXT NOT NULL,
                judgment_revision BIGINT NOT NULL,
                approval_id TEXT NOT NULL,
                task_version BIGINT NOT NULL,
                conclusion TEXT NOT NULL CHECK (conclusion IN ('success', 'failure', 'pending')),
                evidence_path TEXT NOT NULL,
                published_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_scm_publications_change ON scm_publications(change_id, task_version, published_at)"
    );

    private static final List<String> V2 = List.of(
            """
            CREATE TABLE IF NOT EXISTS project_members (
                project_id TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                principal_type TEXT NOT NULL CHECK (principal_type IN ('HUMAN', 'SERVICE')),
                roles JSONB NOT NULL CHECK (jsonb_typeof(roles) = 'array'),
                version BIGINT NOT NULL CHECK (version > 0),
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                created_by_subject TEXT NOT NULL,
                created_by_type TEXT NOT NULL CHECK (created_by_type IN ('HUMAN', 'SERVICE')),
                updated_by_subject TEXT NOT NULL,
                updated_by_type TEXT NOT NULL CHECK (updated_by_type IN ('HUMAN', 'SERVICE')),
                PRIMARY KEY(project_id, subject_id)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_project_members_subject ON project_members(subject_id, project_id)",
            """
            CREATE TABLE IF NOT EXISTS project_member_audit (
                id BIGSERIAL PRIMARY KEY,
                project_id TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                operation TEXT NOT NULL CHECK (operation IN ('BOOTSTRAP', 'ADD', 'UPDATE', 'REMOVE')),
                previous_version BIGINT,
                new_version BIGINT,
                before_principal_type TEXT,
                after_principal_type TEXT,
                before_roles JSONB,
                after_roles JSONB,
                actor_subject TEXT NOT NULL,
                actor_type TEXT NOT NULL CHECK (actor_type IN ('HUMAN', 'SERVICE')),
                occurred_at TIMESTAMPTZ NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_project_member_audit_project ON project_member_audit(project_id, id)"
    );

    private PostgresStorageMigrations() { }

    public static void migrate(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS paichange_schema_migrations (
                            version INTEGER PRIMARY KEY,
                            description TEXT NOT NULL,
                            checksum TEXT NOT NULL,
                            installed_at TEXT NOT NULL
                        )
                        """);
                statement.execute("LOCK TABLE paichange_schema_migrations IN EXCLUSIVE MODE");
            }
            rejectFutureVersion(connection);
            apply(connection, 1, "M6b PostgreSQL control plane, queue, evidence metadata and publication ledger", V1);
            apply(connection, 2, "M7a persistent project membership directory and audit history", V2);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void apply(Connection connection, int version, String description,
                              List<String> statements) throws SQLException {
        String expected = checksum(statements);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT checksum FROM paichange_schema_migrations WHERE version = ?")) {
            query.setInt(1, version);
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    if (!expected.equals(row.getString(1))) {
                        throw new SQLException("PostgreSQL migration V" + version + " checksum mismatch");
                    }
                    return;
                }
            }
        }
        for (String sql : statements) {
            try (Statement statement = connection.createStatement()) { statement.execute(sql); }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO paichange_schema_migrations(version, description, checksum, installed_at)
                VALUES (?, ?, ?, ?)
                """)) {
            insert.setInt(1, version);
            insert.setString(2, description);
            insert.setString(3, expected);
            insert.setString(4, Instant.now().toString());
            insert.executeUpdate();
        }
    }

    public static int version(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM paichange_schema_migrations")) {
            return row.next() ? row.getInt(1) : 0;
        }
    }

    private static void rejectFutureVersion(Connection connection) throws SQLException {
        int version = version(connection);
        if (version > CURRENT_VERSION) {
            throw new SQLException("数据库 schema 版本 " + version + " 高于当前程序支持的 " + CURRENT_VERSION);
        }
    }

    private static String checksum(List<String> statements) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String statement : statements) {
                digest.update(statement.strip().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 migration checksum", e);
        }
    }
}
