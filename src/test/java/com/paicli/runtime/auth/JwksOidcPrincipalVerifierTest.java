package com.paicli.runtime.auth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JwksOidcPrincipalVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    @Test void verifiesRequiredClaimsAndRefreshesJwksOnKeyRotation() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        KeyPair replacement = keyPair();
        AtomicReference<String> jwks = new AtomicReference<>(jwks("key-1", first));
        try (MockWebServer server = server(jwks)) {
            var verifier = verifier(server, NOW);
            Principal alice = verifier.verify(token("key-1", first, claims("https://issuer.example", "pai-api",
                    NOW.plusSeconds(60), NOW.minusSeconds(1), "alice", "HUMAN")));
            assertEquals("alice", alice.subjectId());
            assertEquals(PrincipalType.HUMAN, alice.type());

            jwks.set(jwks("key-2", second));
            Principal robot = verifier.verify(token("key-2", second, claims("https://issuer.example", "pai-api",
                    NOW.plusSeconds(60), null, "release-bot", "SERVICE")));
            assertEquals(PrincipalType.SERVICE, robot.type());
            assertTrue(server.getRequestCount() >= 2, "unknown kid must cause an immediate JWKS refresh");

            int beforeSameKidRotation = server.getRequestCount();
            jwks.set(jwks("key-2", replacement));
            Principal rotated = verifier.verify(token("key-2", replacement, claims("https://issuer.example", "pai-api",
                    NOW.plusSeconds(60), null, "rotated", "HUMAN")));
            assertEquals("rotated", rotated.subjectId());
            assertTrue(server.getRequestCount() > beforeSameKidRotation,
                    "signature failure for a reused kid must refresh JWKS once");
        }
    }

    @Test void rejectsExpiredNotYetValidForgedUnknownIssuerAudienceAndAlgorithm() throws Exception {
        KeyPair trusted = keyPair();
        KeyPair attacker = keyPair();
        AtomicReference<String> jwks = new AtomicReference<>(jwks("trusted", trusted));
        try (MockWebServer server = server(jwks)) {
            var verifier = verifier(server, NOW);
            assertThrows(AuthenticationException.class, () -> verifier.verify(token("trusted", trusted,
                    claims("https://issuer.example", "pai-api", NOW, null, "alice", null))));
            assertThrows(AuthenticationException.class, () -> verifier.verify(token("trusted", trusted,
                    claims("https://issuer.example", "pai-api", NOW.plusSeconds(60), NOW.plusSeconds(1), "alice", null))));
            assertThrows(AuthenticationException.class, () -> verifier.verify(token("trusted", attacker,
                    claims("https://issuer.example", "pai-api", NOW.plusSeconds(60), null, "alice", null))));
            assertThrows(AuthenticationException.class, () -> verifier.verify(token("unknown", attacker,
                    claims("https://issuer.example", "pai-api", NOW.plusSeconds(60), null, "alice", null))));
            assertThrows(AuthenticationException.class, () -> verifier.verify(token("trusted", trusted,
                    claims("other", "pai-api", NOW.plusSeconds(60), null, "alice", null))));
            assertThrows(AuthenticationException.class, () -> verifier.verify(token("trusted", trusted,
                    claims("https://issuer.example", "other", NOW.plusSeconds(60), null, "alice", null))));

            String none = encode(ChangeJsonHolder.object().put("alg", "none").put("kid", "trusted")) + "."
                    + encode(claims("https://issuer.example", "pai-api", NOW.plusSeconds(60), null, "alice", null)) + ".AA";
            assertThrows(AuthenticationException.class, () -> verifier.verify(none));
        }
    }

    private static JwksOidcPrincipalVerifier verifier(MockWebServer server, Instant now) {
        OidcSettings settings = new OidcSettings("https://issuer.example", "pai-api", server.url("/jwks").uri(),
                Set.of("RS256"), Duration.ofMinutes(5), Duration.ofSeconds(2), "admin");
        return new JwksOidcPrincipalVerifier(settings, new okhttp3.OkHttpClient(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static MockWebServer server(AtomicReference<String> jwks) throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(jwks.get());
            }
        });
        server.start();
        return server;
    }

    private static ObjectNode claims(String issuer, String audience, Instant exp, Instant nbf,
                                     String subject, String type) {
        ObjectNode claims = ChangeJsonHolder.object().put("iss", issuer).put("aud", audience)
                .put("exp", exp.getEpochSecond()).put("sub", subject).put("name", subject);
        if (nbf != null) claims.put("nbf", nbf.getEpochSecond());
        if (type != null) claims.put("principal_type", type);
        return claims;
    }

    private static String token(String kid, KeyPair key, ObjectNode claims) throws Exception {
        String header = encode(ChangeJsonHolder.object().put("alg", "RS256").put("kid", kid).put("typ", "JWT"));
        String payload = encode(claims);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update((header + "." + payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return header + "." + payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static String jwks(String kid, KeyPair key) throws Exception {
        RSAPublicKey rsa = (RSAPublicKey) key.getPublic();
        ObjectNode jwk = ChangeJsonHolder.object().put("kty", "RSA").put("use", "sig")
                .put("alg", "RS256").put("kid", kid)
                .put("n", integer(rsa.getModulus())).put("e", integer(rsa.getPublicExponent()));
        var root = ChangeJsonHolder.object();
        root.putArray("keys").add(jwk);
        return root.toString();
    }

    private static String integer(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(com.fasterxml.jackson.databind.JsonNode node) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ChangeJsonHolder.MAPPER.writeValueAsBytes(node));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static final class ChangeJsonHolder {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        private static ObjectNode object() { return MAPPER.createObjectNode(); }
    }
}
