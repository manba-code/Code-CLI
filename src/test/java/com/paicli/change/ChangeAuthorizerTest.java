package com.paicli.change;

import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChangeAuthorizerTest {
    private static final String PROJECT = "project-a";

    @Test void roleMatrixIsActionSpecificAndProjectBound() {
        var memberships = new InMemoryProjectMemberships();
        memberships.put(new ProjectMembership(PROJECT, "viewer", Set.of(ProjectRole.VIEWER)));
        memberships.put(new ProjectMembership(PROJECT, "developer", Set.of(ProjectRole.DEVELOPER)));
        memberships.put(new ProjectMembership(PROJECT, "approver", Set.of(ProjectRole.APPROVER)));
        memberships.put(new ProjectMembership(PROJECT, "admin", Set.of(ProjectRole.PROJECT_ADMIN)));
        var authorizer = new ChangeAuthorizer(memberships);

        assertEquals(Set.of(ChangePermission.READ_TASK, ChangePermission.READ_EVENTS,
                ChangePermission.READ_ARTIFACTS), authorizer.permissions(human("viewer"), PROJECT));
        assertTrue(authorizer.permissions(human("developer"), PROJECT).containsAll(Set.of(
                ChangePermission.CREATE_TASK, ChangePermission.CANCEL_DRAFT,
                ChangePermission.RETRY_DRAFT, ChangePermission.SUPPLEMENT_SPEC)));
        assertFalse(authorizer.permissions(human("developer"), PROJECT).contains(ChangePermission.APPROVE_SPEC));
        assertTrue(authorizer.permissions(human("approver"), PROJECT).containsAll(Set.of(
                ChangePermission.APPROVE_SPEC, ChangePermission.RECORD_HUMAN_EVIDENCE,
                ChangePermission.APPROVE_DELIVERY, ChangePermission.APPROVE_TOOL)));
        assertEquals(Set.copyOf(java.util.EnumSet.allOf(ChangePermission.class)),
                authorizer.permissions(human("admin"), PROJECT));
        assertTrue(authorizer.permissions(human("viewer"), "project-b").isEmpty());
    }

    @Test void serviceAccountsNeverReceiveHumanApprovalActionsAndRevocationIsImmediate() {
        var memberships = new InMemoryProjectMemberships();
        memberships.put(new ProjectMembership(PROJECT, "bot", Set.of(ProjectRole.PROJECT_ADMIN)));
        var authorizer = new ChangeAuthorizer(memberships);
        var permissions = authorizer.permissions(service("bot"), PROJECT);
        assertTrue(permissions.contains(ChangePermission.CREATE_TASK));
        assertFalse(permissions.contains(ChangePermission.APPROVE_SPEC));
        assertFalse(permissions.contains(ChangePermission.RECORD_HUMAN_EVIDENCE));
        assertFalse(permissions.contains(ChangePermission.APPROVE_DELIVERY));
        assertFalse(permissions.contains(ChangePermission.APPROVE_TOOL));
        memberships.remove(PROJECT, "bot");
        assertTrue(authorizer.permissions(service("bot"), PROJECT).isEmpty());
    }

    @Test void localTrustedPrincipalRetainsSingleOperatorCompatibility() {
        var local = new Principal("local-user", "Local operator", PrincipalType.HUMAN,
                "local", null, true);
        assertEquals(Set.copyOf(java.util.EnumSet.allOf(ChangePermission.class)),
                new ChangeAuthorizer(ProjectMembershipProvider.none()).permissions(local, "any-project"));
    }

    private static Principal human(String id) {
        return new Principal(id, id, PrincipalType.HUMAN, "test", Instant.now().plusSeconds(60), false);
    }

    private static Principal service(String id) {
        return new Principal(id, id, PrincipalType.SERVICE, "test", Instant.now().plusSeconds(60), false);
    }
}
