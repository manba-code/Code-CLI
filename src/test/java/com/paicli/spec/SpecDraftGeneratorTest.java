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

        SpecDraftSession.DraftGeneration generation = generator.generateWithMetrics(
                "修复登录超时重试",
                "项目使用 Maven，测试命令为 mvn test",
                "<file path=\"src/Auth.java\">class Auth {}</file>");
        ChangeSpecDocument document = generation.document();

        assertEquals("CHANGE-TEST-001", document.spec().id());
        assertEquals(1, client.requests.size());
        assertTrue(client.requests.get(0).tools().isEmpty());
        String userPrompt = client.requests.get(0).messages().get(1).content();
        assertTrue(userPrompt.contains("修复登录超时重试"));
        assertTrue(userPrompt.contains("项目使用 Maven"));
        assertTrue(userPrompt.contains("src/Auth.java"));
        assertEquals(1, generation.llmUsage().calls());
        assertEquals(10, generation.llmUsage().inputTokens());
        assertEquals(10, generation.llmUsage().outputTokens());
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
        assertTrue(client.requests.get(0).messages().get(0).content().contains(
                "path_scope 只能证明修改范围，不能单独证明 behavior"));
        assertTrue(retryMessages.get(retryMessages.size() - 1).content().contains(
                "非 scope 的 deterministic Criterion 必须至少引用一个 command Verifier"));
    }

    @Test
    void explainsThatDottedCommandExpectationPathMustBeNestedYaml() throws Exception {
        RecordingClient client = new RecordingClient(
                flatCommandExpectationDocument(),
                validDocument());
        SpecDraftGenerator generator = new SpecDraftGenerator(
                client,
                new ChangeSpecCodec(),
                "CHANGE-TEST-001");

        generator.generate("修复除零行为，测试命令为 mvn test", "", "");

        assertEquals(2, client.requests.size());
        String systemPrompt = client.requests.get(0).messages().get(0).content();
        assertTrue(systemPrompt.contains("expect:\n       exit_code: 0"));
        String correctionPrompt = client.requests.get(1).messages()
                .get(client.requests.get(1).messages().size() - 1).content();
        assertTrue(correctionPrompt.contains("expect.exit_code 只是字段路径，不是 YAML 键名"));
        assertTrue(correctionPrompt.contains("expect:\n  exit_code: 0"));
        assertTrue(correctionPrompt.contains("首行必须是 ---"));
        assertTrue(correctionPrompt.contains("不能只输出局部字段"));
    }

    @Test
    void extractsCompleteFrontMatterDocumentAfterModelPreface() throws Exception {
        RecordingClient client = new RecordingClient(
                "下面是修正后的完整文档：\n```yaml\n" + validDocument() + "\n```");
        SpecDraftGenerator generator = new SpecDraftGenerator(
                client,
                new ChangeSpecCodec(),
                "CHANGE-TEST-001");

        ChangeSpecDocument document = generator.generate("修复问题", "", "");

        assertEquals("修复登录超时重试", document.spec().title());
        assertEquals(1, client.requests.size());
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

    private static String flatCommandExpectationDocument() {
        return """
                ---
                schema: paicli/change-spec/v1
                id: CHANGE-TEST-001
                revision: 1
                title: 修复除零行为
                intent:
                  goal: 除数为零时返回空结果
                  non_goals: []
                scope:
                  mode: open
                  include: []
                  exclude: []
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: 除数为零时返回空结果
                    oracle:
                      type: deterministic
                      verifiers: [VT-TEST]
                  - id: AC-SCOPE
                    kind: scope
                    statement: 修改不得超出声明的 Scope
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-TEST
                    type: command
                    command: mvn test
                    expect.exit_code: 0
                  - id: VT-SCOPE
                    type: path_scope
                ---
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
