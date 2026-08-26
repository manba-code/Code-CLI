package com.paicli.llm;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.util.List;

public class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "DeepSeek-V4-pro";
    private static final OkHttpClient HTTP_1_1_CLIENT = SHARED_HTTP_CLIENT.newBuilder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .build();
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public DeepSeekClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    public boolean supportsImageInput() {
        return false;
    }

    @Override
    protected OkHttpClient httpClient() {
        return HTTP_1_1_CLIENT;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "automatic-prefix-cache";
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }

}
