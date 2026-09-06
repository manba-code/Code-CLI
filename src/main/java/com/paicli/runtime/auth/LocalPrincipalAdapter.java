package com.paicli.runtime.auth;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Testable local token adapter for development and contract tests; tokens never become actor IDs. */
public final class LocalPrincipalAdapter implements PrincipalAdapter {
    private final Map<String, Principal> principals = new ConcurrentHashMap<>();
    private final Clock clock;

    public LocalPrincipalAdapter(Map<String, Principal> principals) {
        this(principals, Clock.systemUTC());
    }

    public LocalPrincipalAdapter(Map<String, Principal> principals, Clock clock) {
        this.principals.putAll(Objects.requireNonNull(principals, "principals"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void put(String token, Principal principal) {
        principals.put(requireToken(token), Objects.requireNonNull(principal, "principal"));
    }

    public void revoke(String token) {
        principals.remove(requireToken(token));
    }

    @Override
    public Principal authenticate(AuthenticationRequest request) {
        Principal principal = principals.get(request.bearerToken());
        if (principal == null || principal.expired(clock.instant())) {
            throw new AuthenticationException(principal == null ? "凭据无效" : "登录态已过期");
        }
        return principal;
    }

    @Override public String mode() { return "LOCAL_PRINCIPAL"; }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token 不能为空");
        return token;
    }
}
