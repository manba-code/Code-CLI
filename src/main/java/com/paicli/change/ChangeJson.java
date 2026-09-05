package com.paicli.change;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** HTTP 与本地 fixture 共用的字段校验，不包含业务状态逻辑。 */
public final class ChangeJson {
    public static final ObjectMapper MAPPER = mapper();
    private ChangeJson() { }

    private static ObjectMapper mapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override public void serialize(Instant v, JsonGenerator g, SerializerProvider p) throws IOException {
                g.writeString(v.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<Instant>() {
            @Override public Instant deserialize(com.fasterxml.jackson.core.JsonParser p, DeserializationContext c) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        module.addSerializer(Path.class, new JsonSerializer<Path>() {
            @Override public void serialize(Path v, JsonGenerator g, SerializerProvider p) throws IOException {
                g.writeString(v.toString());
            }
        });
        return new ObjectMapper().registerModule(module)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public static ChangeRequest request(JsonNode body) {
        object(body, "request");
        JsonNode repository = body.path("repository");
        object(repository, "repository");
        JsonNode source = body.path("source");
        WorkItemRef ref = null;
        if (!source.isMissingNode() && !source.isNull()) {
            object(source, "source");
            List<String> labels = new ArrayList<>();
            if (source.has("labels")) {
                if (!source.path("labels").isArray()) throw new IllegalArgumentException("labels 必须是数组");
                for (JsonNode label : source.path("labels")) {
                    if (!label.isTextual()) throw new IllegalArgumentException("label 必须是字符串");
                    labels.add(label.asText());
                }
            }
            ref = new WorkItemRef(optionalText(source, "type"), optionalText(source, "externalId"),
                    optionalText(source, "url"), labels, optionalText(source, "priority"));
        }
        // Risk/route/approvals are domain outputs and cannot be supplied by a caller or LLM.
        for (String field : List.of("risk", "route", "state", "specApproval", "deliveryApproval", "run", "draftJob", "humanReview", "deliveryVerdict", "judgmentRevision")) {
            if (body.has(field)) throw new IllegalArgumentException(field + " 由 ChangeWorkflow 维护");
        }
        return new ChangeRequest(text(body, "idempotencyKey"), ref,
                new RepositoryRef(text(repository, "path"), text(repository, "baseRef")),
                text(body, "title"), text(body, "requirement"), text(body, "actorId"),
                optionalText(body, "projectContext"), optionalText(body, "referencedContext"));
    }

    public static String text(JsonNode body, String field) {
        String value = optionalText(body, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " 必填");
        return value.trim();
    }

    public static String optionalText(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode()) return "";
        if (!node.isTextual()) throw new IllegalArgumentException(field + " 必须是字符串");
        return node.asText();
    }

    public static void object(JsonNode body, String name) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException(name + " 必须是 JSON 对象");
    }
}
