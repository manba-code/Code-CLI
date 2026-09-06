package com.paicli.change;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Server-side GitLab configuration. Tokens are accepted only from process settings, never HTTP input. */
public record GitLabSettings(URI baseUrl, String projectId, String token, Path repository,
                             String baseRef, String remote, Duration timeout) {
    public GitLabSettings {
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").normalize();
        if (!("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("GitLab baseUrl 必须是无凭据、query 和 fragment 的 HTTP(S) URL");
        }
        projectId = required(projectId, "projectId");
        token = required(token, "token");
        repository = Objects.requireNonNull(repository, "repository").toAbsolutePath().normalize();
        baseRef = required(baseRef, "baseRef");
        remote = required(remote, "remote");
        if (!remote.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("GitLab remote 名称无效");
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("GitLab timeout 必须为正数");
    }

    public static GitLabSettings fromProcess() {
        String base = setting("paichange.gitlab.base-url", "PAICHANGE_GITLAB_BASE_URL", "");
        String project = setting("paichange.gitlab.project-id", "PAICHANGE_GITLAB_PROJECT_ID", "");
        String token = environmentSecret("PAICHANGE_GITLAB_TOKEN");
        String repository = setting("paichange.gitlab.repository", "PAICHANGE_GITLAB_REPOSITORY", "");
        String baseRef = setting("paichange.gitlab.base-ref", "PAICHANGE_GITLAB_BASE_REF", "main");
        String remote = setting("paichange.gitlab.remote", "PAICHANGE_GITLAB_REMOTE", "origin");
        long seconds;
        try {
            seconds = Long.parseLong(setting("paichange.gitlab.timeout-seconds",
                    "PAICHANGE_GITLAB_TIMEOUT_SECONDS", "15"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("GitLab timeout 配置必须是整数秒", e);
        }
        return new GitLabSettings(URI.create(base), project, token, Path.of(repository), baseRef, remote,
                Duration.ofSeconds(seconds));
    }

    public static boolean enabled() {
        return "gitlab".equalsIgnoreCase(setting("paichange.scm", "PAICHANGE_SCM", "mock"));
    }

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String environmentSecret(String environment) {
        String value = System.getenv(environment);
        return value == null ? "" : value.trim();
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("GitLab " + name + " 不能为空");
        return normalized;
    }
}
