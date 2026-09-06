package com.paicli.change;

import com.paicli.runtime.auth.PrincipalType;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Durable project membership row exposed by the M7a administration boundary. */
public record ProjectMember(
        String projectId,
        String subjectId,
        PrincipalType principalType,
        Set<ProjectRole> roles,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String createdBySubject,
        PrincipalType createdByType,
        String updatedBySubject,
        PrincipalType updatedByType
) {
    public ProjectMember {
        projectId = text(projectId, "projectId");
        subjectId = text(subjectId, "subjectId");
        principalType = Objects.requireNonNull(principalType, "principalType");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (roles.isEmpty()) throw new IllegalArgumentException("成员至少需要一个角色");
        if (version < 1) throw new IllegalArgumentException("成员 version 必须大于 0");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        createdBySubject = text(createdBySubject, "createdBySubject");
        createdByType = Objects.requireNonNull(createdByType, "createdByType");
        updatedBySubject = text(updatedBySubject, "updatedBySubject");
        updatedByType = Objects.requireNonNull(updatedByType, "updatedByType");
    }

    public ProjectMembership authorizationView() {
        return new ProjectMembership(projectId, subjectId, roles);
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " 不能为空");
        return normalized;
    }
}
