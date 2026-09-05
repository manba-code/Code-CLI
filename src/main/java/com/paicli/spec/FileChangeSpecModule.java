package com.paicli.spec;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** 文件实现：Draft 可恢复，确认后的 Spec 不可覆盖。 */
public final class FileChangeSpecModule implements ChangeSpecModule {
    private static final String DRAFTS_DIR = ".paicli/spec-drafts";
    private static final String LOCKED_DIR = ".paicli/specs";

    private final Path projectRoot;
    private final ChangeSpecCodec codec;
    private final DraftProvider draftProvider;

    public FileChangeSpecModule(Path projectRoot, DraftProvider draftProvider) {
        this(projectRoot, new ChangeSpecCodec(), draftProvider);
    }

    public static FileChangeSpecModule usingLlmClient(Path projectRoot, LlmClient llmClient) {
        Objects.requireNonNull(llmClient, "llmClient");
        ChangeSpecCodec codec = new ChangeSpecCodec();
        return new FileChangeSpecModule(projectRoot, codec, context -> new SpecDraftGenerator(
                llmClient,
                codec,
                context.specId(),
                context.revision()).generateWithMetrics(
                        context.request(),
                        context.projectContext(),
                        context.referencedContext()));
    }

    FileChangeSpecModule(Path projectRoot, ChangeSpecCodec codec, DraftProvider draftProvider) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
        this.draftProvider = Objects.requireNonNull(draftProvider, "draftProvider");
    }

    @Override
    public SpecDraft generateDraft(ChangeContext context) throws IOException {
        SpecDraftSession.DraftGeneration generation = Objects.requireNonNull(
                draftProvider.generate(context),
                "draft generation");
        ChangeSpecDocument document = Objects.requireNonNull(generation.document(), "draft document");
        assertContextIdentity(context, document);

        Path changeDir = safeChild(projectRoot.resolve(DRAFTS_DIR), context.changeId(), "changeId");
        if (!context.artifactKey().isEmpty()) {
            changeDir = safeChild(changeDir, context.artifactKey(), "artifactKey");
        }
        Files.createDirectories(changeDir);
        Path target = safeChild(
                changeDir,
                document.spec().id() + "-r" + document.spec().revision() + ".md",
                "ChangeSpec id");
        writeNew(target, codec.encode(document));
        ChangeSpecDocument saved = codec.decode(Files.readString(target, StandardCharsets.UTF_8));
        assertIdentity(document, saved, "保存后的 ChangeSpec Draft");
        return new SpecDraft(
                target,
                saved.spec().id(),
                saved.spec().revision(),
                saved.specDigest(),
                generation.durationMs(),
                generation.llmUsage());
    }

    @Override
    public LockedSpec lockConfirmed(SpecDraft draft, String expectedDigest) throws IOException {
        Objects.requireNonNull(draft, "draft");
        String expected = requireText(expectedDigest, "expectedDigest");
        Path draftsRoot = projectRoot.resolve(DRAFTS_DIR).toAbsolutePath().normalize();
        if (!draft.path().startsWith(draftsRoot)) {
            throw new IOException("ChangeSpec Draft 路径不属于当前项目的 Draft 目录");
        }
        ChangeSpecDocument document = codec.decode(Files.readString(draft.path(), StandardCharsets.UTF_8));
        if (!draft.specId().equals(document.spec().id())
                || draft.revision() != document.spec().revision()
                || !draft.specDigest().equals(document.specDigest())) {
            throw new IOException("ChangeSpec Draft 的 specId、revision 或 digest 已发生变化");
        }
        if (!expected.equals(document.specDigest())) {
            throw new SpecDigestConflictException(expected, document.specDigest());
        }
        return lockDocument(document);
    }

    LockedSpec lockDocument(ChangeSpecDocument document) throws IOException {
        Objects.requireNonNull(document, "document");
        String encoded = codec.encode(document);
        ChangeSpecDocument encodedDocument = codec.decode(encoded);
        assertIdentity(document, encodedDocument, "编码后的 ChangeSpec");

        Path lockedDir = projectRoot.resolve(LOCKED_DIR).normalize();
        if (!lockedDir.startsWith(projectRoot)) {
            throw new IOException("ChangeSpec 保存目录超出项目根目录");
        }
        Files.createDirectories(lockedDir);
        Path target = safeChild(
                lockedDir,
                document.spec().id() + "-r" + document.spec().revision() + ".md",
                "ChangeSpec id");
        writeNew(target, encoded);
        ChangeSpecDocument saved = codec.decode(Files.readString(target, StandardCharsets.UTF_8));
        assertIdentity(document, saved, "保存后的 ChangeSpec");
        return new LockedSpec(
                target,
                saved.spec().id(),
                saved.spec().revision(),
                saved.specDigest());
    }

    private static Path safeChild(Path parent, String name, String label) throws IOException {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path target = normalizedParent.resolve(requireText(name, label)).normalize();
        if (!normalizedParent.equals(target.getParent())) {
            throw new IOException(label + " 不能用于安全文件名: " + name);
        }
        return target;
    }

    private static void writeNew(Path target, String content) throws IOException {
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException("ChangeSpec 文件已存在，不能覆盖: " + target);
        }
        Path directory = target.getParent();
        Path temporary = Files.createTempFile(directory, "." + target.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void assertContextIdentity(ChangeContext context, ChangeSpecDocument document) {
        if (!context.specId().equals(document.spec().id())
                || context.revision() != document.spec().revision()) {
            throw new ChangeSpecValidationException(java.util.List.of(
                    "Draft identity 必须是 " + context.specId() + " revision " + context.revision()));
        }
    }

    private static void assertIdentity(
            ChangeSpecDocument expected,
            ChangeSpecDocument actual,
            String source
    ) throws IOException {
        if (!expected.spec().id().equals(actual.spec().id())
                || expected.spec().revision() != actual.spec().revision()
                || !expected.specDigest().equals(actual.specDigest())) {
            throw new IOException(source + " 的 specId、revision 或 digest 与确认结果不一致");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    @FunctionalInterface
    public interface DraftProvider {
        SpecDraftSession.DraftGeneration generate(ChangeContext context) throws IOException;
    }
}
