package com.paicli.runtime.auth;

/**
 * Provider-neutral OIDC boundary. A deployment adapter must verify signature, issuer, audience,
 * expiry and required claims before returning a Principal.
 */
@FunctionalInterface
public interface OidcPrincipalVerifier {
    Principal verify(String bearerToken);

    default void checkHealth() { }
}
