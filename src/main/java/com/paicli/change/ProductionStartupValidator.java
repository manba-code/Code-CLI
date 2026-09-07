package com.paicli.change;

import com.paicli.runtime.auth.OidcSettings;

import java.net.URI;
import java.nio.file.Files;
import java.util.Locale;

/** Pure startup validation performed before production adapters can accept work. */
public final class ProductionStartupValidator {
    private ProductionStartupValidator() { }

    public static void validate(ProductionStorageSettings storage, ProductionOperationsSettings operations,
                                OidcSettings oidc, RemoteScmSettings scm) {
        if (storage == null || operations == null || oidc == null || scm == null) {
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
        if (storage.queueLeaseMillis() < storage.queuePollMillis() * 3L) {
            throw new IllegalStateException("PAICHANGE_QUEUE_LEASE_MS 必须至少是 POLL_MS 的 3 倍");
        }
        validateScm(scm);
    }

    public static void validateScm(RemoteScmSettings scm) {
        if (scm == null) throw new IllegalStateException("远程 SCM 配置未装配");
        requireSecure(scm.baseUrl(), scm.provider() + " baseUrl", false);
        validateRepository(scm);
    }

    private static void validateRepository(RemoteScmSettings scm) {
        if (!Files.isDirectory(scm.repository())) {
            throw new IllegalStateException(scm.provider() + " checkout 必须是已存在目录");
        }
        try {
            String workTree = GitBranchPusher.git(scm.repository(), "读取 checkout 失败",
                    "rev-parse", "--is-inside-work-tree").trim();
            if (!"true".equals(workTree)) throw new IllegalStateException(scm.provider() + " checkout 不是 Git 工作树");
            GitBranchPusher.git(scm.repository(), "base ref 不可解析", "rev-parse", "--verify",
                    scm.baseRef() + "^{commit}");
            String remoteUrl = GitBranchPusher.git(scm.repository(), "remote 不存在", "remote", "get-url",
                    scm.remote()).trim();
            if ((remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://"))
                    && URI.create(remoteUrl).getUserInfo() != null) {
                throw new IllegalStateException(scm.provider() + " remote URL 不得内嵌凭据");
            }
            if (!loopback(scm.baseUrl().getHost()) && !matchesIdentity(remoteUrl, scm)) {
                throw new IllegalStateException(scm.provider() + " remote 与配置的仓库身份不一致");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(scm.provider() + " checkout/base ref/remote 启动校验失败", e);
        }
    }

    private static boolean matchesIdentity(String remoteUrl, RemoteScmSettings scm) {
        String normalized = remoteUrl.trim().replace('\\', '/');
        String host = remoteHost(normalized);
        if (host == null || !host.equalsIgnoreCase(scm.expectedRemoteHost())) return false;
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith(".git")) normalized = normalized.substring(0, normalized.length() - 4);
        String expected = scm.repositoryIdentity().replace('\\', '/');
        return normalized.endsWith("/" + expected) || normalized.endsWith(":" + expected);
    }

    private static String remoteHost(String remoteUrl) {
        try {
            if (remoteUrl.contains("://")) return URI.create(remoteUrl).getHost();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int at = remoteUrl.lastIndexOf('@');
        int colon = remoteUrl.indexOf(':', at + 1);
        if (colon <= at + 1) return null;
        return remoteUrl.substring(at + 1, colon);
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
