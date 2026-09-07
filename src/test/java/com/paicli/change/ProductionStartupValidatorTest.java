package com.paicli.change;

import com.paicli.runtime.auth.OidcSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProductionStartupValidatorTest {
    @TempDir Path repository;

    @BeforeEach void initializeRepository() throws Exception {
        Path bare = repository.resolve("remote.git");
        Path checkout = repository.resolve("checkout");
        run(repository, "git", "init", "--bare", bare.toString());
        Files.createDirectories(checkout);
        run(checkout, "git", "init", "-b", "main");
        Files.writeString(checkout.resolve("tracked.txt"), "base\n");
        run(checkout, "git", "add", "tracked.txt");
        run(checkout, "git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--no-gpg-sign", "-m", "base");
        run(checkout, "git", "remote", "add", "origin", bare.toString());
        repository = checkout;
    }

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
        GitHubSettings github = new GitHubSettings(URI.create("http://127.0.0.1:8083"), "g", "p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        assertDoesNotThrow(() -> ProductionStartupValidator.validate(local,
                new ProductionOperationsSettings(300, 600), oidc, github));

        assertDoesNotThrow(() -> run(repository, "git", "remote", "set-url", "origin",
                "https://github.com/g/p.git"));
        GitHubSettings publicGitHub = new GitHubSettings(URI.create("https://api.github.com"), "g", "p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        assertDoesNotThrow(() -> ProductionStartupValidator.validateScm(publicGitHub));
        GitHubSettings wrongHost = new GitHubSettings(URI.create("https://api.github.com"), "g", "p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        assertDoesNotThrow(() -> run(repository, "git", "remote", "set-url", "origin",
                "https://evil.example/g/p.git"));
        assertThrows(IllegalStateException.class, () -> ProductionStartupValidator.validateScm(wrongHost));

        ProductionStorageSettings insecure = storage("jdbc:postgresql://db.example/paichange",
                "https://s3.example");
        GitLabSettings secureGitlab = new GitLabSettings(URI.create("https://gitlab.example"), "g/p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionStartupValidator.validate(insecure, new ProductionOperationsSettings(300, 600),
                        new OidcSettings("https://id.example", "aud", URI.create("https://id.example/jwks"),
                                Set.of("RS256"), Duration.ofMinutes(5), Duration.ofSeconds(2), "admin"), secureGitlab));
        assertTrue(error.getMessage().contains("sslmode=verify-full"));

        ProductionStorageSettings secureStorage = storage(
                "jdbc:postgresql://db.example/paichange?sslmode=verify-full", "https://s3.example");
        GitHubSettings mismatched = new GitHubSettings(URI.create("https://api.github.com"), "g", "p", "token",
                repository, "main", "origin", Duration.ofSeconds(2));
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> ProductionStartupValidator.validate(secureStorage, new ProductionOperationsSettings(300, 600),
                        new OidcSettings("https://id.example", "aud", URI.create("https://id.example/jwks"),
                                Set.of("RS256"), Duration.ofMinutes(5), Duration.ofSeconds(2), "admin"), mismatched));
        assertTrue(mismatch.getMessage().contains("仓库身份"));
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

    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
