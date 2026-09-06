package com.paicli.runtime.auth;

/** Minimal transport-neutral credential input for identity adapters. */
public record AuthenticationRequest(String authorization, String directApiKey) {
    public String bearerToken() {
        if (authorization == null || !authorization.startsWith("Bearer ")) return "";
        return authorization.substring("Bearer ".length()).trim();
    }
}
