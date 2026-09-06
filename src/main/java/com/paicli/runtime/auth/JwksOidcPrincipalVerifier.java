package com.paicli.runtime.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Strict single-issuer JWT verifier backed by a rotating RSA JWKS. */
public final class JwksOidcPrincipalVerifier implements OidcPrincipalVerifier {
    private static final int MAX_TOKEN_CHARS = 65_536;
    private static final long MAX_JWKS_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final OidcSettings settings;
    private final OkHttpClient client;
    private final Clock clock;
    private Map<String, KeyEntry> keys = Map.of();
    private Instant refreshAt = Instant.EPOCH;

    public JwksOidcPrincipalVerifier(OidcSettings settings) {
        this(settings, new OkHttpClient.Builder()
                .connectTimeout(settings.httpTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(settings.httpTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(settings.httpTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false).build(), Clock.systemUTC());
    }

    JwksOidcPrincipalVerifier(OidcSettings settings, OkHttpClient client, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public Principal verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > MAX_TOKEN_CHARS) {
            throw new AuthenticationException("OIDC token 格式无效");
        }
        String[] parts = bearerToken.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new AuthenticationException("OIDC token 格式无效");
        }
        JsonNode header = json(parts[0], "header");
        JsonNode claims = json(parts[1], "claims");
        if (header.has("crit") || (header.has("b64")
                && (!header.path("b64").isBoolean() || !header.path("b64").booleanValue()))) {
            throw new AuthenticationException("OIDC token 包含不支持的 JOSE critical header");
        }
        String algorithm = requiredText(header, "alg");
        String keyId = requiredText(header, "kid");
        if (!settings.allowedAlgorithms().contains(algorithm)) {
            throw new AuthenticationException("OIDC token 签名算法不允许");
        }
        byte[] signature = decode(parts[2], "signature");
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);

        KeyEntry key = key(keyId, false);
        boolean valid = key != null && verifySignature(key, algorithm, signed, signature);
        if (!valid) {
            key = key(keyId, true);
            valid = key != null && verifySignature(key, algorithm, signed, signature);
        }
        if (!valid) throw new AuthenticationException("OIDC token 签名无效或 key 未知");

