package com.paicli.change;

import java.util.Objects;

public record ChangeRequest(
        String idempotencyKey,
        WorkItemRef source,
        RepositoryRef repository,
        String title,
        String requirement,
        String actorId,
        String actorType,
        String projectContext,
        String referencedContext
) {
    public ChangeRequest(String idempotencyKey, WorkItemRef source, RepositoryRef repository, String title,
                         String requirement, String actorId, String projectContext, String referencedContext) {
        this(idempotencyKey, source, repository, title, requirement, actorId, "LEGACY",
                projectContext, referencedContext);
    }

    public ChangeRequest {
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        source = source == null ? new WorkItemRef("", "", "") : source;
        repository = Objects.requireNonNull(repository, "repository");
        title = requireText(title, "title");
        requirement = requireText(requirement, "requirement");
        actorId = requireText(actorId, "actorId");
        actorType = requireText(actorType, "actorType");
        projectContext = projectContext == null ? "" : projectContext.trim();
        referencedContext = referencedContext == null ? "" : referencedContext.trim();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
