package com.paicli.llm;

import com.paicli.config.PaiCliConfig;

public class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(String provider, PaiCliConfig config) {
        return create(provider, null, config);
    }

    /** 创建绑定 ExecutionRoute 明确模型的任务级 Client，不修改共享配置。 */
    public static LlmClient create(String provider, String routedModel, PaiCliConfig config) {
        if (provider == null) return null;

        String normalized = normalizeProvider(provider);
        String configuredProvider = provider.trim().toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if ((apiKey == null || apiKey.isBlank()) && !configuredProvider.equals(normalized)) {
            apiKey = config.getApiKey(configuredProvider);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = firstConfigured(routedModel, firstConfigured(config.getModel(normalized),
                configuredProvider.equals(normalized) ? null : config.getModel(configuredProvider)));
        String baseUrl = firstConfigured(config.getBaseUrl(normalized),
                configuredProvider.equals(normalized) ? null : config.getBaseUrl(configuredProvider));
        String loraId = firstConfigured(config.getLoraId(normalized),
                configuredProvider.equals(normalized) ? null : config.getLoraId(configuredProvider));

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model, baseUrl);
            case "step" -> new StepClient(apiKey, model, baseUrl);
            case "kimi" -> new KimiClient(apiKey, model, baseUrl);
            case "freellmapi" -> new FreeLlmApiClient(apiKey, model, baseUrl);
            case "xfyun" -> new XfyunMaaSClient(apiKey, model, baseUrl, loraId);
            case "agnes" -> new AgnesClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(PaiCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        for (String provider : new String[]{"glm", "deepseek", "step", "kimi", "freellmapi", "xfyun", "agnes"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    public static String defaultModel(String provider) {
        return switch (normalizeProvider(provider)) {
            case "glm" -> "glm-5.1";
            case "deepseek" -> "DeepSeek-V4-pro";
            case "step" -> "step-3.5-flash";
            case "kimi" -> "kimi-k2.6";
            case "freellmapi" -> "auto";
            case "xfyun" -> "Qwen3.6-35B-A3B";
            case "agnes" -> "agnes-2.0-flash";
            default -> throw new IllegalArgumentException("不支持的 provider: " + provider);
        };
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase();
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "free-llm-api", "free_llm_api", "freellm", "free-llm" -> "freellmapi";
            case "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" -> "xfyun";
            case "agnes-ai", "agnes_ai", "sapiens", "sapiens-ai", "sapiens_ai" -> "agnes";
            default -> normalized;
        };
    }

    private static String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
