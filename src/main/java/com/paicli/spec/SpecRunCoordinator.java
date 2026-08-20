package com.paicli.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 将已确认的 Draft 锁定为不可覆盖文件，并作为不可变契约交给现有 ReAct 执行。
 * 本切片不执行 Verifier，也不生成 Evidence 或 Verdict。
 */
public final class SpecRunCoordinator {
    private static final String SPECS_DIR = ".paicli/specs";

    private final Path projectRoot;
    private final SpecDraftSession draftSession;
    private final UnaryOperator<String> confirmedRequestExpander;
    private final ReActExecutor reactExecutor;
    private final ChangeSpecCodec codec;

    public SpecRunCoordinator(
            Path projectRoot,
            SpecDraftSession draftSession,
            UnaryOperator<String> confirmedRequestExpander,
            ReActExecutor reactExecutor
    ) {
        this(
                projectRoot,
                draftSession,
                confirmedRequestExpander,
                reactExecutor,
                new ChangeSpecCodec());
    }

    SpecRunCoordinator(
            Path projectRoot,
            SpecDraftSession draftSession,
            UnaryOperator<String> confirmedRequestExpander,
            ReActExecutor reactExecutor,
            ChangeSpecCodec codec
    ) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.draftSession = Objects.requireNonNull(draftSession, "draftSession");
        this.confirmedRequestExpander = Objects.requireNonNull(
                confirmedRequestExpander,
                "confirmedRequestExpander");
        this.reactExecutor = Objects.requireNonNull(reactExecutor, "reactExecutor");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Result run(String request) throws IOException {
        SpecDraftSession.Result review = draftSession.run(request);
        if (review.status() == SpecDraftSession.Status.CANCELED) {
            return new Result(Status.CANCELED, null, null);
        }

        ChangeSpecDocument document = Objects.requireNonNull(review.document(), "confirmed document");
        String confirmedRequest = Objects.requireNonNull(review.confirmedRequest(), "confirmed request");
        LockedSpec lockedSpec = lock(document);
        String expandedRequest = Objects.requireNonNull(
                confirmedRequestExpander.apply(confirmedRequest),
                "expanded confirmed request");
        String executionInput = buildExecutionInput(expandedRequest, document);
        String response = reactExecutor.run(executionInput, lockedSpec);
        return new Result(Status.FINISHED, lockedSpec, response);
    }

    private LockedSpec lock(ChangeSpecDocument document) throws IOException {
        String encoded = codec.encode(document);
        ChangeSpecDocument encodedDocument = codec.decode(encoded);
        assertIdentity(document, encodedDocument, "编码后的 ChangeSpec");

        Path specsDir = projectRoot.resolve(SPECS_DIR).normalize();
        if (!specsDir.startsWith(projectRoot)) {
            throw new IOException("ChangeSpec 保存目录超出项目根目录");
        }
        Files.createDirectories(specsDir);

        ChangeSpec spec = document.spec();
        String fileName = spec.id() + "-r" + spec.revision() + ".md";
        Path target = specsDir.resolve(fileName).normalize();
        if (!specsDir.equals(target.getParent())) {
            throw new IOException("ChangeSpec id 不能用于安全文件名: " + spec.id());
        }
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException("锁定的 ChangeSpec 已存在，不能覆盖: " + target);
        }

        Path temporary = Files.createTempFile(specsDir, "." + fileName + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            moveWithoutReplacing(temporary, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }

        ChangeSpecDocument saved = codec.decode(Files.readString(target, StandardCharsets.UTF_8));
        assertIdentity(document, saved, "保存后的 ChangeSpec");
        return new LockedSpec(target, spec.id(), spec.revision(), document.specDigest());
    }

    private String buildExecutionInput(String confirmedRequest, ChangeSpecDocument document) throws IOException {
        String machineContract = codec.encodeMachineContract(document);
        return """
                执行以下已经由用户确认并锁定的 ChangeSpec。它是本轮不可变契约，不得修改或替换。

                <confirmed_request>
                %s
                </confirmed_request>

                <locked_change_spec id="%s" revision="%d" digest="%s">
                %s
                </locked_change_spec>

                使用现有 ReAct 能力完成代码修改，并遵守当前 HITL、PathGuard 和 CommandGuard。
                你可以把测试作为实现工作的一部分运行，但当前阶段没有 Evidence Gate；你的最终回答不是验收 Verdict，不得把自述称为验收通过，也不得生成 PASSED Verdict。
                """.formatted(
                confirmedRequest,
                document.spec().id(),
                document.spec().revision(),
                document.specDigest(),
                machineContract);
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

    private static void moveWithoutReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    @FunctionalInterface
    public interface ReActExecutor {
        String run(String executionInput, LockedSpec lockedSpec);
    }

    public record LockedSpec(Path path, String specId, int revision, String specDigest) {
    }

    public record Result(Status status, LockedSpec lockedSpec, String agentResponse) {
    }

    public enum Status {
        CANCELED,
        FINISHED
    }
}
