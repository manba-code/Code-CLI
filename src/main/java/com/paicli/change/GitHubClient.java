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

/** Small GitHub REST client. It never follows redirects or exposes response bodies/tokens in errors. */
final class GitHubClient {
    private final GitHubSettings settings;
    private final HttpClient http;
    private final String repositoryPath;

    GitHubClient(GitHubSettings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    GitHubClient(GitHubSettings settings, HttpClient http) {
        this.settings = settings;
        this.http = http;
        this.repositoryPath = "/repos/" + encode(settings.owner()) + "/" + encode(settings.repositoryName());
    }

    JsonNode issue(String number) {
        if (number == null || !number.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("GitHub workItem 必须是正整数 Issue number");
        }
        return get(repositoryPath + "/issues/" + number);
    }

    JsonNode pullRequests(String sourceBranch, String targetBranch) {
        return get(repositoryPath + "/pulls?" + query(Map.of("state", "all",
                "head", settings.owner() + ":" + sourceBranch, "base", targetBranch, "per_page", "100")));
    }

    JsonNode createPullRequest(String sourceBranch, String targetBranch, String title, String body) {
        return post(repositoryPath + "/pulls", Map.of("head", sourceBranch, "base", targetBranch,
                "title", title, "body", body));
    }

    String branchHead(String branch) {
        JsonNode response = get(repositoryPath + "/git/ref/heads/" + encode(branch));
        String head = response.path("object").path("sha").asText();
        if (head.isBlank()) throw new IllegalStateException("GitHub ref 响应缺少 object.sha");
        return head;
    }

    JsonNode statuses(String sha) {
        return get(repositoryPath + "/commits/" + encode(sha) + "/statuses?per_page=100");
    }

    JsonNode publishStatus(String sha, String state, String context, String description, String targetUrl) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("state", state);
        fields.put("context", context);
        fields.put("description", description);
        if (targetUrl != null && !targetUrl.isBlank()) fields.put("target_url", targetUrl);
        return post(repositoryPath + "/statuses/" + encode(sha), fields);
    }

    private JsonNode get(String path) { return send("GET", path, null); }

    private JsonNode post(String path, Map<String, String> fields) {
        try {
            return send("POST", path, ChangeJson.MAPPER.writeValueAsString(fields));
        } catch (IOException e) {
            throw new IllegalStateException("GitHub 请求编码失败", e);
        }
    }

    private JsonNode send(String method, String path, String body) {
        URI uri = base(path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(settings.timeout())
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + settings.token())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "PaiChange");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 401 || status == 403) {
                    throw new GitHubRequestException(status, "GitHub 凭据无效或权限不足");
                }
                if (status == 429) throw new GitHubRequestException(status, "GitHub 限流，稍后可安全重试");
                throw new GitHubRequestException(status, "GitHub 请求失败，HTTP " + status);
            }
            return ChangeJson.MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub 请求被中断，远程结果可能未知", e);
        } catch (IOException e) {
            throw new IllegalStateException("GitHub 请求失败，远程结果可能未知", e);
        }
    }

    private URI base(String path) {
        String root = settings.baseUrl().toString();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return URI.create(root + path);
    }

    static String query(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static final class GitHubRequestException extends IllegalStateException {
        private final int status;
        GitHubRequestException(int status, String message) { super(message); this.status = status; }
        int status() { return status; }
    }
}
