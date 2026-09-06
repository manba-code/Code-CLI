package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small GitLab v4 JSON client. It never follows redirects or logs response bodies/tokens. */
final class GitLabClient {
    private final GitLabSettings settings;
    private final HttpClient http;
    private final String projectPath;

    GitLabClient(GitLabSettings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    GitLabClient(GitLabSettings settings, HttpClient http) {
        this.settings = settings;
        this.http = http;
        this.projectPath = "/api/v4/projects/" + encode(settings.projectId());
    }

    JsonNode issue(String iid) {
        if (iid == null || !iid.matches("[1-9][0-9]*")) throw new IllegalArgumentException("GitLab workItem 必须是正整数 issue IID");
        return get(projectPath + "/issues/" + iid);
    }

    JsonNode mergeRequests(String sourceBranch, String targetBranch) {
        return get(projectPath + "/merge_requests?" + form(Map.of(
                "state", "all", "source_branch", sourceBranch, "target_branch", targetBranch,
                "scope", "all", "per_page", "100")));
    }

    JsonNode createMergeRequest(String sourceBranch, String targetBranch, String title, String description) {
        return post(projectPath + "/merge_requests", Map.of("source_branch", sourceBranch,
                "target_branch", targetBranch, "title", title, "description", description));
    }

    String branchHead(String branch) {
        JsonNode response = get(projectPath + "/repository/branches/" + encode(branch));
        String head = response.path("commit").path("id").asText();
        if (head.isBlank()) throw new IllegalStateException("GitLab 分支响应缺少 commit.id");
        return head;
    }

    JsonNode statuses(String sha, String name, String ref) {
        return get(projectPath + "/repository/commits/" + encode(sha) + "/statuses?" + form(Map.of(
                "all", "true", "name", name, "ref", ref, "per_page", "100")));
    }

    JsonNode publishStatus(String sha, String state, String name, String ref, String description, String targetUrl) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("state", state); fields.put("name", name); fields.put("ref", ref);
        fields.put("description", description);
        if (targetUrl != null && !targetUrl.isBlank()) fields.put("target_url", targetUrl);
        return post(projectPath + "/statuses/" + encode(sha), fields);
    }

    private JsonNode get(String path) { return send("GET", path, null); }
    private JsonNode post(String path, Map<String, String> fields) { return send("POST", path, form(fields)); }

    private JsonNode send(String method, String path, String body) {
        URI uri = base(path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(settings.timeout())
                .header("Accept", "application/json").header("PRIVATE-TOKEN", settings.token());
        if (body == null) request.GET();
        else request.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 401 || status == 403) throw new GitLabRequestException(status, "GitLab 凭据无效或权限不足");
                if (status == 429) throw new GitLabRequestException(status, "GitLab 限流，稍后可安全重试");
                throw new GitLabRequestException(status, "GitLab 请求失败，HTTP " + status);
            }
            return ChangeJson.MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitLab 请求被中断，远程结果可能未知", e);
        } catch (IOException e) {
            throw new IllegalStateException("GitLab 请求失败，远程结果可能未知", e);
        }
    }

    private URI base(String path) {
        String root = settings.baseUrl().toString();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return URI.create(root + path);
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static final class GitLabRequestException extends IllegalStateException {
        private final int status;
        GitLabRequestException(int status, String message) { super(message); this.status = status; }
        int status() { return status; }
    }
}
