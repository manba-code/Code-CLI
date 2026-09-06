package com.paicli.change;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
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

public final class SqliteChangeStore implements ChangePersistence {
    private static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper JSON = ChangeJson.MAPPER;
    private final Connection connection;

    public SqliteChangeStore(Path dbPath) throws SQLException {
        Path normalized = dbPath.toAbsolutePath().normalize();
        try {
            if (normalized.getParent() != null) {
                Files.createDirectories(normalized.getParent());
            }
        } catch (Exception e) {
            throw new SQLException("无法创建 ChangeTask 数据库目录: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
        initTables();
    }

    @Override
    public synchronized List<ChangeTask> list() {
        List<ChangeTask> tasks = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM change_tasks ORDER BY created_at, id")) {
            while (rows.next()) tasks.add(fromRow(rows));
            return List.copyOf(tasks);
        } catch (SQLException e) {
            throw persistenceFailure("列出 ChangeTask 失败", e);
        }
    }

    @Override
    public synchronized Optional<ChangeTask> find(ChangeTaskId id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM change_tasks WHERE id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(fromRow(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw persistenceFailure("读取 ChangeTask 失败", e);
        }
    }

    @Override
    public synchronized Optional<ChangeTask> findByIdempotencyKey(String idempotencyKey) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM change_tasks WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(fromRow(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw persistenceFailure("按幂等键读取 ChangeTask 失败", e);
        }
    }

    @Override
    public synchronized ChangeTask create(ChangeTask task, ChangeEvent event) {
        validateEvent(task, event);
        return inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_tasks (
                        id, idempotency_key, version, state,
                        source_type, source_external_id, source_url, source_labels_json, source_priority,
                        repository, base_ref, title, requirement, requester_id,
                        project_context, referenced_context,
                        spec_id, spec_revision, spec_digest, spec_draft_path, spec_locked_path,
                        risk_level, risk_score, risk_reasons_json, route_json,
                        approval_id, approval_decision, approver_id, approval_reason, approval_created_at,
                        delivery_approval_id, delivery_approval_decision, delivery_approver_id,
                        delivery_approval_reason, delivery_approval_spec_digest,
                        delivery_approval_head_sha, delivery_approval_created_at,
                        active_claim_id, active_claimed_at,
                        run_id, run_spec_digest, run_verdict, run_status,
                        run_workspace_id, run_branch, run_head_sha, run_evidence_path, run_completed_at,
                        created_at, updated_at, draft_job_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bindTask(statement, task);
                statement.executeUpdate();
            } catch (SQLException e) {
                if (isConstraintViolation(e)) {
                    throw new ChangeConflictException("ChangeTask 或幂等键已存在");
                }
                throw e;
            }
            saveReview(task);
            appendApprovals(task);
            appendEvent(event);
            return task;
        });
    }

