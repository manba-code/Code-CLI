package com.paicli.runtime.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;

/**
 * Single-operator localhost compatibility mode. This is intentionally not a shared-deployment
 * identity scheme: one key always resolves to one fixed server-side subject.
 */
public final class LocalApiKeyPrincipalAdapter implements PrincipalAdapter {
    public static final String SUBJECT = "local-user";
    private final byte[] apiKey;
    private final Clock clock;

    public LocalApiKeyPrincipalAdapter(String apiKey) {
        this(apiKey, Clock.systemUTC());
    }

    LocalApiKeyPrincipalAdapter(String apiKey, Clock clock) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Runtime API 需要配置 PAICLI_RUNTIME_API_KEY 或 -Dpaicli.runtime.api.key");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public Principal authenticate(AuthenticationRequest request) {
        String supplied = request.bearerToken();
        if (supplied.isEmpty()) supplied = request.directApiKey() == null ? "" : request.directApiKey();
        if (!MessageDigest.isEqual(apiKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthenticationException("凭据无效");
        }
        return new Principal(SUBJECT, "Local operator", PrincipalType.HUMAN,
                "paicli-local-api-key", null, true);
    }

    @Override public String mode() { return "LOCAL_API_KEY"; }
}
