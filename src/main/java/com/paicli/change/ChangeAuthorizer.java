package com.paicli.change;

import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Central project-bound action authorization. Memberships are resolved on every request. */
public final class ChangeAuthorizer {
    private static final Set<ChangePermission> HUMAN_ONLY = EnumSet.of(
            ChangePermission.APPROVE_SPEC, ChangePermission.RECORD_HUMAN_EVIDENCE,
            ChangePermission.APPROVE_DELIVERY, ChangePermission.APPROVE_TOOL,
            ChangePermission.MANAGE_MEMBERS);
    private final ProjectMembershipProvider memberships;

    public ChangeAuthorizer(ProjectMembershipProvider memberships) {
        this.memberships = Objects.requireNonNull(memberships, "memberships");
    }

    public Set<ChangePermission> permissions(Principal principal, String projectId) {
        EnumSet<ChangePermission> result = principal.localTrusted()
                ? EnumSet.allOf(ChangePermission.class) : EnumSet.noneOf(ChangePermission.class);
        if (!principal.localTrusted()) {
            for (ProjectMembership membership : memberships.memberships(principal)) {
                if (membership.projectId().equals(projectId)) {
                    membership.roles().forEach(role -> result.addAll(role.permissions()));
                }
            }
        }
        if (principal.type() == PrincipalType.SERVICE) result.removeAll(HUMAN_ONLY);
        return Set.copyOf(result);
    }

    public List<ProjectMembership> memberships(Principal principal) {
        return principal.localTrusted() ? List.of() : memberships.memberships(principal);
    }

    public void require(Principal principal, String projectId, ChangePermission permission) {
        if (!permissions(principal, projectId).contains(permission)) {
            throw new ChangeForbiddenException("主体 " + principal.subjectId() + " 缺少项目动作权限 " + permission);
        }
    }
}
