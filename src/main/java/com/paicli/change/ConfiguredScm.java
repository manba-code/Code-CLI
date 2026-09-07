package com.paicli.change;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Single composition root for Mock/GitLab/GitHub adapter pairing and provider settings. */
final class ConfiguredScm {
    enum Provider { MOCK, GITLAB, GITHUB }

    record Configuration(Provider provider, RemoteScmSettings remote) {
        Configuration {
            Objects.requireNonNull(provider);
            if ((provider == Provider.MOCK) != (remote == null)) {
                throw new IllegalArgumentException("SCM provider 与远程配置不匹配");
            }
        }
    }

    record Adapters(ScmAdapter scm, WorkItemAdapter workItems, Provider provider) {
        Adapters {
            Objects.requireNonNull(scm);
            Objects.requireNonNull(workItems);
            Objects.requireNonNull(provider);
            if (!scm.type().equals(workItems.type()) || !scm.type().equals(provider.name())) {
                throw new IllegalStateException("SCM 与 WorkItem adapter 必须来自同一 provider");
            }
        }
    }

    private ConfiguredScm() { }

    static Configuration load(boolean offlineDemo, boolean production) {
        if (offlineDemo) return new Configuration(Provider.MOCK, null);
        String configured = System.getProperty("paichange.scm");
        if (configured == null || configured.isBlank()) configured = System.getenv("PAICHANGE_SCM");
        configured = configured == null || configured.isBlank() ? "mock" : configured.trim().toLowerCase(Locale.ROOT);
        Provider provider = switch (configured) {
            case "mock" -> Provider.MOCK;
            case "gitlab" -> Provider.GITLAB;
            case "github" -> Provider.GITHUB;
            default -> throw new IllegalStateException("PAICHANGE_SCM 只允许 mock、gitlab 或 github 严格单选");
        };
        if (production && provider == Provider.MOCK) {
            throw new IllegalStateException("生产 PostgreSQL 模式要求 PAICHANGE_SCM=gitlab 或 github");
        }
        RemoteScmSettings settings = switch (provider) {
            case MOCK -> null;
            case GITLAB -> GitLabSettings.fromProcess();
            case GITHUB -> GitHubSettings.fromProcess();
        };
        if (settings != null && !production) ProductionStartupValidator.validateScm(settings);
        return new Configuration(provider, settings);
    }

    static Adapters assemble(Configuration configuration, Path database, Path fixtures,
                             DefaultChangeWorkflow workflow, ProductionStorageSettings production) throws Exception {
        return switch (configuration.provider()) {
            case MOCK -> new Adapters(new MockScmAdapter(database), new MockWorkItemAdapter(fixtures, workflow),
                    Provider.MOCK);
            case GITLAB -> {
                GitLabSettings settings = (GitLabSettings) configuration.remote();
                ScmAdapter scm = production == null ? new GitLabScmAdapter(database, settings)
                        : new GitLabScmAdapter(production.jdbcUrl(), production.user(), production.password(), settings);
                yield new Adapters(scm, new GitLabWorkItemAdapter(settings, workflow), Provider.GITLAB);
            }
            case GITHUB -> {
                GitHubSettings settings = (GitHubSettings) configuration.remote();
                ScmAdapter scm = production == null ? new GitHubScmAdapter(database, settings)
                        : new GitHubScmAdapter(production.jdbcUrl(), production.user(), production.password(), settings);
                yield new Adapters(scm, new GitHubWorkItemAdapter(settings, workflow), Provider.GITHUB);
            }
        };
    }
}
