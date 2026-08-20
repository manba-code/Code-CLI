package com.paicli.agent;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunResultTest {

    @Test
    void exposesPerRunOutcomeUsageAndElapsedTime() {
        Agent agent = new Agent(new OneShotClient());

        Agent.RunResult result = agent.runDetailed("完成任务");

        assertEquals(Agent.RunOutcome.COMPLETED, result.outcome());
        assertEquals(1, result.llmCalls());
        assertEquals(11, result.inputTokens());
        assertEquals(7, result.outputTokens());
        assertEquals(3, result.cachedInputTokens());
        assertTrue(result.elapsedMs() >= 0L);
        assertTrue(result.response().contains("done"));
    }

    private static final class OneShotClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "done", null, List.of(), 11, 7, 3);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