    @Override
    public synchronized ChangeTask update(long expectedVersion, ChangeTask task, ChangeEvent event) {
        validateEvent(task, event);
        if (task.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("新版本必须等于 expectedVersion + 1");
        }
        return inTransaction(() -> {
            int changed;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE change_tasks SET
                        version = ?, state = ?, source_type = ?, source_external_id = ?, source_url = ?,
                        source_labels_json = ?, source_priority = ?,
                        repository = ?, base_ref = ?, title = ?, requirement = ?, requester_id = ?,
                        project_context = ?, referenced_context = ?,
                        spec_id = ?, spec_revision = ?, spec_digest = ?,
                        spec_draft_path = ?, spec_locked_path = ?,
                        risk_level = ?, risk_score = ?, risk_reasons_json = ?, route_json = ?,
                        approval_id = ?, approval_decision = ?, approver_id = ?,
                        approval_reason = ?, approval_created_at = ?,
                        delivery_approval_id = ?, delivery_approval_decision = ?, delivery_approver_id = ?,
                        delivery_approval_reason = ?, delivery_approval_spec_digest = ?,
                        delivery_approval_head_sha = ?, delivery_approval_created_at = ?,
                        active_claim_id = ?, active_claimed_at = ?,
                        run_id = ?, run_spec_digest = ?, run_verdict = ?, run_status = ?,
                        run_workspace_id = ?, run_branch = ?, run_head_sha = ?,
                        run_evidence_path = ?, run_completed_at = ?, updated_at = ?, draft_job_json = ?
                    WHERE id = ? AND version = ?
                    """)) {
                bindMutableTask(statement, task);
                statement.setString(49, task.id().value());
                statement.setLong(50, expectedVersion);
                changed = statement.executeUpdate();
            }
            if (changed == 0) {
                if (find(task.id()).isEmpty()) {
                    throw new ChangeNotFoundException(task.id());
                }
                throw new ChangeConflictException("ChangeTask version 已过期: " + expectedVersion);
            }
            saveReview(task);
            appendApprovals(task);
            appendEvent(event);
            return task;
        });
    }

    @Override
    public synchronized List<ChangeEvent> events(ChangeTaskId id) {
        List<ChangeEvent> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, change_id, event_type, actor_type, actor_id,
                       previous_state, new_state, payload_json, created_at
                FROM change_events WHERE change_id = ? ORDER BY id ASC
                """)) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new ChangeEvent(
                            rows.getLong("id"),
                            new ChangeTaskId(rows.getString("change_id")),
                            rows.getString("event_type"),
                            rows.getString("actor_type"),
                            nullable(rows.getString("actor_id")),
                            state(rows.getString("previous_state")),
                            state(rows.getString("new_state")),
                            rows.getString("payload_json"),
                            Instant.parse(rows.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw persistenceFailure("读取 Change Event 失败", e);
        }
        return List.copyOf(result);
    }

    private void initTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS paichange_schema_migrations (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        installed_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS change_tasks (
                        id TEXT PRIMARY KEY,
                        idempotency_key TEXT NOT NULL UNIQUE,
                        version INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        source_type TEXT,
                        source_external_id TEXT,
                        source_url TEXT,
                        source_labels_json TEXT NOT NULL DEFAULT '[]',
                        source_priority TEXT NOT NULL DEFAULT '',
                        repository TEXT NOT NULL,
                        base_ref TEXT NOT NULL,
                        title TEXT NOT NULL,
                        requirement TEXT NOT NULL,
                        requester_id TEXT NOT NULL DEFAULT 'unknown',
                        project_context TEXT NOT NULL,
                        referenced_context TEXT NOT NULL,
                        spec_id TEXT,
                        spec_revision INTEGER,
                        spec_digest TEXT,
                        spec_draft_path TEXT,
                        spec_locked_path TEXT,
                        risk_level TEXT,
                        risk_score INTEGER,
                        risk_reasons_json TEXT,
                        route_json TEXT,
                        approval_id TEXT,
                        approval_decision TEXT,
                        approver_id TEXT,
                        approval_reason TEXT,
                        approval_created_at TEXT,
                        delivery_approval_id TEXT,
                        delivery_approval_decision TEXT,
                        delivery_approver_id TEXT,
                        delivery_approval_reason TEXT,
                        delivery_approval_spec_digest TEXT,
                        delivery_approval_head_sha TEXT,
                        delivery_approval_created_at TEXT,
                        active_claim_id TEXT,
                        active_claimed_at TEXT,
                        run_id TEXT,
                        run_spec_digest TEXT,
                        run_verdict TEXT,
                        run_status TEXT,
                        run_workspace_id TEXT,
                        run_branch TEXT,
                        run_head_sha TEXT,
                        run_evidence_path TEXT,
                        run_completed_at TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS change_approvals (
                        id TEXT PRIMARY KEY,
                        change_id TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        approver_id TEXT NOT NULL,
                        reason TEXT,
                        spec_digest TEXT,
                        head_sha TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(change_id) REFERENCES change_tasks(id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS change_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        change_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        actor_type TEXT NOT NULL,
                        actor_id TEXT,
                        previous_state TEXT,
                        new_state TEXT,
                        payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(change_id) REFERENCES change_tasks(id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_change_events_change ON change_events(change_id, id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS project_tool_policies (
                        project_id TEXT PRIMARY KEY,
                        version INTEGER NOT NULL,
                        policy_json TEXT NOT NULL CHECK (json_valid(policy_json)),
                        updated_at TEXT NOT NULL,
                        actor_id TEXT NOT NULL,
                        actor_type TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tool_approvals (
                        id TEXT PRIMARY KEY,
                        change_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        approval_json TEXT NOT NULL CHECK (json_valid(approval_json)),
                        created_at TEXT NOT NULL,
                        decided_at TEXT,
                        FOREIGN KEY(change_id) REFERENCES change_tasks(id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_tool_approvals_change ON tool_approvals(change_id, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_tool_approvals_pending ON tool_approvals(status, created_at)");
        }
        ensureExecutionColumns();
        ensurePhaseFourColumns();
        ensureColumn("draft_job_json", "TEXT");
        ensureColumn("change_approvals", "run_id", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("change_approvals", "judgment_revision", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("human_review_json", "TEXT");
        ensureColumn("delivery_binding_json", "TEXT");
        recordSchemaVersion();
    }

    private void recordSchemaVersion() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO paichange_schema_migrations(version, description, checksum, installed_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.setString(2, "M6b versioned SQLite control-plane schema");
            statement.setString(3, "sqlite-control-plane-v1");
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    @Override public String backend() { return "sqlite"; }

    @Override public int schemaVersion() {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM paichange_schema_migrations")) {
            return row.next() ? row.getInt(1) : 0;
        } catch (SQLException e) {
            throw persistenceFailure("读取存储迁移版本失败", e);
        }
    }

    @Override public void checkHealth() {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT 1")) {
            if (!row.next() || row.getInt(1) != 1) throw new SQLException("unexpected probe result");
        } catch (SQLException e) {
            throw persistenceFailure("SQLite 健康检查失败", e);
        }
    }

    @Override
    public synchronized ProjectToolPolicy policy(String projectId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT policy_json FROM project_tool_policies WHERE project_id = ?")) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? fromJson(rows.getString(1), ProjectToolPolicy.class)
                        : ProjectToolPolicy.defaults(projectId, Instant.EPOCH);
            }
        } catch (SQLException e) {
            throw persistenceFailure("读取项目工具策略失败", e);
        }
    }

    @Override
    public synchronized ProjectToolPolicy updatePolicy(String projectId, long expectedVersion,
                                                        List<ProjectToolPolicy.Rule> rules, Instant now,
                                                        String actorId, String actorType) {
        ProjectToolPolicy current = policy(projectId);
        if (current.version() != expectedVersion) throw new ChangeConflictException("工具策略版本已过期");
        ProjectToolPolicy updated = new ProjectToolPolicy(projectId, expectedVersion + 1, rules, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_tool_policies(project_id, version, policy_json, updated_at, actor_id, actor_type)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET version=excluded.version, policy_json=excluded.policy_json,
                    updated_at=excluded.updated_at, actor_id=excluded.actor_id, actor_type=excluded.actor_type
                WHERE project_tool_policies.version = ?
                """)) {
            statement.setString(1, projectId);
            statement.setLong(2, updated.version());
            statement.setString(3, json(updated));
            statement.setString(4, now.toString());
            statement.setString(5, actorId);
            statement.setString(6, actorType);
            statement.setLong(7, expectedVersion);
            if (statement.executeUpdate() == 0) throw new ChangeConflictException("工具策略版本已过期");
            return updated;
        } catch (SQLException e) {
            throw persistenceFailure("保存项目工具策略失败", e);
        }
    }

    @Override
    public synchronized ToolApproval createApproval(ToolApproval approval) {
        return inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tool_approvals(id, change_id, status, approval_json, created_at, decided_at)
                    VALUES (?, ?, ?, ?, ?, NULL)
                    """)) {
                statement.setString(1, approval.id());
                statement.setString(2, approval.changeId().value());
                statement.setString(3, approval.status().name());
                statement.setString(4, json(approval));
                statement.setString(5, approval.createdAt().toString());
                statement.executeUpdate();
            }
            appendEvent(toolEvent(approval, "tool.approval_requested", "WORKER", "change-worker"));
            return approval;
        });
    }

    @Override
    public synchronized ToolApproval recordDeniedCall(ToolApproval denial) {
        if (denial.status() != ToolApproval.Status.REJECTED) throw new IllegalArgumentException("必须记录拒绝结果");
        return inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tool_approvals(id, change_id, status, approval_json, created_at, decided_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, denial.id());
                statement.setString(2, denial.changeId().value());
                statement.setString(3, denial.status().name());
                statement.setString(4, json(denial));
                statement.setString(5, denial.createdAt().toString());
                statement.setString(6, denial.decidedAt() == null ? null : denial.decidedAt().toString());
                statement.executeUpdate();
            }
            appendEvent(toolEvent(denial, "tool.policy_denied", "SYSTEM", "tool-policy"));
            return denial;
        });
    }

    @Override
    public synchronized Optional<ToolApproval> findApproval(String approvalId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT approval_json FROM tool_approvals WHERE id = ?")) {
            statement.setString(1, approvalId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(fromJson(rows.getString(1), ToolApproval.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw persistenceFailure("读取工具审批失败", e);
        }
    }

    @Override
    public synchronized List<ToolApproval> approvals(ChangeTaskId changeId) {
        List<ToolApproval> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT approval_json FROM tool_approvals WHERE change_id = ? ORDER BY created_at, id")) {
            statement.setString(1, changeId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(fromJson(rows.getString(1), ToolApproval.class));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw persistenceFailure("列出工具审批失败", e);
        }
    }

    @Override
    public synchronized ToolApproval updateApproval(ToolApproval approval, ToolApproval.Status expectedStatus) {
        return inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_approvals SET status = ?, approval_json = ?, decided_at = ?
                    WHERE id = ? AND status = ?
                    """)) {
                statement.setString(1, approval.status().name());
                statement.setString(2, json(approval));
                statement.setString(3, approval.decidedAt() == null ? null : approval.decidedAt().toString());
                statement.setString(4, approval.id());
                statement.setString(5, expectedStatus.name());
                if (statement.executeUpdate() == 0) throw new ChangeConflictException("工具审批状态已变化");
            }
            appendEvent(toolEvent(approval, "tool.approval_" + approval.status().name().toLowerCase(java.util.Locale.ROOT),
                    approval.approverType().isBlank() ? "SYSTEM" : approval.approverType(),
                    approval.approverId().isBlank() ? "change-worker" : approval.approverId()));
            return approval;
        });
    }

    @Override
    public synchronized List<ToolApproval> pendingApprovals() {
        List<ToolApproval> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT approval_json FROM tool_approvals WHERE status = 'PENDING' ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(fromJson(rows.getString(1), ToolApproval.class));
            return List.copyOf(result);
        } catch (SQLException e) {
            throw persistenceFailure("列出未决工具审批失败", e);
        }
    }

    private ChangeEvent toolEvent(ToolApproval approval, String type, String actorType, String actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state FROM change_tasks WHERE id = ?")) {
            statement.setString(1, approval.changeId().value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new ChangeNotFoundException(approval.changeId());
                ChangeState state = ChangeState.valueOf(rows.getString(1));
                var payload = ChangeJson.MAPPER.createObjectNode();
                payload.put("approvalId", approval.id()).put("callId", approval.callId())
                        .put("runId", approval.runId()).put("toolName", approval.toolName())
                        .put("argumentsDigest", approval.argumentsDigest())
                        .put("argumentsPreview", approval.argumentsPreview())
                        .put("workingDirectory", approval.workingDirectory())
                        .put("specDigest", approval.specDigest()).put("policyVersion", approval.policyVersion())
                        .put("profile", approval.profile().name()).put("ruleId", approval.ruleId())
                        .put("status", approval.status().name()).put("reason", approval.decisionReason());
                return new ChangeEvent(0, approval.changeId(), type, actorType, actorId,
                        state, state, payload.toString(), approval.decidedAt() == null ? approval.createdAt() : approval.decidedAt());
            }
        }
    }

    private static void bindTask(PreparedStatement statement, ChangeTask task) throws SQLException {
        statement.setString(1, task.id().value());
        statement.setString(2, task.idempotencyKey());
        statement.setLong(3, task.version());
        statement.setString(4, task.state().name());
        bindTaskBody(statement, task, 5);
        statement.setString(49, task.createdAt().toString());
        statement.setString(50, task.updatedAt().toString());
        statement.setString(51, task.draftJob() == null ? null : json(task.draftJob()));
    }

    private static void bindMutableTask(PreparedStatement statement, ChangeTask task) throws SQLException {
        statement.setLong(1, task.version());
        statement.setString(2, task.state().name());
        bindTaskBody(statement, task, 3);
        statement.setString(47, task.updatedAt().toString());
        statement.setString(48, task.draftJob() == null ? null : json(task.draftJob()));
    }

    private static void bindTaskBody(PreparedStatement statement, ChangeTask task, int offset) throws SQLException {
        WorkItemRef source = task.source();
        statement.setString(offset, source.type());
        statement.setString(offset + 1, source.externalId());
        statement.setString(offset + 2, source.url());
        statement.setString(offset + 3, json(source.labels()));
        statement.setString(offset + 4, source.priority());
        statement.setString(offset + 5, task.repository().repository());
        statement.setString(offset + 6, task.repository().baseRef());
        statement.setString(offset + 7, task.title());
        statement.setString(offset + 8, task.requirement());
        statement.setString(offset + 9, task.requesterId());
        statement.setString(offset + 10, task.projectContext());
        statement.setString(offset + 11, task.referencedContext());
        SpecRef spec = task.spec();
        statement.setString(offset + 12, spec == null ? null : spec.specId());
        if (spec == null) statement.setNull(offset + 13, java.sql.Types.INTEGER);
        else statement.setInt(offset + 13, spec.revision());
        statement.setString(offset + 14, spec == null ? null : spec.digest());
        statement.setString(offset + 15, path(spec == null ? null : spec.draftPath()));
        statement.setString(offset + 16, path(spec == null ? null : spec.lockedPath()));
        RiskAssessment risk = task.risk();
        statement.setString(offset + 17, risk == null ? null : risk.level().name());
        if (risk == null) statement.setNull(offset + 18, java.sql.Types.INTEGER);
        else statement.setInt(offset + 18, risk.score());
        statement.setString(offset + 19, risk == null ? null : json(risk.reasons()));
        statement.setString(offset + 20, task.route() == null ? null : json(task.route()));
        ApprovalRecord approval = task.specApproval();
        statement.setString(offset + 21, approval == null ? null : approval.id());
        statement.setString(offset + 22, approval == null ? null : approval.decision().name());
        statement.setString(offset + 23, approval == null ? null : approval.approverId());
        statement.setString(offset + 24, approval == null ? null : approval.reason());
        statement.setString(offset + 25, approval == null ? null : approval.createdAt().toString());
        ApprovalRecord delivery = task.deliveryApproval();
        statement.setString(offset + 26, delivery == null ? null : delivery.id());
        statement.setString(offset + 27, delivery == null ? null : delivery.decision().name());
        statement.setString(offset + 28, delivery == null ? null : delivery.approverId());
        statement.setString(offset + 29, delivery == null ? null : delivery.reason());
        statement.setString(offset + 30, delivery == null ? null : delivery.specDigest());
        statement.setString(offset + 31, delivery == null ? null : delivery.headSha());
        statement.setString(offset + 32, delivery == null ? null : delivery.createdAt().toString());
        WorkerClaimRef claim = task.workerClaim();
        statement.setString(offset + 33, claim == null ? null : claim.claimId());
        statement.setString(offset + 34, claim == null ? null : claim.claimedAt().toString());
        RunRef run = task.run();
        statement.setString(offset + 35, run == null ? null : run.runId());
        statement.setString(offset + 36, run == null ? null : run.specDigest());
        statement.setString(offset + 37, run == null ? null : run.verdict().name());
        statement.setString(offset + 38, run == null ? null : run.status().name());
        statement.setString(offset + 39, run == null ? null : run.workspaceId());
        statement.setString(offset + 40, run == null ? null : run.branch());
        statement.setString(offset + 41, run == null ? null : run.headSha());
        statement.setString(offset + 42, path(run == null ? null : run.evidencePath()));
        statement.setString(offset + 43, run == null ? null : run.completedAt().toString());
    }

    private void saveReview(ChangeTask task) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "UPDATE change_tasks SET human_review_json = ?, delivery_binding_json = ? WHERE id = ?")) {
            try {
                s.setString(1, task.humanReview() == null ? null : ChangeJson.MAPPER.writeValueAsString(task.humanReview()));
                s.setString(2, task.deliveryApproval() == null ? null : ChangeJson.MAPPER.writeValueAsString(task.deliveryApproval()));
            } catch (java.io.IOException e) { throw new SQLException("Cannot encode review", e); }
            s.setString(3, task.id().value());
            s.executeUpdate();
        }
    }

    private void appendEvent(ChangeEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO change_events (
                    change_id, event_type, actor_type, actor_id,
                    previous_state, new_state, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.changeId().value());
            statement.setString(2, event.type());
            statement.setString(3, event.actorType());
            statement.setString(4, event.actorId());
            statement.setString(5, event.previousState() == null ? null : event.previousState().name());
            statement.setString(6, event.newState() == null ? null : event.newState().name());
            statement.setString(7, event.payloadJson());
            statement.setString(8, event.createdAt().toString());
            statement.executeUpdate();
        }
    }

    private void appendApprovals(ChangeTask task) throws SQLException {
        appendApproval(task.id(), task.specApproval());
        appendApproval(task.id(), task.deliveryApproval());
    }

    private void appendApproval(ChangeTaskId changeId, ApprovalRecord approval) throws SQLException {
        if (approval == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO change_approvals (
                    id, change_id, stage, decision, approver_id, reason,
                    spec_digest, head_sha, created_at, run_id, judgment_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, approval.id());
            statement.setString(2, changeId.value());
            statement.setString(3, approval.stage().name());
            statement.setString(4, approval.decision().name());
            statement.setString(5, approval.approverId());
            statement.setString(6, approval.reason());
            statement.setString(7, approval.specDigest());
            statement.setString(8, approval.headSha());
            statement.setString(9, approval.createdAt().toString());
            statement.setString(10, approval.runId());
            statement.setLong(11, approval.judgmentRevision());
            statement.executeUpdate();
        }
    }

    private ChangeTask fromRow(ResultSet row) throws SQLException {
        SpecRef spec = row.getString("spec_id") == null ? null : new SpecRef(
                row.getString("spec_id"),
                row.getInt("spec_revision"),
                row.getString("spec_digest"),
                path(row.getString("spec_draft_path")),
                path(row.getString("spec_locked_path")));
        RiskAssessment risk = row.getString("risk_level") == null ? null : new RiskAssessment(
                RiskLevel.valueOf(row.getString("risk_level")),
                row.getInt("risk_score"),
                stringList(row.getString("risk_reasons_json")));
        ExecutionRoute route = row.getString("route_json") == null
                ? null
                : fromJson(row.getString("route_json"), ExecutionRoute.class);
        ApprovalRecord approval = row.getString("approval_id") == null ? null : new ApprovalRecord(
                row.getString("approval_id"),
                ApprovalRecord.Stage.SPEC,
                ApprovalRecord.Decision.valueOf(row.getString("approval_decision")),
                row.getString("approver_id"),
                row.getString("approval_reason"),
                spec == null ? "" : spec.digest(),
                "",
                Instant.parse(row.getString("approval_created_at")));
        ApprovalRecord deliveryApproval = row.getString("delivery_approval_id") == null
                ? null
                : new ApprovalRecord(
                        row.getString("delivery_approval_id"),
                        ApprovalRecord.Stage.DELIVERY,
                        ApprovalRecord.Decision.valueOf(row.getString("delivery_approval_decision")),
                        row.getString("delivery_approver_id"),
                        row.getString("delivery_approval_reason"),
                        row.getString("delivery_approval_spec_digest"),
                        row.getString("delivery_approval_head_sha"),
                        Instant.parse(row.getString("delivery_approval_created_at")));
        WorkerClaimRef claim = row.getString("active_claim_id") == null ? null : new WorkerClaimRef(
                row.getString("active_claim_id"),
                Instant.parse(row.getString("active_claimed_at")));
        RunRef run = row.getString("run_id") == null ? null : new RunRef(
                row.getString("run_id"),
                row.getString("run_spec_digest"),
                com.paicli.spec.SpecRunResult.Status.valueOf(row.getString("run_status")),
                com.paicli.spec.SpecRunResult.Verdict.valueOf(row.getString("run_verdict")),
                row.getString("run_workspace_id"),
                row.getString("run_branch"),
                row.getString("run_head_sha"),
                path(row.getString("run_evidence_path")),
                Instant.parse(row.getString("run_completed_at")));
        if (row.getString("delivery_binding_json") != null) {
            try { deliveryApproval = ChangeJson.MAPPER.readValue(row.getString("delivery_binding_json"), ApprovalRecord.class); }
            catch (java.io.IOException e) { throw new SQLException("Invalid delivery binding", e); }
        }
        HumanReview review = null;
        if (row.getString("human_review_json") != null) {
            try { review = ChangeJson.MAPPER.readValue(row.getString("human_review_json"), HumanReview.class); }
            catch (java.io.IOException e) { throw new SQLException("Invalid human review", e); }
        }
        return new ChangeTask(
                new ChangeTaskId(row.getString("id")),
                row.getString("idempotency_key"),
                row.getLong("version"),
                ChangeState.valueOf(row.getString("state")),
                new WorkItemRef(
                        row.getString("source_type"),
                        row.getString("source_external_id"),
                        row.getString("source_url"),
                        stringList(row.getString("source_labels_json")),
                        row.getString("source_priority")),
                new RepositoryRef(row.getString("repository"), row.getString("base_ref")),
                row.getString("title"),
                row.getString("requirement"),
                row.getString("requester_id"),
                row.getString("project_context"),
                row.getString("referenced_context"),
                spec,
                risk,
                route,
                approval,
                deliveryApproval,
                claim,
                run,
                row.getString("draft_job_json") == null ? null : fromJson(row.getString("draft_job_json"), DraftJob.class),
                review,
                Instant.parse(row.getString("created_at")),
                Instant.parse(row.getString("updated_at")));
    }

    private void ensureExecutionColumns() throws SQLException {
        ensureColumn("active_claim_id", "TEXT");
        ensureColumn("active_claimed_at", "TEXT");
        ensureColumn("run_id", "TEXT");
        ensureColumn("run_spec_digest", "TEXT");
        ensureColumn("run_verdict", "TEXT");
        ensureColumn("run_status", "TEXT");
        ensureColumn("run_workspace_id", "TEXT");
        ensureColumn("run_branch", "TEXT");
        ensureColumn("run_head_sha", "TEXT");
        ensureColumn("run_evidence_path", "TEXT");
        ensureColumn("run_completed_at", "TEXT");
    }

    private void ensurePhaseFourColumns() throws SQLException {
        ensureColumn("source_labels_json", "TEXT NOT NULL DEFAULT '[]'");
        ensureColumn("source_priority", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("requester_id", "TEXT NOT NULL DEFAULT 'unknown'");
        ensureColumn("risk_level", "TEXT");
        ensureColumn("risk_score", "INTEGER");
        ensureColumn("risk_reasons_json", "TEXT");
        ensureColumn("route_json", "TEXT");
        ensureColumn("delivery_approval_id", "TEXT");
        ensureColumn("delivery_approval_decision", "TEXT");
        ensureColumn("delivery_approver_id", "TEXT");
        ensureColumn("delivery_approval_reason", "TEXT");
        ensureColumn("delivery_approval_spec_digest", "TEXT");
        ensureColumn("delivery_approval_head_sha", "TEXT");
        ensureColumn("delivery_approval_created_at", "TEXT");
    }

    private void ensureColumn(String name, String type) throws SQLException {
        ensureColumn("change_tasks", name, type);
    }

    private void ensureColumn(String table, String name, String type) throws SQLException {
        boolean found = false;
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (name.equalsIgnoreCase(columns.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + type);
            }
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = operation.run();
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw persistenceFailure("ChangeTask 事务失败", e);
        }
    }

    private static boolean isConstraintViolation(SQLException error) {
        return error.getErrorCode() == 19 || (error.getMessage() != null && error.getMessage().contains("constraint"));
    }

    private static void validateEvent(ChangeTask task, ChangeEvent event) {
        if (event == null) {
            throw new NullPointerException("event");
        }
        if (!task.id().equals(event.changeId()) || task.state() != event.newState()) {
            throw new IllegalArgumentException("Change Event 必须描述同一 ChangeTask 的新状态");
        }
    }

    private static IllegalStateException persistenceFailure(String message, SQLException error) {
        return new IllegalStateException(message + ": " + error.getMessage(), error);
    }

    private static String path(Path path) {
        return path == null ? null : path.toString();
    }

    private static Path path(String path) {
        return path == null || path.isBlank() ? null : Path.of(path).toAbsolutePath().normalize();
    }

    private static ChangeState state(String value) {
        return value == null ? null : ChangeState.valueOf(value);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String json(Object value) throws SQLException {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new SQLException("ChangeTask JSON 编码失败", error);
        }
    }

    private static List<String> stringList(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readerForListOf(String.class).readValue(value);
        } catch (JsonProcessingException error) {
            throw new SQLException("ChangeTask JSON 列表解码失败", error);
        }
    }

    private static <T> T fromJson(String value, Class<T> type) throws SQLException {
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new SQLException("ChangeTask JSON 解码失败: " + type.getSimpleName(), error);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }
}
