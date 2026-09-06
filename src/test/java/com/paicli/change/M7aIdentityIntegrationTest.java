package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.api.ChangeApiHandler;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.auth.OidcSettings;
import com.paicli.runtime.auth.PrincipalType;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in local fake OIDC/JWKS + real PostgreSQL M7a acceptance. */
class M7aIdentityIntegrationTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void oidcAndPersistentMembershipsCloseTheMinimumProductionIdentityLoop() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("paichange.m7a.integration.enabled"),
                "run with -Ppaichange-m7a-it or docker/run-paichange-m7a-tests.sh");
        String jdbc = System.getProperty("paichange.m7a.test.jdbc",
                "jdbc:postgresql://127.0.0.1:55433/paichange");
        String user = System.getProperty("paichange.m7a.test.user", "paichange");
        String password = System.getProperty("paichange.m7a.test.password", "paichange-test-only");
        String project = "project-" + UUID.randomUUID().toString().replace("-", "");
        String otherProject = project + "-other";
        Instant now = Instant.now();
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        KeyPair attacker = keyPair();
        AtomicReference<String> jwks = new AtomicReference<>(jwks("key-1", first));

        // Recreate the exact pre-M7a state: a valid V1 install with no V2 tables, then upgrade in place.
        try (var connection = DriverManager.getConnection(jdbc, user, password)) {
            PostgresStorageMigrations.migrate(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE project_member_audit");
                statement.execute("DROP TABLE project_members");
                statement.execute("DELETE FROM paichange_schema_migrations WHERE version = 2");
            }
            assertEquals(1, PostgresStorageMigrations.version(connection));
        }

        try (MockWebServer oidc = new MockWebServer()) {
            oidc.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(jwks.get());
                }
            });
            oidc.start();
            OidcSettings settings = new OidcSettings("https://fake-idp.example", "paichange-api",
                    oidc.url("/jwks").uri(), Set.of("RS256"), Duration.ofMinutes(5), Duration.ofSeconds(2),
                    "bootstrap-admin");
            String adminToken = token("key-1", first, claims(settings, now.plusSeconds(600), null,
                    "bootstrap-admin", PrincipalType.HUMAN));
            String devToken = token("key-1", first, claims(settings, now.plusSeconds(600), null,
                    "developer", PrincipalType.HUMAN));
            String botToken = token("key-1", first, claims(settings, now.plusSeconds(600), null,
                    "release-bot", PrincipalType.SERVICE));

            try (PostgresProjectMembershipDirectory directory =
                         new PostgresProjectMembershipDirectory(jdbc, user, password);
                 SqliteChangeStore changes = new SqliteChangeStore(root.resolve("changes.db"));
                 RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"))) {
                assertEquals(2, PostgresStorageMigrations.CURRENT_VERSION);
                try (var connection = DriverManager.getConnection(jdbc, user, password)) {
                    assertEquals(2, PostgresStorageMigrations.version(connection));
                }
                ChangeAuthorizer authorizer = new ChangeAuthorizer(directory);
                ProjectMemberService memberService = new ProjectMemberService(directory, authorizer,
                        settings.bootstrapAdminSubject());
                var workflow = new DefaultChangeWorkflow(changes, changes, ChangeTestSupport.specs(root));
                var handler = new ChangeApiHandler(workflow, changes, new MockWorkItemAdapter(root, workflow),
                        null, false, authorizer, null, WorkerIsolation.Capabilities.disabled(), true, memberService);
                try (RuntimeApiServer api = new RuntimeApiServer(threads, value -> value, 0,
                        settings.principalAdapter(), handler)) {
                    api.start();
                    String base = "http://127.0.0.1:" + api.port();
                    String members = "/v1/changes/projects/" + project + "/members";

                    assertEquals(403, send(base, "GET", members, null, adminToken).statusCode());
                    assertEquals(403, send(base, "GET", "/v1/changes/projects/" + otherProject + "/members",
                            null, devToken).statusCode());
                    HttpResponse<String> bootstrapped = send(base, "PUT", members,
                            member("bootstrap-admin", "HUMAN", "PROJECT_ADMIN", 0), adminToken);
                    assertEquals(201, bootstrapped.statusCode(), bootstrapped.body());
                    assertEquals(1, json(bootstrapped).path("version").asLong());

                    assertEquals(201, send(base, "PUT", members,
                            member("developer", "HUMAN", "DEVELOPER", 0), adminToken).statusCode());
                    JsonNode devCapabilities = json(send(base, "GET", "/v1/changes/capabilities", null, devToken));
                    assertTrue(values(devCapabilities.path("memberships").get(0).path("roles")).contains("DEVELOPER"));
                    assertEquals(200, send(base, "PUT", members,
                            member("developer", "HUMAN", "VIEWER", 1), adminToken).statusCode());
                    devCapabilities = json(send(base, "GET", "/v1/changes/capabilities", null, devToken));
                    assertTrue(values(devCapabilities.path("memberships").get(0).path("roles")).contains("VIEWER"));
                    assertFalse(values(devCapabilities.path("memberships").get(0).path("roles")).contains("DEVELOPER"));

                    CompletableFuture<HttpResponse<String>> updateA = sendAsync(base, "PUT", members,
                            member("developer", "HUMAN", "DEVELOPER", 2), adminToken);
                    CompletableFuture<HttpResponse<String>> updateB = sendAsync(base, "PUT", members,
                            member("developer", "HUMAN", "APPROVER", 2), adminToken);
                    int firstStatus = updateA.get().statusCode();
                    int secondStatus = updateB.get().statusCode();
                    assertEquals(Set.of(200, 409), Set.of(firstStatus, secondStatus));

                    assertEquals(201, send(base, "PUT", members,
                            member("release-bot", "SERVICE", "PROJECT_ADMIN", 0), adminToken).statusCode());
                    assertEquals(403, send(base, "GET", members, null, botToken).statusCode(),
                            "SERVICE is denied member administration even with PROJECT_ADMIN role");
                    String botPretendingHuman = token("key-1", first, claims(settings, now.plusSeconds(600), null,
                            "release-bot", PrincipalType.HUMAN));
                    assertEquals(403, send(base, "GET", members, null, botPretendingHuman).statusCode(),
                            "stored principal type must prevent claim-type privilege escalation");

                    assertEquals(201, send(base, "PUT", members,
                            member("second-admin", "HUMAN", "PROJECT_ADMIN", 0), adminToken).statusCode());
                    jwks.set(jwks("key-2", second));
                    String secondAdminToken = token("key-2", second, claims(settings, now.plusSeconds(600), null,
                            "second-admin", PrincipalType.HUMAN));
                    assertEquals(200, send(base, "GET", members, null, secondAdminToken).statusCode());
                    assertTrue(oidc.getRequestCount() >= 2, "new kid must refresh JWKS before authenticating");

                    assertEquals(200, send(base, "DELETE", members,
                            remove("bootstrap-admin", 1), secondAdminToken).statusCode());
                    assertEquals(409, send(base, "DELETE", members,
                            remove("second-admin", 1), secondAdminToken).statusCode(),
                            "last human project admin cannot be removed");
                    String removedAdminToken = token("key-2", second, claims(settings, now.plusSeconds(600), null,
                            "bootstrap-admin", PrincipalType.HUMAN));
                    assertEquals(403, send(base, "GET", members, null, removedAdminToken).statusCode(),
                            "member removal must take effect on the next request");
                    assertEquals(403, send(base, "GET", "/v1/changes/projects/" + otherProject + "/members",
                            null, secondAdminToken).statusCode());

                    JsonNode audit = json(send(base, "GET", members + "/audit", null, secondAdminToken));
                    assertTrue(audit.path("audit").size() >= 6);
                    assertEquals("bootstrap-admin", audit.path("audit").get(0).path("actorSubject").asText());
                    assertEquals("BOOTSTRAP", audit.path("audit").get(0).path("operation").asText());
                    HttpResponse<String> auditExport = send(base, "GET", members + "/audit/export", null, secondAdminToken);
                    assertEquals(200, auditExport.statusCode());
                    assertEquals("application/x-ndjson; charset=utf-8", auditExport.headers()
                            .firstValue("Content-Type").orElse(""));
                    assertEquals(64, auditExport.headers().firstValue("X-Content-SHA256").orElse("").length());
                    assertTrue(Long.parseLong(auditExport.headers().firstValue("X-Record-Count").orElse("0")) >= 6);
                    assertTrue(auditExport.body().contains("\"operation\":\"BOOTSTRAP\""));
                    String rotatedBotToken = token("key-2", second, claims(settings, now.plusSeconds(600), null,
                            "release-bot", PrincipalType.SERVICE));
                    assertEquals(403, send(base, "GET", members + "/audit/export", null, rotatedBotToken).statusCode());

                    assertEquals(401, send(base, "GET", members, null,
                            token("key-2", second, claims(settings, now.minusSeconds(1), null,
                                    "second-admin", PrincipalType.HUMAN))).statusCode());
                    assertEquals(401, send(base, "GET", members, null,
                            token("key-2", second, claims(settings, now.plusSeconds(60), now.plusSeconds(30),
                                    "second-admin", PrincipalType.HUMAN))).statusCode());
                    assertEquals(401, send(base, "GET", members, null,
                            token("key-2", attacker, claims(settings, now.plusSeconds(60), null,
                                    "second-admin", PrincipalType.HUMAN))).statusCode());
                    assertEquals(401, send(base, "GET", members, null,
                            token("key-2", second, claims("https://wrong.example", settings.audience(),
                                    now.plusSeconds(60), null, "second-admin", PrincipalType.HUMAN))).statusCode());
                    assertEquals(401, send(base, "GET", members, null,
                            token("key-2", second, claims(settings.issuer(), "wrong-audience",
                                    now.plusSeconds(60), null, "second-admin", PrincipalType.HUMAN))).statusCode());
                }
            }

            try (PostgresProjectMembershipDirectory reopened =
                         new PostgresProjectMembershipDirectory(jdbc, user, password)) {
                assertTrue(reopened.projectMembers(project).stream()
                        .anyMatch(member -> member.subjectId().equals("second-admin")
                                && member.roles().contains(ProjectRole.PROJECT_ADMIN)));
                assertFalse(reopened.projectMembers(project).stream()
                        .anyMatch(member -> member.subjectId().equals("bootstrap-admin")));
                assertTrue(reopened.audit(project).stream()
                        .anyMatch(event -> event.operation() == ProjectMemberAudit.Operation.REMOVE
                                && event.actorSubject().equals("second-admin")));
            }
        }
    }

    private HttpResponse<String> send(String base, String method, String path, String body, String token) throws Exception {
        return client.send(request(base, method, path, body, token), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(String base, String method, String path,
                                                               String body, String token) {
        return client.sendAsync(request(base, method, path, body, token), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(String base, String method, String path, String body, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static String member(String subject, String type, String role, long version) {
        return "{\"subjectId\":\"" + subject + "\",\"principalType\":\"" + type
                + "\",\"roles\":[\"" + role + "\"],\"expectedVersion\":" + version + "}";
    }

    private static String remove(String subject, long version) {
        return "{\"subjectId\":\"" + subject + "\",\"expectedVersion\":" + version + "}";
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return ChangeJson.MAPPER.readTree(response.body());
    }

    private static Set<String> values(JsonNode array) {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        if (array != null) array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static ObjectNode claims(OidcSettings settings, Instant exp, Instant nbf,
                                     String subject, PrincipalType type) {
        return claims(settings.issuer(), settings.audience(), exp, nbf, subject, type);
    }

    private static ObjectNode claims(String issuer, String audience, Instant exp, Instant nbf,
                                     String subject, PrincipalType type) {
        ObjectNode claims = ChangeJson.MAPPER.createObjectNode().put("iss", issuer).put("aud", audience)
                .put("exp", exp.getEpochSecond()).put("sub", subject).put("name", subject)
                .put("principal_type", type.name());
        if (nbf != null) claims.put("nbf", nbf.getEpochSecond());
        return claims;
    }

    private static String token(String kid, KeyPair key, ObjectNode claims) throws Exception {
        ObjectNode header = ChangeJson.MAPPER.createObjectNode().put("alg", "RS256").put("kid", kid);
        String encodedHeader = encode(header);
        String encodedClaims = encode(claims);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update((encodedHeader + "." + encodedClaims).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return encodedHeader + "." + encodedClaims + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String jwks(String kid, KeyPair pair) {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        ObjectNode jwk = ChangeJson.MAPPER.createObjectNode().put("kty", "RSA").put("use", "sig")
                .put("alg", "RS256").put("kid", kid)
                .put("n", integer(key.getModulus())).put("e", integer(key.getPublicExponent()));
        ObjectNode root = ChangeJson.MAPPER.createObjectNode();
        root.putArray("keys").add(jwk);
        return root.toString();
    }

    private static String integer(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(JsonNode value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ChangeJson.MAPPER.writeValueAsBytes(value));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
