package com.paicli.change;

import java.util.Objects;

public record RepositoryRef(String repository, String baseRef) {
    public RepositoryRef {
        repository = requireText(repository, "repository");
        baseRef = requireText(baseRef, "baseRef");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
