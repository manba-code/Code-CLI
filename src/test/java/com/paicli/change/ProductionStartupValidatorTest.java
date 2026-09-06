package com.paicli.change;

import com.paicli.runtime.auth.OidcSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProductionStartupValidatorTest {
    @TempDir Path repository;

    @Test void loopbackAcceptanceAndRemoteTlsRequirementsAreExplicit() {
        ProductionStorageSettings local = storage("jdbc:postgresql://127.0.0.1:5432/paichange",
                "http://127.0.0.1:9000");
        OidcSettings oidc = new OidcSettings("http://127.0.0.1:8081", "aud",
                URI.create("http://127.0.0.1:8081/jwks"), Set.of("RS256"), Duration.ofMinutes(5),
                Duration.ofSeconds(2), "admin");
        GitLabSettings gitlab = new GitLabSettings(URI.create("http://127.0.0.1:8082"), "g/p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        assertDoesNotThrow(() -> ProductionStartupValidator.validate(local,
                new ProductionOperationsSettings(300, 600), oidc, gitlab));

        ProductionStorageSettings insecure = storage("jdbc:postgresql://db.example/paichange",
                "https://s3.example");
        GitLabSettings secureGitlab = new GitLabSettings(URI.create("https://gitlab.example"), "g/p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionStartupValidator.validate(insecure, new ProductionOperationsSettings(300, 600),
                        new OidcSettings("https://id.example", "aud", URI.create("https://id.example/jwks"),
                                Set.of("RS256"), Duration.ofMinutes(5), Duration.ofSeconds(2), "admin"), secureGitlab));
        assertTrue(error.getMessage().contains("sslmode=verify-full"));
    }

    @Test void recoveryObjectiveRangesAndQueueLeaseAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionOperationsSettings(1, 600));
        ProductionStorageSettings invalidQueue = new ProductionStorageSettings(
                "jdbc:postgresql://127.0.0.1/db", "u", "p", "http://127.0.0.1:9000", "bucket", "us-east-1",
                "a", "s", 1, 5_000, 2_000);
        OidcSettings oidc = new OidcSettings("http://127.0.0.1:8081", "aud",
                URI.create("http://127.0.0.1:8081/jwks"), Set.of("RS256"), Duration.ofMinutes(5),
                Duration.ofSeconds(2), "admin");
        GitLabSettings gitlab = new GitLabSettings(URI.create("http://127.0.0.1:8082"), "g/p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        assertThrows(IllegalStateException.class, () -> ProductionStartupValidator.validate(invalidQueue,
                new ProductionOperationsSettings(300, 600), oidc, gitlab));
    }

    private static ProductionStorageSettings storage(String jdbc, String s3) {
        return new ProductionStorageSettings(jdbc, "user", "password", s3, "bucket", "us-east-1",
                "access", "secret", 2, 60_000, 250);
    }
}
