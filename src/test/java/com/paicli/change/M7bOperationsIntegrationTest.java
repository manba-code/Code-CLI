package com.paicli.change;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.auth.OidcSettings;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;
import com.paicli.runtime.task.PostgresWorkerJobScheduler;
import com.paicli.runtime.task.WorkerJob;
import com.paicli.runtime.task.WorkerJobLifecycleListener;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in M7b local fake OIDC/GitLab plus real PostgreSQL/MinIO fault and restart closure. */
class M7bOperationsIntegrationTest {
    @TempDir Path root;

    @Test void operationsFailClosedAndDurableStateRecoversWithoutDuplicateDelivery() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("paichange.m7b.integration.enabled"),
                "run with -Ppaichange-m7b-it or docker/run-paichange-m7b-tests.sh");
        String jdbc = property("paichange.m7b.test.jdbc", "jdbc:postgresql://127.0.0.1:55434/paichange");
        String user = property("paichange.m7b.test.user", "paichange");
        String password = property("paichange.m7b.test.password", "paichange-test-only");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        ChangeTask task = task("change_" + suffix.substring(0, 12), "m7b-" + suffix);
        S3ObjectStorage s3 = s3(property("paichange.m7b.test.s3.bucket", "paichange-evidence"));

        try (PostgresChangeStore store = new PostgresChangeStore(jdbc, user, password)) {
            store.create(task, new ChangeEvent(0, task.id(), "change.created", "HUMAN", "m7b-admin", null,
                    task.state(), "{}", task.createdAt()));
        }
        Path source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve("result.json"), "{\"runId\":\"run-" + suffix + "\"}");
        Files.writeString(source.resolve("change.diff"), "m7b backup recovery\n");
        try (PostgresEvidenceStore evidence = new PostgresEvidenceStore(jdbc, user, password, s3, root.resolve("cache"))) {
            evidence.capture(task.id(), "run-" + suffix, source);
        }
        try (PostgresProjectMembershipDirectory members = new PostgresProjectMembershipDirectory(jdbc, user, password)) {
            Principal admin = new Principal("m7b-admin", "M7b Admin", PrincipalType.HUMAN, "test",
                    Instant.now().plusSeconds(600), false);
            String project = "project-" + suffix;
            members.bootstrap(project, admin);
            members.put(project, "m7b-reviewer", PrincipalType.HUMAN, Set.of(ProjectRole.APPROVER), 0, admin);
            assertEquals(2, members.audit(project).size());
        }

        String expiredId = "job_" + suffix.substring(12, 24);
        try (var connection = DriverManager.getConnection(jdbc, user, password);
             var insert = connection.prepareStatement("""
                     INSERT INTO worker_jobs(id, job_type, reference_id, status, recovery_count, lease_owner,
                         lease_expires_at, created_at, started_at, updated_at)
                     VALUES (?, 'm7b.recovery', ?, 'RUNNING', 0, 'lost-worker', ?, ?, ?, ?)
                     """)) {
            String past = Instant.now().minusSeconds(30).toString();
            insert.setString(1, expiredId); insert.setString(2, "recovery-" + suffix);
            insert.setString(3, past); insert.setString(4, past); insert.setString(5, past); insert.setString(6, past);
            insert.executeUpdate();
        }
        CountDownLatch recovered = new CountDownLatch(1);
        try (PostgresWorkerJobScheduler queue = new PostgresWorkerJobScheduler(jdbc, user, password, 1, 2_000, 25)) {
            queue.registerWorkerJobHandler("m7b.recovery", job -> { recovered.countDown(); return "ok"; },
                    WorkerJobLifecycleListener.NO_OP);
            WorkerJob duplicateA = queue.enqueueWorkerJob("m7b.duplicate", task.id().value());
            WorkerJob duplicateB = queue.enqueueWorkerJob("m7b.duplicate", task.id().value());
            assertEquals(duplicateA.id(), duplicateB.id());
            queue.registerWorkerJobHandler("m7b.duplicate", job -> "done", WorkerJobLifecycleListener.NO_OP);
            queue.start();
            assertTrue(recovered.await(10, TimeUnit.SECONDS));
            await(queue, expiredId, "COMPLETED");
            assertTrue(queue.metrics().recoveries() >= 1);
        }

        AtomicBoolean jwksUp = new AtomicBoolean(true);
        AtomicBoolean gitlabUp = new AtomicBoolean(true);
        KeyPair key = keyPair();
        try (MockWebServer oidc = new MockWebServer(); MockWebServer gitlab = new MockWebServer()) {
            oidc.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return jwksUp.get() ? json(jwks("m7b-key", key)) : new MockResponse().setResponseCode(503);
                }
            });
            gitlab.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return gitlabUp.get() ? json("{\"commit\":{\"id\":\"base-head\"}}")
                            : new MockResponse().setResponseCode(503);
                }
            });
            oidc.start(); gitlab.start();
            OidcSettings oidcSettings = new OidcSettings("https://fake-idp.example", "paichange-api",
                    oidc.url("/jwks").uri(), Set.of("RS256"), Duration.ofMinutes(5), Duration.ofSeconds(2), "m7b-admin");
            GitLabSettings gitlabSettings = new GitLabSettings(gitlab.url("/").uri(), "group/project", "test-token",
                    Files.createDirectories(root.resolve("repo")), "main", "origin", Duration.ofSeconds(2));
            AtomicBoolean s3Up = new AtomicBoolean(true);
            FaultObjectStorage objectStorage = new FaultObjectStorage(s3(property("paichange.m7b.test.s3.bucket", "paichange-evidence")), s3Up);
            try (PostgresChangeStore store = new PostgresChangeStore(jdbc, user, password);
                 PostgresWorkerJobScheduler jobs = new PostgresWorkerJobScheduler(jdbc, user, password, 1, 2_000, 25);
                 PostgresEvidenceStore evidence = new PostgresEvidenceStore(jdbc, user, password, objectStorage, root.resolve("health-cache"));
                 GitLabScmAdapter scm = new GitLabScmAdapter(jdbc, user, password, gitlabSettings);
                 RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"));
                 RuntimeApiServer api = new RuntimeApiServer(threads, value -> value, 0,
                         oidcSettings.principalAdapter(), null,
                         new ChangeOperations(store, jobs, evidence, scm, new ProductionOperationsSettings(300, 600)))) {
                api.start();
                String base = "http://127.0.0.1:" + api.port();
                assertEquals(200, get(base + "/health/ready").statusCode());
                jwksUp.set(false);
                assertDown(base, "identity_jwks");
                jwksUp.set(true);
                gitlabUp.set(false);
                assertDown(base, "scm");
                gitlabUp.set(true);
                s3Up.set(false);
                assertDown(base, "evidence_store");
                s3Up.set(true);
                store.close();
                assertDown(base, "database");
                assertTrue(storeListState(jdbc, user, password, task.id()).isPresent(),
                        "readiness failures must not mutate durable task state");
            }

            // A fresh process view can reopen all durable seams and re-verify every remote Evidence object.
            try (PostgresChangeStore reopened = new PostgresChangeStore(jdbc, user, password);
                 PostgresEvidenceStore evidence = new PostgresEvidenceStore(jdbc, user, password,
                         s3(property("paichange.m7b.test.s3.bucket", "paichange-evidence")), root.resolve("restart-cache"))) {
                assertEquals(ChangeState.CREATED, reopened.find(task.id()).orElseThrow().state());
                assertTrue(evidence.verifyAll().archives() >= 1);
            }
        }
    }

    private void assertDown(String base, String component) throws Exception {
        HttpResponse<String> response = get(base + "/health/ready");
        assertEquals(503, response.statusCode(), response.body());
        assertEquals("DOWN", ChangeJson.MAPPER.readTree(response.body()).path("components").path(component).path("status").asText());
    }

    private static Optional<ChangeState> storeListState(String jdbc, String user, String password, ChangeTaskId id) throws Exception {
        try (PostgresChangeStore store = new PostgresChangeStore(jdbc, user, password)) {
            return store.find(id).map(ChangeTask::state);
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private static void await(PostgresWorkerJobScheduler queue, String id, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (queue.find(id).map(job -> job.status().name()).orElse("").equals(status)) return;
            Thread.sleep(25);
        }
        fail("job did not reach " + status);
    }

    private S3ObjectStorage s3(String bucket) {
        return new S3ObjectStorage(property("paichange.m7b.test.s3.endpoint", "http://127.0.0.1:59002"), bucket,
                "us-east-1", property("paichange.m7b.test.s3.access", "paichange-test"),
                property("paichange.m7b.test.s3.secret", "paichange-test-secret"));
    }

    private ChangeTask task(String id, String key) {
        Instant now = Instant.now();
        return new ChangeTask(new ChangeTaskId(id), key, 0, ChangeState.CREATED,
                new WorkItemRef("m7b", "OPS-1", ""), new RepositoryRef(root.resolve("repo").toString(), "main"),
                "M7b operations", "verify recovery and fail closed behavior", "m7b-admin", "", "", null, null,
                null, null, null, null, null, null, null, now, now);
    }

    private static String jwks(String kid, KeyPair pair) {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        ObjectNode jwk = ChangeJson.MAPPER.createObjectNode().put("kty", "RSA").put("use", "sig")
                .put("alg", "RS256").put("kid", kid).put("n", integer(key.getModulus())).put("e", integer(key.getPublicExponent()));
        ObjectNode root = ChangeJson.MAPPER.createObjectNode(); root.putArray("keys").add(jwk); return root.toString();
    }
    private static String integer(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static KeyPair keyPair() throws Exception { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
    private static String property(String name, String fallback) { return System.getProperty(name, fallback); }

    private record FaultObjectStorage(ObjectStorage delegate, AtomicBoolean up) implements ObjectStorage {
        private void available() throws java.io.IOException { if (!up.get()) throw new java.io.IOException("injected S3 outage token=hidden"); }
        @Override public void putIfAbsent(String key, byte[] content, String sha256) throws java.io.IOException { available(); delegate.putIfAbsent(key, content, sha256); }
        @Override public byte[] get(String key, long maxBytes) throws java.io.IOException { available(); return delegate.get(key, maxBytes); }
        @Override public Optional<Metadata> metadata(String key) throws java.io.IOException { available(); return delegate.metadata(key); }
        @Override public List<String> list(String prefix) throws java.io.IOException { available(); return delegate.list(prefix); }
        @Override public void checkHealth() { if (!up.get()) throw new IllegalStateException("injected S3 outage secret=hidden"); delegate.checkHealth(); }
        @Override public void close() { delegate.close(); }
    }
}
