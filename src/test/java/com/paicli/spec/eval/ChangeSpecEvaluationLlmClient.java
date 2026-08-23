package com.paicli.spec.eval;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class ChangeSpecEvaluationLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final LongSupplier nanoTime;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong requestDurationNanos = new AtomicLong();

    ChangeSpecEvaluationLlmClient(LlmClient delegate) {
        this(delegate, System::nanoTime);
    }

    ChangeSpecEvaluationLlmClient(LlmClient delegate, LongSupplier nanoTime) {
        if (delegate == null) throw new IllegalArgumentException("delegate 不能为空");
        if (nanoTime == null) throw new IllegalArgumentException("nanoTime 不能为空");
        this.delegate = delegate;
        this.nanoTime = nanoTime;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        calls.incrementAndGet();
        long startedAt = nanoTime.getAsLong();
        try {
            return record(delegate.chat(messages, tools));
        } finally {
            recordDuration(startedAt);
        }
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
            throws IOException {
        calls.incrementAndGet();
        long startedAt = nanoTime.getAsLong();
        try {
            return record(delegate.chat(messages, tools, listener));
        } finally {
            recordDuration(startedAt);
        }
    }

    private void recordDuration(long startedAt) {
        long elapsed = Math.max(0L, nanoTime.getAsLong() - startedAt);
        requestDurationNanos.updateAndGet(current -> saturatingAdd(current, elapsed));
    }

    private static long saturatingAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    private ChatResponse record(ChatResponse response) {
        if (response != null) {
            inputTokens.addAndGet(Math.max(0, response.inputTokens()));
            outputTokens.addAndGet(Math.max(0, response.outputTokens()));
            cachedInputTokens.addAndGet(Math.max(0, response.cachedInputTokens()));
        }
        return response;
    }

    int calls() { return calls.get(); }
    long inputTokens() { return inputTokens.get(); }
    long outputTokens() { return outputTokens.get(); }
    long cachedInputTokens() { return cachedInputTokens.get(); }
    long requestDurationMs() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(requestDurationNanos.get());
    }

    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public String getProviderName() { return delegate.getProviderName(); }
    @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
    @Override public boolean supportsPromptCaching() { return delegate.supportsPromptCaching(); }
    @Override public boolean supportsTools() { return delegate.supportsTools(); }
    @Override public boolean supportsImageInput() { return delegate.supportsImageInput(); }
    @Override public String promptCacheMode() { return delegate.promptCacheMode(); }
}
