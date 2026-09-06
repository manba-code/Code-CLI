package com.paicli.change;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** PostgreSQL adapter for the complete Change control-plane persistence interface. */
public final class PostgresChangeStore implements ChangePersistence {
    private final Connection connection;

    public PostgresChangeStore(String jdbcUrl, String user, String password) throws SQLException {
        this(DriverManager.getConnection(requireJdbcUrl(jdbcUrl), user, password));
    }

    PostgresChangeStore(Connection connection) throws SQLException {
        this.connection = java.util.Objects.requireNonNull(connection, "connection");
        PostgresStorageMigrations.migrate(connection);
    }

    @Override public synchronized List<ChangeTask> list() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT task_json::text FROM change_tasks ORDER BY created_at, id")) {
            List<ChangeTask> result = new ArrayList<>();
            while (rows.next()) result.add(decode(rows.getString(1), ChangeTask.class));
            return List.copyOf(result);
        } catch (SQLException e) { throw failure("列出 ChangeTask 失败", e); }
    }

    @Override public synchronized Optional<ChangeTask> find(ChangeTaskId id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT task_json::text FROM change_tasks WHERE id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(decode(row.getString(1), ChangeTask.class)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("读取 ChangeTask 失败", e); }
    }

    @Override public synchronized Optional<ChangeTask> findByIdempotencyKey(String idempotencyKey) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT task_json::text FROM change_tasks WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey.trim());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(decode(row.getString(1), ChangeTask.class)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("按幂等键读取 ChangeTask 失败", e); }
    }

    @Override public synchronized ChangeTask create(ChangeTask task, ChangeEvent event) {
        validateEvent(task, event);
        return transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_tasks(id, idempotency_key, version, state, task_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                    """)) {
                statement.setString(1, task.id().value());
                statement.setString(2, task.idempotencyKey());
                statement.setLong(3, task.version());
                statement.setString(4, task.state().name());
                statement.setString(5, encode(task));
                statement.setString(6, task.createdAt().toString());
                statement.setString(7, task.updatedAt().toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                if (constraint(e)) throw new ChangeConflictException("ChangeTask 或幂等键已存在");
                throw e;
            }
            appendApprovals(task);
            appendEvent(event);
            return task;
        });
    }

    @Override public synchronized ChangeTask update(long expectedVersion, ChangeTask task, ChangeEvent event) {
        validateEvent(task, event);
        if (task.version() != expectedVersion + 1) throw new IllegalArgumentException("新版本必须等于 expectedVersion + 1");
        return transaction(() -> {
            int changed;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE change_tasks SET version = ?, state = ?, task_json = ?::jsonb, updated_at = ?
                    WHERE id = ? AND version = ?
                    """)) {
                statement.setLong(1, task.version());
                statement.setString(2, task.state().name());
                statement.setString(3, encode(task));
                statement.setString(4, task.updatedAt().toString());
                statement.setString(5, task.id().value());
                statement.setLong(6, expectedVersion);
                changed = statement.executeUpdate();
            }
            if (changed == 0) {
                if (find(task.id()).isEmpty()) throw new ChangeNotFoundException(task.id());
                throw new ChangeConflictException("ChangeTask version 已过期: " + expectedVersion);
            }
            appendApprovals(task);
            appendEvent(event);
            return task;
        });
    }

    @Override public synchronized List<ChangeEvent> events(ChangeTaskId id) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, change_id, event_type, actor_type, actor_id, previous_state, new_state,
                       payload_json::text, created_at
                FROM change_events WHERE change_id = ? ORDER BY id
                """)) {
            statement.setString(1, id.value());
            List<ChangeEvent> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new ChangeEvent(rows.getLong(1),
                        new ChangeTaskId(rows.getString(2)), rows.getString(3), rows.getString(4),
                        nullable(rows.getString(5)), state(rows.getString(6)), state(rows.getString(7)),
                        rows.getString(8), Instant.parse(rows.getString(9))));
            }
            return List.copyOf(result);
        } catch (SQLException e) { throw failure("读取 Change Event 失败", e); }
    }

    @Override public synchronized ProjectToolPolicy policy(String projectId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT policy_json::text FROM project_tool_policies WHERE project_id = ?")) {
            statement.setString(1, projectId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? decode(row.getString(1), ProjectToolPolicy.class)
                        : ProjectToolPolicy.defaults(projectId, Instant.EPOCH);
            }
        } catch (SQLException e) { throw failure("读取项目工具策略失败", e); }
    }

    @Override public synchronized ProjectToolPolicy updatePolicy(String projectId, long expectedVersion,
                                                                  List<ProjectToolPolicy.Rule> rules, Instant now,
                                                                  String actorId, String actorType) {
        ProjectToolPolicy current = policy(projectId);
        if (current.version() != expectedVersion) throw new ChangeConflictException("工具策略版本已过期");
        ProjectToolPolicy updated = new ProjectToolPolicy(projectId, expectedVersion + 1, rules, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_tool_policies(project_id, version, policy_json, updated_at, actor_id, actor_type)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET version=excluded.version, policy_json=excluded.policy_json,
                    updated_at=excluded.updated_at, actor_id=excluded.actor_id, actor_type=excluded.actor_type
                WHERE project_tool_policies.version = ?
                """)) {
            statement.setString(1, projectId); statement.setLong(2, updated.version());
            statement.setString(3, encode(updated)); statement.setString(4, now.toString());
            statement.setString(5, actorId); statement.setString(6, actorType); statement.setLong(7, expectedVersion);
            if (statement.executeUpdate() == 0) throw new ChangeConflictException("工具策略版本已过期");
            return updated;
        } catch (SQLException e) { throw failure("保存项目工具策略失败", e); }
    }

    @Override public synchronized ToolApproval createApproval(ToolApproval approval) {
        return saveToolApproval(approval, "tool.approval_requested", "WORKER", "change-worker", null);
    }

    @Override public synchronized ToolApproval recordDeniedCall(ToolApproval denial) {
        if (denial.status() != ToolApproval.Status.REJECTED) throw new IllegalArgumentException("必须记录拒绝结果");
        return saveToolApproval(denial, "tool.policy_denied", "SYSTEM", "tool-policy", denial.decidedAt());
    }

    private ToolApproval saveToolApproval(ToolApproval approval, String eventType, String actorType,
                                          String actorId, Instant decidedAt) {
        return transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tool_approvals(id, change_id, status, approval_json, created_at, decided_at)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?)
                    """)) {
                statement.setString(1, approval.id()); statement.setString(2, approval.changeId().value());
                statement.setString(3, approval.status().name()); statement.setString(4, encode(approval));
                statement.setString(5, approval.createdAt().toString());
                statement.setString(6, decidedAt == null ? null : decidedAt.toString());
                statement.executeUpdate();
            }
            appendEvent(toolEvent(approval, eventType, actorType, actorId));
            return approval;
        });
    }

    @Override public synchronized Optional<ToolApproval> findApproval(String approvalId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT approval_json::text FROM tool_approvals WHERE id = ?")) {
            statement.setString(1, approvalId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(decode(row.getString(1), ToolApproval.class)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("读取工具审批失败", e); }
    }

    @Override public synchronized List<ToolApproval> approvals(ChangeTaskId changeId) {
        return approvalQuery("SELECT approval_json::text FROM tool_approvals WHERE change_id = ? ORDER BY created_at, id",
                changeId.value());
    }

    @Override public synchronized ToolApproval updateApproval(ToolApproval approval, ToolApproval.Status expectedStatus) {
        return transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_approvals SET status=?, approval_json=?::jsonb, decided_at=? WHERE id=? AND status=?
                    """)) {
                statement.setString(1, approval.status().name()); statement.setString(2, encode(approval));
                statement.setString(3, approval.decidedAt() == null ? null : approval.decidedAt().toString());
                statement.setString(4, approval.id()); statement.setString(5, expectedStatus.name());
                if (statement.executeUpdate() == 0) throw new ChangeConflictException("工具审批状态已变化");
            }
            appendEvent(toolEvent(approval, "tool.approval_" + approval.status().name().toLowerCase(Locale.ROOT),
                    approval.approverType().isBlank() ? "SYSTEM" : approval.approverType(),
                    approval.approverId().isBlank() ? "change-worker" : approval.approverId()));
            return approval;
        });
    }

    @Override public synchronized List<ToolApproval> pendingApprovals() {
        return approvalQuery("SELECT approval_json::text FROM tool_approvals WHERE status = 'PENDING' ORDER BY created_at, id", null);
    }

    private List<ToolApproval> approvalQuery(String sql, String changeId) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (changeId != null) statement.setString(1, changeId);
            List<ToolApproval> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(decode(rows.getString(1), ToolApproval.class));
            }
            return List.copyOf(result);
        } catch (SQLException e) { throw failure("列出工具审批失败", e); }
    }

    private ChangeEvent toolEvent(ToolApproval approval, String type, String actorType, String actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state FROM change_tasks WHERE id = ?")) {
            statement.setString(1, approval.changeId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ChangeNotFoundException(approval.changeId());
                ChangeState current = ChangeState.valueOf(row.getString(1));
                var payload = ChangeJson.MAPPER.createObjectNode();
                payload.put("approvalId", approval.id()).put("callId", approval.callId())
                        .put("runId", approval.runId()).put("toolName", approval.toolName())
                        .put("argumentsDigest", approval.argumentsDigest()).put("argumentsPreview", approval.argumentsPreview())
                        .put("workingDirectory", approval.workingDirectory()).put("specDigest", approval.specDigest())
                        .put("policyVersion", approval.policyVersion()).put("profile", approval.profile().name())
                        .put("ruleId", approval.ruleId()).put("status", approval.status().name())
                        .put("reason", approval.decisionReason());
                return new ChangeEvent(0, approval.changeId(), type, actorType, actorId, current, current,
                        payload.toString(), approval.decidedAt() == null ? approval.createdAt() : approval.decidedAt());
            }
        }
    }

    private void appendApprovals(ChangeTask task) throws SQLException {
        appendApproval(task.id(), task.specApproval());
        appendApproval(task.id(), task.deliveryApproval());
    }

    private void appendApproval(ChangeTaskId changeId, ApprovalRecord approval) throws SQLException {
        if (approval == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO change_approvals(id, change_id, stage, approval_json, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT(id) DO NOTHING
                """)) {
            statement.setString(1, approval.id()); statement.setString(2, changeId.value());
            statement.setString(3, approval.stage().name()); statement.setString(4, encode(approval));
            statement.setString(5, approval.createdAt().toString()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT change_id, stage, approval_json::text FROM change_approvals WHERE id = ?
                """)) {
            statement.setString(1, approval.id());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()
                        || !row.getString(1).equals(changeId.value())
                        || !row.getString(2).equals(approval.stage().name())
                        || !decode(row.getString(3), ApprovalRecord.class).equals(approval)) {
                    throw new ChangeConflictException("同一审批 ID 已有不同内容: " + approval.id());
                }
            }
        }
    }

    private void appendEvent(ChangeEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO change_events(change_id, event_type, actor_type, actor_id, previous_state,
                                          new_state, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """)) {
            statement.setString(1, event.changeId().value()); statement.setString(2, event.type());
            statement.setString(3, event.actorType()); statement.setString(4, event.actorId());
            statement.setString(5, event.previousState() == null ? null : event.previousState().name());
            statement.setString(6, event.newState() == null ? null : event.newState().name());
            statement.setString(7, event.payloadJson()); statement.setString(8, event.createdAt().toString());
            statement.executeUpdate();
        }
    }

    @Override public String backend() { return "postgresql"; }
    @Override public synchronized int schemaVersion() {
        try { return PostgresStorageMigrations.version(connection); }
        catch (SQLException e) { throw failure("读取 PostgreSQL migration 版本失败", e); }
    }
    @Override public synchronized void checkHealth() {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM change_tasks")) {
            if (!row.next() || schemaVersion() != PostgresStorageMigrations.CURRENT_VERSION)
                throw new SQLException("unexpected schema probe result");
        } catch (SQLException e) { throw failure("PostgreSQL 健康检查失败", e); }
    }
    @Override public synchronized ChangeTaskMetrics metrics() {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT COUNT(*),
                       COUNT(*) FILTER (WHERE state NOT IN ('COMPLETED','FAILED','REJECTED','CANCELED')),
                       COUNT(*) FILTER (WHERE state='FAILED'),
                       COUNT(*) FILTER (WHERE state='COMPLETED'),
                       COUNT(*) FILTER (WHERE state='DELIVERY_REVIEW'),
                       (SELECT COUNT(*) FROM change_events WHERE event_type='dispatch.failed')
                     FROM change_tasks
                     """)) {
            if (!row.next()) throw new SQLException("unexpected task metrics result");
            return new ChangeTaskMetrics(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4),
                    row.getLong(5), row.getLong(6));
        } catch (SQLException e) { throw failure("读取 PostgreSQL PaiChange 指标失败", e); }
    }
    @Override public synchronized void close() { try { connection.close(); } catch (SQLException ignored) { } }

    /** Idempotent administrative import used only by the offline SQLite-to-M6b migration tool. */
    synchronized void importSnapshot(ChangeTask task, List<ChangeEvent> events,
                                     List<ProjectToolPolicy> policies, List<ToolApproval> approvals) {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_tasks(id, idempotency_key, version, state, task_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT(id) DO NOTHING
                    """)) {
                statement.setString(1, task.id().value()); statement.setString(2, task.idempotencyKey());
                statement.setLong(3, task.version()); statement.setString(4, task.state().name());
                statement.setString(5, encode(task)); statement.setString(6, task.createdAt().toString());
                statement.setString(7, task.updatedAt().toString()); statement.executeUpdate();
            }
            ChangeTask saved = find(task.id()).orElseThrow();
            if (!encode(saved).equals(encode(task))) throw new ChangeConflictException("目标 PostgreSQL 已有不同 ChangeTask: " + task.id());
            for (ChangeEvent event : events) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO change_events(id, change_id, event_type, actor_type, actor_id, previous_state,
                            new_state, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT(id) DO NOTHING
                        """)) {
                    statement.setLong(1, event.sequence()); statement.setString(2, event.changeId().value());
                    statement.setString(3, event.type()); statement.setString(4, event.actorType());
                    statement.setString(5, event.actorId());
                    statement.setString(6, event.previousState() == null ? null : event.previousState().name());
                    statement.setString(7, event.newState() == null ? null : event.newState().name());
                    statement.setString(8, event.payloadJson()); statement.setString(9, event.createdAt().toString());
                    statement.executeUpdate();
                }
                requireImportedEvent(event);
            }
            for (ProjectToolPolicy policy : policies) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO project_tool_policies(project_id, version, policy_json, updated_at, actor_id, actor_type)
                        VALUES (?, ?, ?::jsonb, ?, 'sqlite-migration', 'SYSTEM') ON CONFLICT(project_id) DO NOTHING
                        """)) {
                    statement.setString(1, policy.projectId()); statement.setLong(2, policy.version());
                    statement.setString(3, encode(policy)); statement.setString(4, policy.updatedAt().toString());
                    statement.executeUpdate();
                }
                if (!this.policy(policy.projectId()).equals(policy))
                    throw new ChangeConflictException("目标 PostgreSQL 已有不同项目工具策略: " + policy.projectId());
            }
            appendApprovals(task);
            for (ToolApproval approval : approvals) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tool_approvals(id, change_id, status, approval_json, created_at, decided_at)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT(id) DO NOTHING
                        """)) {
                    statement.setString(1, approval.id()); statement.setString(2, approval.changeId().value());
                    statement.setString(3, approval.status().name()); statement.setString(4, encode(approval));
                    statement.setString(5, approval.createdAt().toString());
                    statement.setString(6, approval.decidedAt() == null ? null : approval.decidedAt().toString());
                    statement.executeUpdate();
                }
                if (!findApproval(approval.id()).orElseThrow().equals(approval))
                    throw new ChangeConflictException("目标 PostgreSQL 已有不同工具审批: " + approval.id());
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT setval(pg_get_serial_sequence('change_events','id'), "
                        + "COALESCE((SELECT MAX(id) FROM change_events), 1), true)");
            }
            return null;
        });
    }

    private void requireImportedEvent(ChangeEvent expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT change_id, event_type, actor_type, actor_id, previous_state, new_state,
                       payload_json::text, created_at FROM change_events WHERE id=?
                """)) {
            statement.setLong(1, expected.sequence());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()
                        || !row.getString(1).equals(expected.changeId().value())
                        || !row.getString(2).equals(expected.type())
                        || !row.getString(3).equals(expected.actorType())
                        || !nullable(row.getString(4)).equals(expected.actorId())
                        || !java.util.Objects.equals(row.getString(5), expected.previousState() == null ? null : expected.previousState().name())
                        || !java.util.Objects.equals(row.getString(6), expected.newState() == null ? null : expected.newState().name())
                        || !jsonTree(row.getString(7)).equals(jsonTree(expected.payloadJson()))
                        || !row.getString(8).equals(expected.createdAt().toString())) {
                    throw new ChangeConflictException("目标 PostgreSQL event sequence 已有不同内容: " + expected.sequence());
                }
            }
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode jsonTree(String value) throws SQLException {
        try { return ChangeJson.MAPPER.readTree(value); }
        catch (JsonProcessingException e) { throw new SQLException("PostgreSQL event JSON 解码失败", e); }
    }

    private <T> T transaction(SqlOperation<T> operation) {
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = operation.run(); connection.commit(); return result;
            } catch (RuntimeException | SQLException e) {
                connection.rollback(); throw e;
            } finally { connection.setAutoCommit(autoCommit); }
        } catch (SQLException e) { throw failure("PostgreSQL ChangeTask 事务失败", e); }
    }

    private static void validateEvent(ChangeTask task, ChangeEvent event) {
        if (event == null) throw new NullPointerException("event");
        if (!task.id().equals(event.changeId()) || task.state() != event.newState())
            throw new IllegalArgumentException("Change Event 必须描述同一 ChangeTask 的新状态");
    }
    private static String encode(Object value) throws SQLException {
        try { return ChangeJson.MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new SQLException("PostgreSQL JSON 编码失败", e); }
    }
    private static <T> T decode(String value, Class<T> type) throws SQLException {
        try { return ChangeJson.MAPPER.readValue(value, type); }
        catch (JsonProcessingException e) { throw new SQLException("PostgreSQL JSON 解码失败: " + type.getSimpleName(), e); }
    }
    private static boolean constraint(SQLException e) { return e.getSQLState() != null && e.getSQLState().startsWith("23"); }
    private static ChangeState state(String value) { return value == null ? null : ChangeState.valueOf(value); }
    private static String nullable(String value) { return value == null ? "" : value; }
    private static String requireJdbcUrl(String value) {
        if (value == null || !value.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PAICHANGE_POSTGRES_URL 必须是 jdbc:postgresql:// URL");
        return value;
    }
    private static IllegalStateException failure(String message, SQLException e) {
        return new IllegalStateException(message + ": " + e.getMessage(), e);
    }
    @FunctionalInterface private interface SqlOperation<T> { T run() throws SQLException; }
}
