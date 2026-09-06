package com.paicli.runtime.auth;

/** Pluggable server-side authentication boundary. Implementations must verify credentials. */
public interface PrincipalAdapter {
    Principal authenticate(AuthenticationRequest request);

    String mode();

    /** Live dependency probe used by readiness checks. Local adapters are process-local and always ready. */
    default void checkHealth() { }
}
