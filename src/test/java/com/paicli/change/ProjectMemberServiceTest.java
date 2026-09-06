package com.paicli.change;

import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectMemberServiceTest {
    @Test void bootstrapIsOneTimeAndServicePrincipalsCannotManageMembers() {
        Directory directory = new Directory();
        ChangeAuthorizer authorizer = new ChangeAuthorizer(directory);
        ProjectMemberService service = new ProjectMemberService(directory, authorizer, "bootstrap");

        assertThrows(ChangeForbiddenException.class,
                () -> service.list("project", human("other")));
        ProjectMember admin = service.put("project", "bootstrap", PrincipalType.HUMAN,
                Set.of(ProjectRole.PROJECT_ADMIN), 0, human("bootstrap"));
        assertEquals(1, admin.version());
        directory.replaceRoles(admin, Set.of(ProjectRole.VIEWER));
        assertThrows(ChangeForbiddenException.class,
                () -> service.put("project", "bootstrap", PrincipalType.HUMAN,
                        Set.of(ProjectRole.PROJECT_ADMIN), 0, human("bootstrap")),
                "bootstrap config must not bypass the database after initialization");
        assertThrows(ChangeForbiddenException.class,
                () -> service.list("project", service("bootstrap")));
    }

    @Test void auditExportIsAuthorizedBoundedJsonLinesWithChecksum() throws Exception {
        Directory directory = new Directory();
        ProjectMemberService service = new ProjectMemberService(directory, new ChangeAuthorizer(directory), "admin");
        Principal admin = human("admin");
        service.put("project", "admin", PrincipalType.HUMAN, Set.of(ProjectRole.PROJECT_ADMIN), 0, admin);
        directory.audit.add(new ProjectMemberAudit(1, "project", "admin", ProjectMemberAudit.Operation.BOOTSTRAP,
                null, 1L, null, PrincipalType.HUMAN, null, Set.of(ProjectRole.PROJECT_ADMIN),
                "admin", PrincipalType.HUMAN, Instant.parse("2026-09-06T00:00:00Z")));
        ProjectMemberAuditExport export = service.exportAudit("project", admin);
        assertEquals(1, export.recordCount());
        assertTrue(export.utf8().endsWith("\n"));
        assertTrue(export.utf8().contains("\"operation\":\"BOOTSTRAP\""));
        assertEquals(64, export.sha256().length());
        assertThrows(ChangeForbiddenException.class, () -> service.exportAudit("project", service("bot")));
    }

    private static Principal human(String id) {
        return new Principal(id, id, PrincipalType.HUMAN, "test", Instant.now().plusSeconds(60), false);
    }

    private static Principal service(String id) {
        return new Principal(id, id, PrincipalType.SERVICE, "test", Instant.now().plusSeconds(60), false);
    }

    private static final class Directory implements ProjectMembershipDirectory {
        private final Map<String, ProjectMember> members = new LinkedHashMap<>();
        private final List<ProjectMemberAudit> audit = new ArrayList<>();

        @Override public List<ProjectMembership> memberships(String subjectId) {
            return members.values().stream().filter(value -> value.subjectId().equals(subjectId))
                    .map(ProjectMember::authorizationView).toList();
        }
        @Override public List<ProjectMember> projectMembers(String projectId) {
            return members.values().stream().filter(value -> value.projectId().equals(projectId)).toList();
        }
        @Override public List<ProjectMemberAudit> audit(String projectId) { return List.copyOf(audit); }
        @Override public boolean hasMembers(String projectId) { return !projectMembers(projectId).isEmpty(); }
        @Override public ProjectMember put(String projectId, String subjectId, PrincipalType type,
                                           Set<ProjectRole> roles, long expectedVersion, Principal actor) {
            throw new AssertionError("normal update not expected");
        }
        @Override public ProjectMember bootstrap(String projectId, Principal actor) {
            if (hasMembers(projectId)) throw new ChangeConflictException("initialized");
            Instant now = Instant.now();
            ProjectMember member = new ProjectMember(projectId, actor.subjectId(), PrincipalType.HUMAN,
                    Set.of(ProjectRole.PROJECT_ADMIN), 1, now, now, actor.subjectId(), actor.type(),
                    actor.subjectId(), actor.type());
            members.put(projectId + "\0" + actor.subjectId(), member);
            return member;
        }
        @Override public ProjectMember remove(String projectId, String subjectId, long expectedVersion, Principal actor) {
            throw new AssertionError("remove not expected");
        }

        private void replaceRoles(ProjectMember current, Set<ProjectRole> roles) {
            members.put(current.projectId() + "\0" + current.subjectId(), new ProjectMember(
                    current.projectId(), current.subjectId(), current.principalType(), roles, current.version() + 1,
                    current.createdAt(), Instant.now(), current.createdBySubject(), current.createdByType(),
                    current.updatedBySubject(), current.updatedByType()));
        }
    }
}
