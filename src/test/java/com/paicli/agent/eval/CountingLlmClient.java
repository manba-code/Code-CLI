package com.paicli.agent.eval;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class CountingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();

    CountingLlmClient(LlmClient delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        calls.incrementAndGet();
        return recordUsage(delegate.chat(messages, tools));
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools,
                             StreamListener listener) throws IOException {
        calls.incrementAndGet();
        return recordUsage(delegate.chat(messages, tools, listener));
    }

    private ChatResponse recordUsage(ChatResponse response) {
        if (response != null) {
            inputTokens.addAndGet(Math.max(0, response.inputTokens()));
            outputTokens.addAndGet(Math.max(0, response.outputTokens()));
            cachedInputTokens.addAndGet(Math.max(0, response.cachedInputTokens()));
        }
        return response;
    }

    int calls() {
        return calls.get();
    }

    long inputTokens() {
        return inputTokens.get();
    }

    long outputTokens() {
        return outputTokens.get();
    }

    long cachedInputTokens() {
        return cachedInputTokens.get();
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public boolean supportsImageInput() {
        return delegate.supportsImageInput();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }
}
