package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 从固定本地 fixture 目录读取模拟工单，不访问外部工单系统。 */
public final class MockWorkItemAdapter {
    private final Path fixtures;
    private final ChangeWorkflow workflow;

    public MockWorkItemAdapter(Path fixtures, ChangeWorkflow workflow) {
        this.fixtures = Objects.requireNonNull(fixtures).toAbsolutePath().normalize();
        this.workflow = Objects.requireNonNull(workflow);
    }

    public ChangeTaskId submit(String fixture) throws IOException {
        if (fixture == null || !fixture.matches("[A-Za-z0-9_-]+\\.json")) {
            throw new IllegalArgumentException("fixture 必须是本地 JSON 文件名");
        }
        Path root = fixtures.toRealPath();
        Path file = root.resolve(fixture).toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("fixture 路径越界");
        }
        JsonNode input = ChangeJson.MAPPER.readTree(Files.readString(file));
        ChangeJson.object(input, "fixture");
        ObjectNode body = ChangeJson.MAPPER.createObjectNode();
        body.put("idempotencyKey", ChangeJson.text(input, "idempotencyKey"));
        ObjectNode source = body.putObject("source");
        source.put("type", ChangeJson.text(input, "sourceType"));
        source.put("externalId", ChangeJson.text(input, "externalId"));
        source.put("url", ChangeJson.optionalText(input, "sourceUrl"));
        source.put("priority", ChangeJson.optionalText(input, "priority"));
        if (input.has("labels")) source.set("labels", input.get("labels"));
        body.set("repository", input.path("repository"));
        body.put("title", ChangeJson.text(input, "title"));
        body.put("requirement", ChangeJson.text(input, "description"));
        body.put("actorId", ChangeJson.text(input, "requester"));
        return workflow.submit(ChangeJson.request(body));
    }
}
