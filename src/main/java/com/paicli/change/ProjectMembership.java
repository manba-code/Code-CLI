package com.paicli.change;

import java.util.Set;

public record ProjectMembership(String projectId, String subjectId, Set<ProjectRole> roles) {
    public ProjectMembership {
        if (projectId == null || projectId.isBlank() || subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("projectId 和 subjectId 必填");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
