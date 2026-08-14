package com.paicli.agent.eval;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationInfrastructureTest {

    @Test
    void shouldCountUsageAndDelegateCapabilities() throws Exception {
        CountingLlmClient client = new CountingLlmClient(new StubClient());

        client.chat(List.of(LlmClient.Message.user("hello")), List.of());
        client.chat(List.of(LlmClient.Message.user("world")), List.of(), LlmClient.StreamListener.NO_OP);

        assertEquals(2, client.calls());
        assertEquals(22, client.inputTokens());
        assertEquals(10, client.outputTokens());
        assertEquals(4, client.cachedInputTokens());
        assertEquals("stub", client.getProviderName());
        assertFalse(client.supportsImageInput());
    }

    @Test
    void shouldVerifyHiddenCommandAndRejectUnexpectedChanges(@TempDir Path tempDir) throws Exception {
        AgentEvaluationCase evaluationCase = new AgentEvaluationCase(
                "infra", "change allowed.txt",
                Map.of("allowed.txt", "before", "protected.txt", "keep"),
                Map.of("hidden.txt", "secret"), Set.of("allowed.txt"),
                javaVersionCommand(), Duration.ofSeconds(20));
        Path passing = tempDir.resolve("passing");
        evaluationCase.materialize(passing);
        AgentEvaluationCase.WorkspaceSnapshot passingBaseline = evaluationCase.snapshot(passing);
        Files.writeString(passing.resolve("allowed.txt"), "after");

        AgentEvaluationCase.ValidationResult passed = evaluationCase.verify(passing, passingBaseline);

        assertTrue(passed.passed());
        assertTrue(Files.exists(passing.resolve("hidden.txt")));

        Path failing = tempDir.resolve("failing");
        evaluationCase.materialize(failing);
        AgentEvaluationCase.WorkspaceSnapshot failingBaseline = evaluationCase.snapshot(failing);
        Files.writeString(failing.resolve("protected.txt"), "changed");

        AgentEvaluationCase.ValidationResult failed = evaluationCase.verify(failing, failingBaseline);

        assertFalse(failed.passed());
        assertTrue(failed.unexpectedFiles().contains("protected.txt"));
    }

    private static List<String> javaVersionCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-version");
    }

    private static final class StubClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return response();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return response();
        }

        private ChatResponse response() {
            return new ChatResponse("assistant", "ok", null, null, 11, 5, 2);
        }

        @Override public String getModelName() { return "stub-model"; }
        @Override public String getProviderName() { return "stub"; }
        @Override public boolean supportsImageInput() { return false; }
    }
}
