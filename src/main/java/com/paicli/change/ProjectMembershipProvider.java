package com.paicli.change;

import com.paicli.runtime.auth.Principal;

import java.util.List;

public interface ProjectMembershipProvider {
    List<ProjectMembership> memberships(String subjectId);

    /** Persistent providers may bind a subject to its expected principal type. */
    default List<ProjectMembership> memberships(Principal principal) {
        return memberships(principal.subjectId());
    }

    static ProjectMembershipProvider none() { return ignored -> List.of(); }
}
