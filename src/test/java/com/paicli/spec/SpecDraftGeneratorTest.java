package com.paicli.spec;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecDraftGeneratorTest {

    @Test
    void generatesValidatedDraftWithoutToolsAndIncludesAvailableContext() throws Exception {
        RecordingClient client = new RecordingClient(validDocument());
        SpecDraftGenerator generator = new SpecDraftGenerator(
                client,
                new ChangeSpecCodec(),
                "CHANGE-TEST-001");

        ChangeSpecDocument document = generator.generate(
                "修复登录超时重试",
                "项目使用 Maven，测试命令为 mvn test",
                "<file path=\"src/Auth.java\">class Auth {}</file>");

        assertEquals("CHANGE-TEST-001", document.spec().id());
        assertEquals(1, client.requests.size());
        assertTrue(client.requests.get(0).tools().isEmpty());
        String userPrompt = client.requests.get(0).messages().get(1).content();
        assertTrue(userPrompt.contains("修复登录超时重试"));
        assertTrue(userPrompt.contains("项目使用 Maven"));
        assertTrue(userPrompt.contains("src/Auth.java"));
    }

    @Test
    void retriesOnceWithValidationErrors() throws Exception {
        RecordingClient client = new RecordingClient(
                "---\nschema: wrong\n---",
                validDocument());
        SpecDraftGenerator generator = new SpecDraftGenerator(
                client,
                new ChangeSpecCodec(),
                "CHANGE-TEST-001");

        ChangeSpecDocument document = generator.generate("修复问题", "", "");

        assertEquals("修复登录超时重试", document.spec().title());
        assertEquals(2, client.requests.size());
        List<LlmClient.Message> retryMessages = client.requests.get(1).messages();
        assertTrue(retryMessages.get(retryMessages.size() - 1).content().contains("schema 必须是"));
    }

    @Test
    void stopsAfterOneFailedRegeneration() {
        RecordingClient client = new RecordingClient(
                "---\nschema: wrong\n---",
                "仍然不是 ChangeSpec");
        SpecDraftGenerator generator = new SpecDraftGenerator(
                client,
                new ChangeSpecCodec(),
                "CHANGE-TEST-001");

        assertThrows(ChangeSpecValidationException.class,
                () -> generator.generate("修复问题", "", ""));
        assertEquals(2, client.requests.size());
    }

    private static String validDocument() {
        return """
                ---
                schema: paicli/change-spec/v1
                id: CHANGE-TEST-001
                revision: 1
                title: 修复登录超时重试
                intent:
                  goal: 登录超时后最多重试三次
                  non_goals:
                    - 不更换 HTTP Client
                scope:
                  mode: open
                  include: []
                  exclude: []
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: 登录超时后最多重试三次
                    oracle:
                      type: human
                      verifiers: []
                  - id: AC-SCOPE
                    kind: scope
                    statement: 修改不得超出声明的 Scope
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-SCOPE
                    type: path_scope
                ---

                # 背景

                修复无限重试。
                """;
    }

    private static final class RecordingClient implements LlmClient {
        private final ArrayDeque<ChatResponse> responses = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private RecordingClient(String... responses) {
            for (String response : responses) {
                this.responses.add(new ChatResponse("assistant", response, List.of(), 10, 10));
            }
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            requests.add(new Request(List.copyOf(messages), tools == null ? List.of() : List.copyOf(tools)));
            if (responses.isEmpty()) {
                throw new IOException("没有测试响应");
            }
            return responses.removeFirst();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }

    private record Request(List<LlmClient.Message> messages, List<LlmClient.Tool> tools) {
    }
}
