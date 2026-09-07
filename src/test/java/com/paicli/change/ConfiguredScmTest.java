package com.paicli.change;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

class ConfiguredScmTest {
    @AfterEach void clear() { System.clearProperty("paichange.scm"); }

    @Test void defaultsToMockAndRejectsUnknownOrProductionMock() {
        System.clearProperty("paichange.scm");
        assertEquals(ConfiguredScm.Provider.MOCK, ConfiguredScm.load(false, false).provider());
        assertThrows(IllegalStateException.class, () -> ConfiguredScm.load(false, true));
        System.setProperty("paichange.scm", "github,gitlab");
        assertThrows(IllegalStateException.class, () -> ConfiguredScm.load(false, false));
    }

    @Test void offlineDemoAlwaysUsesMockWithoutReadingRemoteSettings() {
        System.setProperty("paichange.scm", "github");
        assertEquals(ConfiguredScm.Provider.MOCK, ConfiguredScm.load(true, true).provider());
    }

    @Test void providerSettingsNeverRenderTokens() {
        GitHubSettings github = new GitHubSettings(URI.create("https://api.github.com"), "o", "r",
                "github-secret", Path.of("."), "main", "origin", Duration.ofSeconds(1));
        GitLabSettings gitlab = new GitLabSettings(URI.create("https://gitlab.example"), "o/r",
                "gitlab-secret", Path.of("."), "main", "origin", Duration.ofSeconds(1));
        assertFalse(github.toString().contains("github-secret"));
        assertFalse(gitlab.toString().contains("gitlab-secret"));
    }
}
