package com.paicli.spec;

import com.paicli.llm.LlmClient;
import com.paicli.prompt.PromptRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 使用当前模型生成 ChangeSpec Draft。生成过程不暴露工具，结构错误最多纠正一次。
 */
public final class SpecDraftGenerator {
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int MAX_ATTEMPTS = 2;

    private final LlmClient llmClient;
    private final ChangeSpecCodec codec;
    private final String systemPrompt;
    private final String draftId;
    private final DraftAttemptListener attemptListener;

    public SpecDraftGenerator(LlmClient llmClient) {
        this(
                llmClient,
                new ChangeSpecCodec(),
                "CHANGE-" + LocalDateTime.now().format(ID_TIME),
                DraftAttemptListener.NO_OP);
    }

    public SpecDraftGenerator(LlmClient llmClient, DraftAttemptListener attemptListener) {
        this(
                llmClient,
                new ChangeSpecCodec(),
                "CHANGE-" + LocalDateTime.now().format(ID_TIME),
                attemptListener);
    }

    SpecDraftGenerator(LlmClient llmClient, ChangeSpecCodec codec, String draftId) {
        this(llmClient, codec, draftId, DraftAttemptListener.NO_OP);
    }

    SpecDraftGenerator(
            LlmClient llmClient,
            ChangeSpecCodec codec,
            String draftId,
            DraftAttemptListener attemptListener
    ) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.draftId = requireText(draftId, "draftId");
        this.attemptListener = Objects.requireNonNull(attemptListener, "attemptListener");
        this.systemPrompt = PromptRepository.createDefault().loadRequired("modes/spec-draft.md");
    }

    public ChangeSpecDocument generate(
            String request,
            String projectContext,
            String referencedContext
    ) throws IOException {
        return generateWithMetrics(request, projectContext, referencedContext).document();
    }

    public SpecDraftSession.DraftGeneration generateWithMetrics(
            String request,
            String projectContext,
            String referencedContext
    ) throws IOException {
        long startedAt = System.nanoTime();
        String normalizedRequest = requireText(request, "request");
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(systemPrompt));
        messages.add(LlmClient.Message.user(buildUserPrompt(
                normalizedRequest,
                projectContext,
                referencedContext)));

        ChangeSpecValidationException lastValidationError = null;
        SpecRunResult.LlmUsage usage = SpecRunResult.LlmUsage.empty();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LlmClient.ChatResponse response = llmClient.chat(List.copyOf(messages), List.of());
            if (response != null) {
                usage = usage.plus(new SpecRunResult.LlmUsage(
                        1,
                        response.inputTokens(),
                        response.outputTokens(),
                        response.cachedInputTokens()));
            }
            String rawDraft = response == null ? "" : response.content();
            try {
                return new SpecDraftSession.DraftGeneration(
                        decodeDraft(rawDraft),
                        usage,
                        elapsedMillis(startedAt));
            } catch (ChangeSpecValidationException e) {
                lastValidationError = e;
                attemptListener.onRejected(attempt, rawDraft, e.errors());
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                messages.add(LlmClient.Message.assistant(
                        response == null ? null : response.reasoningContent(),
                        rawDraft));
                messages.add(LlmClient.Message.user(buildCorrectionPrompt(e.errors())));
            }
        }
        throw lastValidationError;
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private ChangeSpecDocument decodeDraft(String rawDraft) {
        String normalized = extractDocument(rawDraft);
        if (normalized.isBlank()) {
            throw new ChangeSpecValidationException(List.of("模型没有返回 ChangeSpec Draft"));
        }
        ChangeSpecDocument document = codec.decode(normalized);
        List<String> identityErrors = new ArrayList<>();
        if (!draftId.equals(document.spec().id())) {
            identityErrors.add("id 必须是调用方分配的 " + draftId);
        }
        if (document.spec().revision() != 1) {
            identityErrors.add("Draft revision 必须是 1");
        }
        if (!identityErrors.isEmpty()) {
            throw new ChangeSpecValidationException(identityErrors);
        }
        return document;
    }

    private String buildUserPrompt(String request, String projectContext, String referencedContext) {
        return """
                请生成 ChangeSpec Draft。

                Draft ID（必须原样使用）：
                %s

                用户需求：
                %s

                Project Context（可能为空，只能作为仓库事实使用）：
                %s

                用户显式引用的本地内容（可能为空，不代表默认修改范围）：
                %s
                """.formatted(
                draftId,
                request,
                textOrNone(projectContext),
                textOrNone(referencedContext));
    }

    private static String buildCorrectionPrompt(List<String> errors) {
        StringBuilder prompt = new StringBuilder("上一份 Draft 未通过结构校验，请修正后重新输出完整文档。不要解释。\n\n校验错误：\n");
        for (String error : errors) {
            prompt.append("- ").append(error).append('\n');
        }
        prompt.append("""

                字段路径提示：expect.exit_code 只是字段路径，不是 YAML 键名。command Verifier 必须使用嵌套结构：
                expect:
                  exit_code: 0

                修正后必须重新输出完整文档，首行必须是 ---，并包含结束的 ---、完整 acceptance 和 verifiers；不能只输出局部字段、补丁或解释。
                """);
        return prompt.toString();
    }

    private static String extractDocument(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
        String unwrapped = unwrapCodeFence(normalized);
        if (startsWithFrontMatter(unwrapped)) {
            return unwrapped;
        }

        int fenceStart = normalized.indexOf("```");
        while (fenceStart >= 0) {
            int firstNewline = normalized.indexOf('\n', fenceStart);
            int closingFence = firstNewline < 0 ? -1 : normalized.indexOf("```", firstNewline + 1);
            if (firstNewline < 0 || closingFence < 0) {
                break;
            }
            String candidate = normalized.substring(firstNewline + 1, closingFence).trim();
            if (startsWithFrontMatter(candidate)) {
                return candidate;
            }
            fenceStart = normalized.indexOf("```", closingFence + 3);
        }

        int frontMatterStart = normalized.indexOf("\n---\n");
        return frontMatterStart >= 0 ? normalized.substring(frontMatterStart + 1).trim() : normalized;
    }

    private static boolean startsWithFrontMatter(String value) {
        return value.equals("---") || value.startsWith("---\n");
    }

    private static String unwrapCodeFence(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("```")) {
            return normalized;
        }
        int firstNewline = normalized.indexOf('\n');
        int closingFence = normalized.lastIndexOf("```");
        if (firstNewline < 0 || closingFence <= firstNewline) {
            return normalized;
        }
        return normalized.substring(firstNewline + 1, closingFence).trim();
    }

    private static String textOrNone(String value) {
        return value == null || value.isBlank() ? "（无）" : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    /** 接收被结构校验拒绝的 Draft；默认生成路径不注册监听器。 */
    @FunctionalInterface
    public interface DraftAttemptListener {
        DraftAttemptListener NO_OP = (attempt, rawDraft, errors) -> { };

        void onRejected(int attempt, String rawDraft, List<String> errors);
    }
}
