package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** PostgreSQL V2 membership directory. Every mutation and its audit row commit atomically. */
public final class PostgresProjectMembershipDirectory implements ProjectMembershipDirectory {
    private final Connection connection;
    private final Clock clock;

    public PostgresProjectMembershipDirectory(String jdbcUrl, String user, String password) throws SQLException {
        this(DriverManager.getConnection(requireJdbcUrl(jdbcUrl), user, password), Clock.systemUTC());
    }

    PostgresProjectMembershipDirectory(Connection connection, Clock clock) throws SQLException {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = Objects.requireNonNull(clock, "clock");
        PostgresStorageMigrations.migrate(connection);
    }

    @Override public synchronized List<ProjectMembership> memberships(String subjectId) {
        requireText(subjectId, "subjectId");
        return memberships(subjectId, null);
    }

    @Override public synchronized List<ProjectMembership> memberships(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        return memberships(principal.subjectId(), principal.type());
    }

    private List<ProjectMembership> memberships(String subjectId, PrincipalType expectedType) {
        String sql = "SELECT project_id, subject_id, roles::text FROM project_members WHERE subject_id = ?"
                + (expectedType == null ? "" : " AND principal_type = ?") + " ORDER BY project_id";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, subjectId);
            if (expectedType != null) query.setString(2, expectedType.name());
            try (ResultSet rows = query.executeQuery()) {
                List<ProjectMembership> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ProjectMembership(rows.getString(1), rows.getString(2), roles(rows.getString(3))));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw failure("读取主体成员关系失败", e); }
    }

    @Override public synchronized List<ProjectMember> projectMembers(String projectId) {
        requireText(projectId, "projectId");
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT project_id, subject_id, principal_type, roles::text, version, created_at, updated_at,
                       created_by_subject, created_by_type, updated_by_subject, updated_by_type
                FROM project_members WHERE project_id = ? ORDER BY subject_id
                """)) {
            query.setString(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                List<ProjectMember> result = new ArrayList<>();
                while (rows.next()) result.add(member(rows));
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw failure("读取项目成员失败", e); }
    }

    @Override public synchronized boolean hasMembers(String projectId) {
        requireText(projectId, "projectId");
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM project_members WHERE project_id = ? LIMIT 1")) {
            query.setString(1, projectId);
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        } catch (SQLException e) { throw failure("检查项目成员失败", e); }
    }

    @Override public synchronized ProjectMember put(String projectId, String subjectId, PrincipalType type,
                                                     Set<ProjectRole> roles, long expectedVersion, Principal actor) {
        validateMutation(projectId, subjectId, type, roles, expectedVersion, actor);
        return transaction(() -> {
            lockProject(projectId);
            ProjectMember current = findForUpdate(projectId, subjectId);
            Instant now = clock.instant();
            if (current == null) {
                if (expectedVersion != 0) throw conflict(projectId, subjectId, expectedVersion, 0);
                ProjectMember created = new ProjectMember(projectId, subjectId, type, roles, 1, now, now,
                        actor.subjectId(), actor.type(), actor.subjectId(), actor.type());
                insert(created);
                audit(ProjectMemberAudit.Operation.ADD, null, created, actor, now);
                return created;
            }
            if (current.version() != expectedVersion) {
                throw conflict(projectId, subjectId, expectedVersion, current.version());
            }
            preventLastAdminRemoval(current, type, roles);
            ProjectMember updated = new ProjectMember(projectId, subjectId, type, roles, current.version() + 1,
                    current.createdAt(), now, current.createdBySubject(), current.createdByType(),
                    actor.subjectId(), actor.type());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE project_members SET principal_type = ?, roles = ?::jsonb, version = ?, updated_at = ?,
                        updated_by_subject = ?, updated_by_type = ?
                    WHERE project_id = ? AND subject_id = ? AND version = ?
                    """)) {
                statement.setString(1, type.name());
                statement.setString(2, encodeRoles(roles));
                statement.setLong(3, updated.version());
                statement.setObject(4, java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
                statement.setString(5, actor.subjectId());
                statement.setString(6, actor.type().name());
                statement.setString(7, projectId);
                statement.setString(8, subjectId);
                statement.setLong(9, expectedVersion);
                if (statement.executeUpdate() != 1) throw conflict(projectId, subjectId, expectedVersion, -1);
            }
            audit(ProjectMemberAudit.Operation.UPDATE, current, updated, actor, now);
            return updated;
        });
    }

    @Override public synchronized ProjectMember bootstrap(String projectId, Principal actor) {
        requireText(projectId, "projectId");
        requireHumanActor(actor);
        return transaction(() -> {
            lockProject(projectId);
            if (hasMembersInTransaction(projectId)) throw new ChangeConflictException("项目成员目录已经初始化");
            Instant now = clock.instant();
            ProjectMember created = new ProjectMember(projectId, actor.subjectId(), PrincipalType.HUMAN,
                    Set.of(ProjectRole.PROJECT_ADMIN), 1, now, now, actor.subjectId(), actor.type(),
                    actor.subjectId(), actor.type());
            insert(created);
            audit(ProjectMemberAudit.Operation.BOOTSTRAP, null, created, actor, now);
            return created;
        });
    }

    @Override public synchronized ProjectMember remove(String projectId, String subjectId, long expectedVersion,
                                                        Principal actor) {
        requireText(projectId, "projectId");
        requireText(subjectId, "subjectId");
        if (expectedVersion < 1) throw new IllegalArgumentException("移除成员的 expectedVersion 必须大于 0");
        requireHumanActor(actor);
        return transaction(() -> {
            lockProject(projectId);
            ProjectMember current = findForUpdate(projectId, subjectId);
            if (current == null) throw new ChangeConflictException("项目成员不存在或已被移除");
            if (current.version() != expectedVersion) {
                throw conflict(projectId, subjectId, expectedVersion, current.version());
            }
            preventLastAdminRemoval(current, null, Set.of());
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM project_members WHERE project_id = ? AND subject_id = ? AND version = ?")) {
                statement.setString(1, projectId);
                statement.setString(2, subjectId);
                statement.setLong(3, expectedVersion);
                if (statement.executeUpdate() != 1) throw conflict(projectId, subjectId, expectedVersion, -1);
            }
            audit(ProjectMemberAudit.Operation.REMOVE, current, null, actor, clock.instant());
            return current;
        });
    }

    @Override public synchronized List<ProjectMemberAudit> audit(String projectId) {
        requireText(projectId, "projectId");
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, project_id, subject_id, operation, previous_version, new_version,
                       before_principal_type, after_principal_type, before_roles::text, after_roles::text,
                       actor_subject, actor_type, occurred_at
                FROM project_member_audit WHERE project_id = ? ORDER BY id
                """)) {
            query.setString(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                List<ProjectMemberAudit> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ProjectMemberAudit(rows.getLong(1), rows.getString(2), rows.getString(3),
                            ProjectMemberAudit.Operation.valueOf(rows.getString(4)), nullableLong(rows, 5),
                            nullableLong(rows, 6), principalType(rows.getString(7)), principalType(rows.getString(8)),
                            nullableRoles(rows.getString(9)), nullableRoles(rows.getString(10)), rows.getString(11),
                            PrincipalType.valueOf(rows.getString(12)), rows.getObject(13, java.time.OffsetDateTime.class).toInstant()));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw failure("读取成员审计失败", e); }
    }

    private void preventLastAdminRemoval(ProjectMember current, PrincipalType nextType,
                                         Set<ProjectRole> nextRoles) throws SQLException {
        if (current.principalType() != PrincipalType.HUMAN
                || !current.roles().contains(ProjectRole.PROJECT_ADMIN)
                || (nextType == PrincipalType.HUMAN && nextRoles.contains(ProjectRole.PROJECT_ADMIN))) return;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM project_members
                WHERE project_id = ? AND principal_type = 'HUMAN'
                  AND roles @> '["PROJECT_ADMIN"]'::jsonb
                """)) {
            query.setString(1, current.projectId());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getLong(1) <= 1) {
                    throw new ChangeConflictException("不能移除项目最后一个 PROJECT_ADMIN");
                }
            }
        }
    }

    private void insert(ProjectMember member) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_members(project_id, subject_id, principal_type, roles, version,
                    created_at, updated_at, created_by_subject, created_by_type, updated_by_subject, updated_by_type)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, member.projectId()); statement.setString(2, member.subjectId());
            statement.setString(3, member.principalType().name()); statement.setString(4, encodeRoles(member.roles()));
            statement.setLong(5, member.version());
            statement.setObject(6, java.time.OffsetDateTime.ofInstant(member.createdAt(), java.time.ZoneOffset.UTC));
            statement.setObject(7, java.time.OffsetDateTime.ofInstant(member.updatedAt(), java.time.ZoneOffset.UTC));
            statement.setString(8, member.createdBySubject()); statement.setString(9, member.createdByType().name());
            statement.setString(10, member.updatedBySubject()); statement.setString(11, member.updatedByType().name());
            statement.executeUpdate();
        }
    }

    private void audit(ProjectMemberAudit.Operation operation, ProjectMember before, ProjectMember after,
                       Principal actor, Instant at) throws SQLException {
        ProjectMember value = after == null ? before : after;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_member_audit(project_id, subject_id, operation, previous_version, new_version,
                    before_principal_type, after_principal_type, before_roles, after_roles,
                    actor_subject, actor_type, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """)) {
            statement.setString(1, value.projectId()); statement.setString(2, value.subjectId());
            statement.setString(3, operation.name()); nullableLong(statement, 4, before == null ? null : before.version());
            nullableLong(statement, 5, after == null ? null : after.version());
            statement.setString(6, before == null ? null : before.principalType().name());
            statement.setString(7, after == null ? null : after.principalType().name());
            statement.setString(8, before == null ? null : encodeRoles(before.roles()));
            statement.setString(9, after == null ? null : encodeRoles(after.roles()));
            statement.setString(10, actor.subjectId()); statement.setString(11, actor.type().name());
            statement.setObject(12, java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private ProjectMember findForUpdate(String projectId, String subjectId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT project_id, subject_id, principal_type, roles::text, version, created_at, updated_at,
                       created_by_subject, created_by_type, updated_by_subject, updated_by_type
                FROM project_members WHERE project_id = ? AND subject_id = ? FOR UPDATE
                """)) {
            query.setString(1, projectId); query.setString(2, subjectId);
            try (ResultSet row = query.executeQuery()) { return row.next() ? member(row) : null; }
        }
    }

    private boolean hasMembersInTransaction(String projectId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM project_members WHERE project_id = ? LIMIT 1")) {
            query.setString(1, projectId);
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        }
    }

    private void lockProject(String projectId) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            lock.setString(1, projectId);
            lock.executeQuery().close();
        }
    }

    private ProjectMember member(ResultSet row) throws SQLException {
        return new ProjectMember(row.getString(1), row.getString(2), PrincipalType.valueOf(row.getString(3)),
                roles(row.getString(4)), row.getLong(5), row.getObject(6, java.time.OffsetDateTime.class).toInstant(),
                row.getObject(7, java.time.OffsetDateTime.class).toInstant(), row.getString(8),
                PrincipalType.valueOf(row.getString(9)), row.getString(10), PrincipalType.valueOf(row.getString(11)));
    }

    private static String encodeRoles(Set<ProjectRole> roles) {
        try { return ChangeJson.MAPPER.writeValueAsString(roles.stream().map(Enum::name).sorted().toList()); }
        catch (Exception e) { throw new IllegalArgumentException("成员角色无法编码", e); }
    }

    private static Set<ProjectRole> roles(String json) {
        try {
            JsonNode node = ChangeJson.MAPPER.readTree(json);
            EnumSet<ProjectRole> result = EnumSet.noneOf(ProjectRole.class);
            for (JsonNode value : node) result.add(ProjectRole.valueOf(value.asText()));
            return Set.copyOf(result);
        } catch (Exception e) { throw new IllegalStateException("数据库包含无效成员角色", e); }
    }

    private static Set<ProjectRole> nullableRoles(String json) { return json == null ? null : roles(json); }
    private static PrincipalType principalType(String value) { return value == null ? null : PrincipalType.valueOf(value); }
    private static Long nullableLong(ResultSet row, int index) throws SQLException {
        long value = row.getLong(index); return row.wasNull() ? null : value;
    }
    private static void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT); else statement.setLong(index, value);
    }

    private static void validateMutation(String projectId, String subjectId, PrincipalType type,
                                         Set<ProjectRole> roles, long expectedVersion, Principal actor) {
        requireText(projectId, "projectId"); requireText(subjectId, "subjectId");
        Objects.requireNonNull(type, "principalType"); Objects.requireNonNull(roles, "roles");
        if (roles.isEmpty()) throw new IllegalArgumentException("成员至少需要一个角色");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 必须是非负整数");
        requireHumanActor(actor);
    }

    private static void requireHumanActor(Principal actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() == PrincipalType.SERVICE) {
            throw new ChangeForbiddenException("SERVICE 主体不能管理项目成员");
        }
    }
    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " 不能为空");
        return normalized;
    }
    private static String requireJdbcUrl(String value) {
        if (value == null || !value.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PAICHANGE_POSTGRES_URL 必须是 jdbc:postgresql:// URL");
        }
        return value;
    }
    private static ChangeConflictException conflict(String projectId, String subjectId, long expected, long actual) {
        return new ChangeConflictException("成员版本冲突: project=" + projectId + ", subject=" + subjectId
                + ", expected=" + expected + ", actual=" + actual);
    }
    private static IllegalStateException failure(String message, SQLException error) {
        return new IllegalStateException(message, error);
    }

    private <T> T transaction(SqlWork<T> work) {
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run();
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof RuntimeException runtime) throw runtime;
                throw failure("成员目录事务失败", (SQLException) e);
            } finally { connection.setAutoCommit(autoCommit); }
        } catch (SQLException e) { throw failure("成员目录事务失败", e); }
    }

    @Override public synchronized void close() {
        try { connection.close(); } catch (SQLException e) { throw failure("关闭成员目录失败", e); }
    }

    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }
}
