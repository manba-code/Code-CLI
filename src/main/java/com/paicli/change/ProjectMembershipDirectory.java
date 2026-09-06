package com.paicli.change;

import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;

import java.util.List;
import java.util.Set;

/** Mutable, audited membership directory. Authorization reads remain on ProjectMembershipProvider. */
public interface ProjectMembershipDirectory extends ProjectMembershipProvider, AutoCloseable {
    List<ProjectMember> projectMembers(String projectId);

    List<ProjectMemberAudit> audit(String projectId);

    boolean hasMembers(String projectId);

    ProjectMember put(String projectId, String subjectId, PrincipalType type, Set<ProjectRole> roles,
                      long expectedVersion, Principal actor);

    ProjectMember bootstrap(String projectId, Principal actor);

    ProjectMember remove(String projectId, String subjectId, long expectedVersion, Principal actor);

    @Override default void close() { }
}
