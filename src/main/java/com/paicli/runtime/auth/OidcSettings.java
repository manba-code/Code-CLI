package com.paicli.runtime.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit single-issuer M7a configuration. No discovery or multi-IdP routing is implied. */
public record OidcSettings(
        String issuer,
        String audience,
        URI jwksUri,
        Set<String> allowedAlgorithms,
        Duration jwksCacheTtl,
        Duration httpTimeout,
        String bootstrapAdminSubject
) {
    private static final Set<String> SUPPORTED = Set.of("RS256", "RS384", "RS512");

    public OidcSettings {
        issuer = requiredText(issuer, "issuer");
        audience = requiredText(audience, "audience");
        validateEndpoint(URI.create(issuer), "issuer");
        validateEndpoint(jwksUri, "JWKS");
        allowedAlgorithms = allowedAlgorithms == null ? Set.of() : Set.copyOf(allowedAlgorithms);
        if (allowedAlgorithms.isEmpty() || !SUPPORTED.containsAll(allowedAlgorithms)) {
            throw new IllegalArgumentException("OIDC 签名算法只支持显式配置 RS256/RS384/RS512");
        }
        if (jwksCacheTtl == null || jwksCacheTtl.isNegative() || jwksCacheTtl.isZero()) {
            throw new IllegalArgumentException("JWKS cache TTL 必须大于 0");
        }
        if (httpTimeout == null || httpTimeout.isNegative() || httpTimeout.isZero()) {
            throw new IllegalArgumentException("OIDC HTTP timeout 必须大于 0");
        }
        bootstrapAdminSubject = requiredText(bootstrapAdminSubject, "bootstrapAdminSubject");
    }

    public static OidcSettings fromProcess() {
        String mode = value("paichange.auth", "PAICHANGE_AUTH", "");
        if (!"oidc".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("生产 PostgreSQL 模式要求 PAICHANGE_AUTH=oidc");
        }
        Set<String> algorithms = Arrays.stream(value("paichange.oidc.algorithms",
                        "PAICHANGE_OIDC_ALGORITHMS", "RS256").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new OidcSettings(
                required("paichange.oidc.issuer", "PAICHANGE_OIDC_ISSUER"),
                required("paichange.oidc.audience", "PAICHANGE_OIDC_AUDIENCE"),
                URI.create(required("paichange.oidc.jwks-uri", "PAICHANGE_OIDC_JWKS_URI")),
                algorithms,
                Duration.ofSeconds(integer("paichange.oidc.jwks-cache-seconds", "PAICHANGE_OIDC_JWKS_CACHE_SECONDS", 300, 1, 86400)),
                Duration.ofSeconds(integer("paichange.oidc.http-timeout-seconds", "PAICHANGE_OIDC_HTTP_TIMEOUT_SECONDS", 10, 1, 60)),
                required("paichange.bootstrap.admin.subject", "PAICHANGE_BOOTSTRAP_ADMIN_SUBJECT"));
    }

    public PrincipalAdapter principalAdapter() {
        return new OidcPrincipalAdapter(new JwksOidcPrincipalVerifier(this));
    }

    private static void validateEndpoint(URI uri, String name) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(name + " 必须是无 user-info/fragment 的绝对 HTTP(S) URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback(uri.getHost()))) {
            throw new IllegalArgumentException(name + " 必须使用 HTTPS；本地测试只允许 loopback HTTP");
        }
    }

    private static boolean loopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1");
    }

    private static String required(String property, String environment) {
        String configured = value(property, environment, "");
        if (configured.isBlank()) throw new IllegalStateException(environment + " 必填");
        return configured;
    }

    private static int integer(String property, String environment, int fallback, int min, int max) {
        String configured = value(property, environment, Integer.toString(fallback));
        try {
            int parsed = Integer.parseInt(configured);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(environment + " 必须在 " + min + ".." + max + " 范围内");
        }
    }

    private static String value(String property, String environment, String fallback) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) configured = System.getenv(environment);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value.trim();
    }
}