        String issuer = requiredText(claims, "iss");
        if (!settings.issuer().equals(issuer)) throw new AuthenticationException("OIDC issuer 不匹配");
        if (!audienceMatches(claims.path("aud"), settings.audience())) {
            throw new AuthenticationException("OIDC audience 不匹配");
        }
        Instant now = clock.instant();
        Instant expires = instantClaim(claims, "exp", true);
        if (!expires.isAfter(now)) throw new AuthenticationException("OIDC token 已过期");
        Instant notBefore = instantClaim(claims, "nbf", false);
        if (notBefore != null && notBefore.isAfter(now)) throw new AuthenticationException("OIDC token 尚未生效");
        String subject = requiredText(claims, "sub");
        String name = claims.path("name").isTextual() && !claims.path("name").asText().isBlank()
                ? claims.path("name").asText().trim() : subject;
        PrincipalType type = principalType(claims.path("principal_type"));
        return new Principal(subject, name, type, issuer, expires, false);
    }

    /** Readiness is intentionally a live JWKS fetch, not merely a check of a possibly stale cache. */
    @Override public synchronized void checkHealth() {
        refresh();
    }

    private synchronized KeyEntry key(String keyId, boolean forceRefresh) {
        Instant now = clock.instant();
        if (forceRefresh || keys.isEmpty() || !now.isBefore(refreshAt)) refresh();
        return keys.get(keyId);
    }

    private void refresh() {
        Request request = new Request.Builder().url(settings.jwksUri().toString()).get()
                .header("Accept", "application/json").build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) throw new AuthenticationException("JWKS 获取失败");
            if (body.contentLength() > MAX_JWKS_BYTES) throw new AuthenticationException("JWKS 响应过大");
            byte[] bytes;
            try (var input = body.byteStream()) { bytes = input.readNBytes((int) MAX_JWKS_BYTES + 1); }
            if (bytes.length > MAX_JWKS_BYTES) throw new AuthenticationException("JWKS 响应过大");
            JsonNode document = JSON.readTree(bytes);
            if (!document.path("keys").isArray()) throw new AuthenticationException("JWKS 格式无效");
            Map<String, KeyEntry> loaded = new HashMap<>();
            for (JsonNode jwk : document.path("keys")) {
                KeyEntry entry = rsa(jwk);
                if (entry == null) continue;
                if (loaded.putIfAbsent(entry.keyId(), entry) != null) {
                    throw new AuthenticationException("JWKS 包含重复 kid");
                }
            }
            if (loaded.isEmpty()) throw new AuthenticationException("JWKS 没有可用 RSA 签名 key");
            keys = Map.copyOf(loaded);
            refreshAt = clock.instant().plus(settings.jwksCacheTtl());
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("JWKS 获取或解析失败");
        }
    }

    private KeyEntry rsa(JsonNode jwk) {
        try {
            if (!"RSA".equals(jwk.path("kty").asText())) return null;
            if (jwk.has("use") && !"sig".equals(jwk.path("use").asText())) return null;
            if (jwk.has("key_ops") && !contains(jwk.path("key_ops"), "verify")) return null;
            String keyId = requiredText(jwk, "kid");
            String declaredAlgorithm = jwk.path("alg").isTextual() ? jwk.path("alg").asText() : null;
            if (declaredAlgorithm != null && !settings.allowedAlgorithms().contains(declaredAlgorithm)) return null;
            BigInteger modulus = new BigInteger(1, decode(requiredText(jwk, "n"), "modulus"));
            BigInteger exponent = new BigInteger(1, decode(requiredText(jwk, "e"), "exponent"));
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            if (((RSAPublicKey) publicKey).getModulus().bitLength() < 2048) return null;
            return new KeyEntry(keyId, declaredAlgorithm, publicKey);
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("JWKS RSA key 无效");
        }
    }

    private static boolean verifySignature(KeyEntry key, String algorithm, byte[] signed, byte[] signature) {
        if (key.algorithm() != null && !key.algorithm().equals(algorithm)) return false;
        String jca = switch (algorithm) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            default -> throw new AuthenticationException("OIDC token 签名算法不支持");
        };
        try {
            Signature verifier = Signature.getInstance(jca);
            verifier.initVerify(key.key()); verifier.update(signed);
            return verifier.verify(signature);
        } catch (Exception e) { throw new AuthenticationException("OIDC token 签名验证失败"); }
    }

    private static JsonNode json(String encoded, String label) {
        try {
            JsonNode node = JSON.readTree(decode(encoded, label));
            if (node == null || !node.isObject()) throw new AuthenticationException("OIDC token " + label + " 无效");
            return node;
        } catch (AuthenticationException e) { throw e; }
        catch (Exception e) { throw new AuthenticationException("OIDC token " + label + " 无效"); }
    }

    private static byte[] decode(String value, String label) {
        try { return Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw new AuthenticationException("OIDC " + label + " Base64URL 无效"); }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new AuthenticationException("OIDC 缺少必需 claim/header: " + field);
        }
        return value.asText().trim();
    }

    private static Instant instantClaim(JsonNode claims, String name, boolean required) {
        JsonNode value = claims.path(name);
        if (value.isMissingNode() && !required) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new AuthenticationException("OIDC " + name + " 必须是 NumericDate");
        }
        try { return Instant.ofEpochSecond(value.longValue()); }
        catch (RuntimeException e) { throw new AuthenticationException("OIDC " + name + " 超出范围"); }
    }

    private static boolean audienceMatches(JsonNode audience, String required) {
        if (audience.isTextual()) return required.equals(audience.asText());
        if (!audience.isArray()) return false;
        Iterator<JsonNode> values = audience.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (value.isTextual() && required.equals(value.asText())) return true;
        }
        return false;
    }

    private static boolean contains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (value.isTextual() && expected.equals(value.asText())) return true;
        return false;
    }

    private static PrincipalType principalType(JsonNode claim) {
        if (claim.isMissingNode() || claim.isNull()) return PrincipalType.HUMAN;
        if (!claim.isTextual()) throw new AuthenticationException("OIDC principal_type claim 无效");
        try { return PrincipalType.valueOf(claim.asText()); }
        catch (IllegalArgumentException e) { throw new AuthenticationException("OIDC principal_type claim 无效"); }
    }

    private record KeyEntry(String keyId, String algorithm, PublicKey key) { }
}
