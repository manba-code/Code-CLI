package com.paicli.change;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Server-side GitHub configuration. The token is intentionally environment-only. */
public record GitHubSettings(URI baseUrl, String owner, String repositoryName, String token, Path repository,
                             String baseRef, String remote, Duration timeout) implements RemoteScmSettings {
    public GitHubSettings {
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").normalize();
        if (!("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("GitHub baseUrl 必须是无凭据、query 和 fragment 的 HTTP(S) URL");
        }
        owner = required(owner, "owner");
        repositoryName = required(repositoryName, "repositoryName");
        if (owner.contains("/") || repositoryName.contains("/")) {
            throw new IllegalArgumentException("GitHub owner/repositoryName 不得包含路径分隔符");
        }
        token = required(token, "token");
        repository = Objects.requireNonNull(repository, "repository").toAbsolutePath().normalize();
        baseRef = required(baseRef, "baseRef");
        remote = required(remote, "remote");
        if (!remote.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("GitHub remote 名称无效");
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("GitHub timeout 必须为正数");
    }

    public static GitHubSettings fromProcess() {
        String base = setting("paichange.github.api-base-url", "PAICHANGE_GITHUB_API_BASE_URL", "https://api.github.com");
        String owner = setting("paichange.github.owner", "PAICHANGE_GITHUB_OWNER", "");
        String name = setting("paichange.github.repository-name", "PAICHANGE_GITHUB_REPOSITORY_NAME", "");
        String token = environmentSecret("PAICHANGE_GITHUB_TOKEN");
        String repository = setting("paichange.github.checkout", "PAICHANGE_GITHUB_CHECKOUT", "");
        String baseRef = setting("paichange.github.base-ref", "PAICHANGE_GITHUB_BASE_REF", "main");
        String remote = setting("paichange.github.remote", "PAICHANGE_GITHUB_REMOTE", "origin");
        long seconds;
        try {
            seconds = Long.parseLong(setting("paichange.github.timeout-seconds",
                    "PAICHANGE_GITHUB_TIMEOUT_SECONDS", "15"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("GitHub timeout 配置必须是整数秒", e);
        }
        return new GitHubSettings(URI.create(base), owner, name, token, Path.of(repository), baseRef, remote,
                Duration.ofSeconds(seconds));
    }

    public String slug() { return owner + "/" + repositoryName; }
    @Override public String provider() { return "GitHub"; }
    @Override public String repositoryIdentity() { return slug(); }
    @Override public String expectedRemoteHost() {
        return "api.github.com".equalsIgnoreCase(baseUrl.getHost()) ? "github.com" : baseUrl.getHost();
    }
    @Override public String toString() {
        return "GitHubSettings[baseUrl=" + baseUrl + ", owner=" + owner + ", repositoryName="
                + repositoryName + ", token=<redacted>, repository=" + repository + ", baseRef=" + baseRef
                + ", remote=" + remote + ", timeout=" + timeout + "]";
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
        if (normalized.isEmpty()) throw new IllegalArgumentException("GitHub " + name + " 不能为空");
        return normalized;
    }
}
