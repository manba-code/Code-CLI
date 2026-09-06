package com.paicli.change;

import com.paicli.runtime.auth.PrincipalType;

import java.time.Instant;
import java.util.Set;

public record ProjectMemberAudit(
        long id,
        String projectId,
        String subjectId,
        Operation operation,
        Long previousVersion,
        Long newVersion,
        PrincipalType beforePrincipalType,
        PrincipalType afterPrincipalType,
        Set<ProjectRole> beforeRoles,
        Set<ProjectRole> afterRoles,
        String actorSubject,
        PrincipalType actorType,
        Instant occurredAt
) {
    public enum Operation { BOOTSTRAP, ADD, UPDATE, REMOVE }
}
