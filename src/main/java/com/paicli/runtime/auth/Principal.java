package com.paicli.runtime.auth;

import java.time.Instant;
import java.util.Objects;

/** Identity established by a trusted server-side adapter. */
public record Principal(
        String subjectId,
        String displayName,
        PrincipalType type,
        String issuer,
        Instant expiresAt,
        boolean localTrusted
) {
    public Principal {
        subjectId = requireText(subjectId, "subjectId");
        displayName = displayName == null || displayName.isBlank() ? subjectId : displayName.trim();
        type = Objects.requireNonNull(type, "type");
        issuer = requireText(issuer, "issuer");
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    public String actorType() {
        return type.name();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return normalized;
    }
}
