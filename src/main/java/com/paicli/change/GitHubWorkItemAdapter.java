package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Imports one issue from the single configured GitHub repository. */
public final class GitHubWorkItemAdapter implements WorkItemAdapter {
    private final GitHubSettings settings;
    private final GitHubClient client;
    private final ChangeWorkflow workflow;

    public GitHubWorkItemAdapter(GitHubSettings settings, ChangeWorkflow workflow) {
        this(settings, new GitHubClient(settings), workflow);
    }

    GitHubWorkItemAdapter(GitHubSettings settings, GitHubClient client, ChangeWorkflow workflow) {
        this.settings = Objects.requireNonNull(settings);
        this.client = Objects.requireNonNull(client);
        this.workflow = Objects.requireNonNull(workflow);
    }

    @Override
    public ChangeTaskId submit(String reference, String actorId, String actorType) throws IOException {
        JsonNode issue = client.issue(reference);
        String number = issue.path("number").asText();
        String title = issue.path("title").asText().trim();
        String description = issue.path("body").asText().trim();
        if (!number.equals(reference) || title.isBlank() || issue.has("pull_request")) {
            throw new IllegalStateException("GitHub Issue 响应缺少 number/title，或引用实际指向 Pull Request");
        }
        if (description.isBlank()) description = title;
        List<String> labels = new ArrayList<>();
        if (issue.path("labels").isArray()) {
            for (JsonNode label : issue.path("labels")) {
                String name = label.isTextual() ? label.asText() : label.path("name").asText();
                if (!name.isBlank()) labels.add(name);
            }
        }
        String priority = labels.stream().filter(label -> label.toLowerCase(Locale.ROOT).startsWith("priority::"))
                .map(label -> label.substring(label.indexOf("::") + 2)).findFirst().orElse("");
        String requester = actorId == null || actorId.isBlank() ? "github-import" : actorId.trim();
        WorkItemRef source = new WorkItemRef("github_issue", settings.slug() + "#" + number,
                issue.path("html_url").asText(), labels, priority);
        ChangeRequest request = new ChangeRequest("github:" + settings.slug() + ":issue:" + number,
                source, new RepositoryRef(settings.repository().toString(), settings.baseRef()), title, description,
                requester, actorType == null || actorType.isBlank() ? "LEGACY" : actorType,
                "Imported from configured GitHub repository " + settings.slug(), "");
        return workflow.submit(request);
    }

    @Override public String type() { return "GITHUB"; }

    @Override public java.util.Optional<RepositoryRef> repository() {
        return java.util.Optional.of(new RepositoryRef(settings.repository().toString(), settings.baseRef()));
    }
}
