package com.paicli.change;

import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.auth.AuthenticationRequest;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalAdapter;
import com.paicli.runtime.task.DurableTaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChangeOperationsTest {
    @TempDir Path root;

    @Test void readinessAndMetricsExposeOnlyAggregateFailClosedState() throws Exception {
        Path database = root.resolve("operations.db");
        try (SqliteChangeStore persistence = new SqliteChangeStore(database);
             DurableTaskManager jobs = new DurableTaskManager(database, value -> value, 1);
             TrustedEvidenceStore trusted = new TrustedEvidenceStore(database, root.resolve("evidence"));
             MockScmAdapter scm = new MockScmAdapter(database)) {
            FaultEvidenceStore evidence = new FaultEvidenceStore(trusted);
            PrincipalAdapter identity = new PrincipalAdapter() {
                @Override public Principal authenticate(AuthenticationRequest request) { throw new UnsupportedOperationException(); }
                @Override public String mode() { return "TEST"; }
            };
            ChangeOperations operations = new ChangeOperations(persistence, jobs, evidence, scm,
                    new ProductionOperationsSettings(300, 600));
            assertEquals("UP", operations.readiness(identity).status());
            String metrics = operations.prometheus(identity);
            assertTrue(metrics.contains("paichange_worker_jobs_enqueued 0"));
            assertTrue(metrics.contains("paichange_declared_rpo_seconds 300"));

            evidence.fail = true;
            ChangeOperations.HealthSnapshot down = operations.readiness(identity);
            assertEquals("DOWN", down.status());
            assertEquals("dependency_unavailable", down.components().get("evidence_store").code());
            assertFalse(down.toString().contains("hunter2"));
            assertFalse(down.toString().contains("abc.def.ghi"));
        }
    }

    @Test void runtimePublishesUnauthenticatedLocalHealthAndPrometheusEndpoints() throws Exception {
        Path database = root.resolve("server-operations.db");
        try (SqliteChangeStore persistence = new SqliteChangeStore(database);
             DurableTaskManager jobs = new DurableTaskManager(database, value -> value, 1);
             TrustedEvidenceStore evidence = new TrustedEvidenceStore(database, root.resolve("server-evidence"));
             MockScmAdapter scm = new MockScmAdapter(database);
             RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("threads.db"));
             RuntimeApiServer server = new RuntimeApiServer(threads, value -> value, 0, "secret", null,
                     new ChangeOperations(persistence, jobs, evidence, scm))) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> live = client.send(HttpRequest.newBuilder(URI.create(base + "/health/live")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> ready = client.send(HttpRequest.newBuilder(URI.create(base + "/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, live.statusCode());
            assertEquals(200, ready.statusCode());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("paichange_readiness 1"));
            assertFalse(metrics.body().toLowerCase().contains("secret"));
        }
    }

    private static final class FaultEvidenceStore implements EvidenceStore {
        private final EvidenceStore delegate;
        private boolean fail;
        private FaultEvidenceStore(EvidenceStore delegate) { this.delegate = delegate; }
        @Override public Path root() { return delegate.root(); }
        @Override public String manifestSha256(ChangeTaskId changeId, String runId) { return delegate.manifestSha256(changeId, runId); }
        @Override public Capture capture(ChangeTaskId changeId, String runId, Path source) throws Exception { return delegate.capture(changeId, runId, source); }
        @Override public void verify(ChangeTaskId changeId, String runId, Path expectedPath) throws java.io.IOException { delegate.verify(changeId, runId, expectedPath); }
        @Override public void discardSource(Path source) { delegate.discardSource(source); }
        @Override public void checkHealth() {
            if (fail) throw new IllegalStateException("password=hunter2 Bearer abc.def.ghi");
            delegate.checkHealth();
        }
        @Override public void close() { }
    }

    @Test void logRedactorCoversCommonCredentialShapes() {
        String redacted = SensitiveValueRedactor.redact(
                "Authorization=Bearer abc.def token=qwerty password:hello https://user:pass@example.test/path");
        assertFalse(redacted.contains("abc.def"));
        assertFalse(redacted.contains("qwerty"));
        assertFalse(redacted.contains("hello"));
        assertFalse(redacted.contains("user:pass"));
        assertTrue(redacted.contains("<redacted>"));
    }
}
