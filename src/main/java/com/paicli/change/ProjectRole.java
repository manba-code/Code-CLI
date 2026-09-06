package com.paicli.change;

import java.util.EnumSet;
import java.util.Set;

public enum ProjectRole {
    VIEWER(EnumSet.of(ChangePermission.READ_TASK, ChangePermission.READ_EVENTS, ChangePermission.READ_ARTIFACTS)),
    DEVELOPER(EnumSet.of(ChangePermission.CREATE_TASK, ChangePermission.READ_TASK, ChangePermission.READ_EVENTS,
            ChangePermission.READ_ARTIFACTS, ChangePermission.CANCEL_DRAFT, ChangePermission.RETRY_DRAFT,
            ChangePermission.SUPPLEMENT_SPEC)),
    APPROVER(EnumSet.of(ChangePermission.READ_TASK, ChangePermission.READ_EVENTS, ChangePermission.READ_ARTIFACTS,
            ChangePermission.APPROVE_SPEC, ChangePermission.RECORD_HUMAN_EVIDENCE,
            ChangePermission.APPROVE_DELIVERY, ChangePermission.APPROVE_TOOL)),
    PROJECT_ADMIN(EnumSet.allOf(ChangePermission.class));

    private final Set<ChangePermission> permissions;

    ProjectRole(Set<ChangePermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<ChangePermission> permissions() { return permissions; }
}
