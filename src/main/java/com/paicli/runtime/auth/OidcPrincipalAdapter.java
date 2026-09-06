package com.paicli.runtime.auth;

import java.time.Clock;
import java.util.Objects;

/** OIDC transport adapter without claiming integration with any concrete identity provider. */
public final class OidcPrincipalAdapter implements PrincipalAdapter {
    private final OidcPrincipalVerifier verifier;
    private final Clock clock;

    public OidcPrincipalAdapter(OidcPrincipalVerifier verifier) {
        this(verifier, Clock.systemUTC());
    }

    public OidcPrincipalAdapter(OidcPrincipalVerifier verifier, Clock clock) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Principal authenticate(AuthenticationRequest request) {
        String token = request.bearerToken();
        if (token.isEmpty()) throw new AuthenticationException("缺少 Bearer token");
        Principal principal;
        try {
            principal = verifier.verify(token);
        } catch (AuthenticationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AuthenticationException("OIDC token 验证失败");
        }
        if (principal == null || principal.localTrusted()) {
            throw new AuthenticationException("OIDC verifier 未返回有效主体");
        }
        if (principal.expired(clock.instant())) throw new AuthenticationException("登录态已过期");
        return principal;
    }

    @Override public String mode() { return "OIDC"; }

    @Override public void checkHealth() { verifier.checkHealth(); }
}
