package com.paicli.runtime.api;

import com.paicli.runtime.auth.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrincipalAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void localTokenMapsToFixedServerSideSubjectAndCanBeRevokedOrExpire() {
        var active = new Principal("alice", "Alice", PrincipalType.HUMAN, "local-test", NOW.plusSeconds(1), false);
        var adapter = new LocalPrincipalAdapter(Map.of("token-a", active), CLOCK);
        assertEquals("alice", adapter.authenticate(new AuthenticationRequest("Bearer token-a", null)).subjectId());
        assertThrows(AuthenticationException.class,
                () -> adapter.authenticate(new AuthenticationRequest("Bearer alice", null)));
        adapter.revoke("token-a");
        assertThrows(AuthenticationException.class,
                () -> adapter.authenticate(new AuthenticationRequest("Bearer token-a", null)));

        var expired = new LocalPrincipalAdapter(Map.of("old", new Principal("alice", "Alice",
                PrincipalType.HUMAN, "local-test", NOW, false)), CLOCK);
        assertThrows(AuthenticationException.class,
                () -> expired.authenticate(new AuthenticationRequest("Bearer old", null)));
    }

    @Test void oidcBoundaryRejectsMissingExpiredAndLocalTrustedClaims() {
        var good = new Principal("oidc-sub", "Alice", PrincipalType.HUMAN,
                "https://issuer.example", NOW.plusSeconds(60), false);
        assertEquals("oidc-sub", new OidcPrincipalAdapter(token -> good, CLOCK)
                .authenticate(new AuthenticationRequest("Bearer signed", null)).subjectId());
        assertThrows(AuthenticationException.class, () -> new OidcPrincipalAdapter(token -> good, CLOCK)
                .authenticate(new AuthenticationRequest(null, null)));
        assertThrows(AuthenticationException.class, () -> new OidcPrincipalAdapter(token ->
                new Principal("sub", "Sub", PrincipalType.HUMAN, "issuer", NOW, false), CLOCK)
                .authenticate(new AuthenticationRequest("Bearer expired", null)));
        assertThrows(AuthenticationException.class, () -> new OidcPrincipalAdapter(token ->
                new Principal("local", "Local", PrincipalType.HUMAN, "issuer", null, true), CLOCK)
                .authenticate(new AuthenticationRequest("Bearer invalid", null)));
    }
}
