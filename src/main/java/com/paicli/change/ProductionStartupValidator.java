package com.paicli.change;

import com.paicli.runtime.auth.OidcSettings;

import java.net.URI;
import java.nio.file.Files;
import java.util.Locale;

/** Pure startup validation performed before production adapters can accept work. */
public final class ProductionStartupValidator {
    private ProductionStartupValidator() { }

    public static void validate(ProductionStorageSettings storage, ProductionOperationsSettings operations,
                                OidcSettings oidc, GitLabSettings gitlab) {
        if (storage == null || operations == null || oidc == null || gitlab == null) {
            throw new IllegalStateException("生产配置未完整装配");
        }
        if (storage.jdbcUrl() == null || !storage.jdbcUrl().startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("PAICHANGE_POSTGRES_URL 必须是 jdbc:postgresql:// URL");
        }
        URI postgres = URI.create(storage.jdbcUrl().substring("jdbc:".length()));
        requireSecure(postgres, "PostgreSQL", true);
        String query = postgres.getRawQuery() == null ? "" : postgres.getRawQuery().toLowerCase(Locale.ROOT);
        if (queryValue(query, "password") != null || queryValue(query, "user") != null) {
            throw new IllegalStateException("PostgreSQL 凭据不能放入 JDBC URL query");
        }
        if (!loopback(postgres.getHost()) && !"verify-full".equals(queryValue(query, "sslmode"))) {
            throw new IllegalStateException("远程 PostgreSQL 必须配置 sslmode=verify-full");
        }
        requireSecure(URI.create(storage.objectEndpoint()), "S3 endpoint", false);
        requireSecure(gitlab.baseUrl(), "GitLab baseUrl", false);
        if (storage.queueLeaseMillis() < storage.queuePollMillis() * 3L) {
            throw new IllegalStateException("PAICHANGE_QUEUE_LEASE_MS 必须至少是 POLL_MS 的 3 倍");
        }
        if (!Files.isDirectory(gitlab.repository())) {
            throw new IllegalStateException("PAICHANGE_GITLAB_REPOSITORY 必须是已存在目录");
        }
    }

    private static void requireSecure(URI uri, String name, boolean postgres) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException(name + " 必须是无 user-info/fragment 的绝对 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String expected = postgres ? "postgresql" : "https";
        if (!scheme.equals(expected) && !(loopback(uri.getHost()) && (scheme.equals("http") || scheme.equals("postgresql")))) {
            throw new IllegalStateException(name + " 必须使用安全传输；loopback 测试例外");
        }
    }

    private static boolean loopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
    }

    private static String queryValue(String query, String name) {
        for (String item : query.split("&")) {
            int separator = item.indexOf('=');
            if (separator > 0 && item.substring(0, separator).equals(name)) return item.substring(separator + 1);
        }
        return null;
    }
}
