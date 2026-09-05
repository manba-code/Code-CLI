package com.paicli.spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * ChangeSpec 的异步阶段接口：生成可持久化 Draft，并在审批 digest 匹配时不可覆盖地锁定。
 */
public interface ChangeSpecModule {
    SpecDraft generateDraft(ChangeContext context) throws IOException;

    LockedSpec lockConfirmed(SpecDraft draft, String expectedDigest) throws IOException;

    record ChangeContext(
            String changeId,
            String specId,
            int revision,
            String request,
            String projectContext,
            String referencedContext,
            String artifactKey
    ) {
        public ChangeContext(String changeId, String specId, int revision, String request,
                             String projectContext, String referencedContext) {
            this(changeId, specId, revision, request, projectContext, referencedContext, "");
        }
        public ChangeContext {
            artifactKey = normalize(artifactKey);
            if (!artifactKey.isEmpty() && !artifactKey.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("artifactKey 必须为安全文件名");
            }
            changeId = requireText(changeId, "changeId");
            specId = requireText(specId, "specId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision 必须大于等于 1");
            }
            request = requireText(request, "request");
            projectContext = normalize(projectContext);
            referencedContext = normalize(referencedContext);
        }
    }

    record SpecDraft(
            Path path,
            String specId,
            int revision,
            String specDigest,
            long generationMs,
            SpecRunResult.LlmUsage llmUsage
    ) {
        public SpecDraft {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            specId = requireText(specId, "specId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision 必须大于等于 1");
            }
            specDigest = requireText(specDigest, "specDigest");
            generationMs = Math.max(0L, generationMs);
            llmUsage = llmUsage == null ? SpecRunResult.LlmUsage.empty() : llmUsage;
        }
    }

    record LockedSpec(Path path, String specId, int revision, String specDigest) {
        public LockedSpec {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            specId = requireText(specId, "specId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision 必须大于等于 1");
            }
            specDigest = requireText(specDigest, "specDigest");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
