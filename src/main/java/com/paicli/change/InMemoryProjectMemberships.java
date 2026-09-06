package com.paicli.change;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable local membership source so revocation behavior can be tested without an external IdP. */
public final class InMemoryProjectMemberships implements ProjectMembershipProvider {
    private final Map<String, ProjectMembership> values = new ConcurrentHashMap<>();

    public void put(ProjectMembership membership) {
        values.put(key(membership.projectId(), membership.subjectId()), membership);
    }

    public void remove(String projectId, String subjectId) {
        values.remove(key(projectId, subjectId));
    }

    @Override
    public List<ProjectMembership> memberships(String subjectId) {
        return values.values().stream().filter(value -> value.subjectId().equals(subjectId)).toList();
    }

    private static String key(String projectId, String subjectId) { return projectId + "\u0000" + subjectId; }
}
