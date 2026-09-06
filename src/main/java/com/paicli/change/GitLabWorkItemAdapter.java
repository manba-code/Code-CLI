package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Imports one issue from the single configured GitLab project. */
public final class GitLabWorkItemAdapter implements WorkItemAdapter {
    private final GitLabSettings settings;
    private final GitLabClient client;
    private final ChangeWorkflow workflow;

    public GitLabWorkItemAdapter(GitLabSettings settings, ChangeWorkflow workflow) {
        this(settings, new GitLabClient(settings), workflow);
    }

    GitLabWorkItemAdapter(GitLabSettings settings, GitLabClient client, ChangeWorkflow workflow) {
        this.settings = Objects.requireNonNull(settings);
        this.client = Objects.requireNonNull(client);
        this.workflow = Objects.requireNonNull(workflow);
    }

    @Override
    public ChangeTaskId submit(String reference, String actorId, String actorType) throws IOException {
        JsonNode issue = client.issue(reference);
        String iid = issue.path("iid").asText();
        String title = issue.path("title").asText().trim();
        String description = issue.path("description").asText().trim();
        if (!iid.equals(reference) || title.isBlank()) {
            throw new IllegalStateException("GitLab issue 响应缺少 iid 或 title");
        }
        if (description.isBlank()) description = title;
        List<String> labels = new ArrayList<>();
        if (issue.path("labels").isArray()) {
            for (JsonNode label : issue.path("labels")) if (label.isTextual()) labels.add(label.asText());
        }
        String priority = labels.stream().filter(label -> label.toLowerCase(Locale.ROOT).startsWith("priority::"))
                .map(label -> label.substring(label.indexOf("::") + 2)).findFirst().orElse("");
        String requester = actorId == null || actorId.isBlank() ? "gitlab-import" : actorId.trim();
        WorkItemRef source = new WorkItemRef("gitlab_issue", settings.projectId() + "#" + iid,
                issue.path("web_url").asText(), labels, priority);
        ChangeRequest request = new ChangeRequest("gitlab:" + settings.projectId() + ":issue:" + iid,
                source, new RepositoryRef(settings.repository().toString(), settings.baseRef()), title, description,
                requester, actorType == null || actorType.isBlank() ? "LEGACY" : actorType,
                "Imported from configured GitLab project " + settings.projectId(), "");
        return workflow.submit(request);
    }

    @Override public String type() { return "GITLAB"; }

    @Override public java.util.Optional<RepositoryRef> repository() {
        return java.util.Optional.of(new RepositoryRef(settings.repository().toString(), settings.baseRef()));
    }
}
