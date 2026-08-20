package com.paicli.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecRunCoordinatorTest {
    private final ChangeSpecCodec codec = new ChangeSpecCodec();

    @TempDir
    Path projectRoot;

    @Test
    void locksConfirmedSpecAndExecutesReactWithConfirmedRequest() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        List<String> executionInputs = new ArrayList<>();
        List<SpecRunCoordinator.LockedSpec> locks = new ArrayList<>();
        SpecDraftSession session = new SpecDraftSession(
                request -> document,
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request.replace("@src", "<directory>src</directory>"),
                (input, lockedSpec) -> {
                    executionInputs.add(input);
                    locks.add(lockedSpec);
                    return "agent response";
                });

        SpecRunCoordinator.Result result = coordinator.run("修复问题 @src");

        assertEquals(SpecRunCoordinator.Status.FINISHED, result.status());
        assertEquals("agent response", result.agentResponse());
        assertEquals(1, executionInputs.size());
        assertTrue(executionInputs.get(0).contains("修复问题 <directory>src</directory>"));
        assertTrue(executionInputs.get(0).contains("id: CHANGE-001"));
        assertTrue(executionInputs.get(0).contains(document.specDigest()));
        assertTrue(executionInputs.get(0).contains("不是验收 Verdict"));

        SpecRunCoordinator.LockedSpec locked = result.lockedSpec();
        assertEquals(locked, locks.get(0));
        assertEquals(projectRoot.resolve(".paicli/specs/CHANGE-001-r1.md"), locked.path());
        assertTrue(Files.isRegularFile(locked.path()));
        ChangeSpecDocument saved = codec.decode(Files.readString(locked.path()));
        assertEquals(document.spec().id(), saved.spec().id());
        assertEquals(document.spec().revision(), saved.spec().revision());
        assertEquals(document.specDigest(), saved.specDigest());
    }

    @Test
    void injectsSupplementAsPartOfFinalConfirmedRequest() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        AtomicInteger reviews = new AtomicInteger();
        List<String> executionInputs = new ArrayList<>();
        SpecDraftSession session = new SpecDraftSession(
                request -> document,
                draft -> reviews.getAndIncrement() == 0
                        ? SpecDraftSession.ReviewDecision.supplement("不得修改公共接口")
                        : SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (input, lockedSpec) -> {
                    executionInputs.add(input);
                    return "done";
                });

        coordinator.run("修复问题");

        assertEquals(1, executionInputs.size());
        assertTrue(executionInputs.get(0).contains("用户补充要求"));
        assertTrue(executionInputs.get(0).contains("不得修改公共接口"));
    }

    @Test
    void cancellationDoesNotSaveOrExecute() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        AtomicBoolean executed = new AtomicBoolean();
        SpecDraftSession session = new SpecDraftSession(
                request -> document,
                draft -> SpecDraftSession.ReviewDecision.cancel());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (input, lockedSpec) -> {
                    executed.set(true);
                    return "unexpected";
                });

        SpecRunCoordinator.Result result = coordinator.run("修复问题");

        assertEquals(SpecRunCoordinator.Status.CANCELED, result.status());
        assertFalse(executed.get());
        assertFalse(Files.exists(projectRoot.resolve(".paicli/specs")));
    }

    @Test
    void existingLockedSpecIsNeverOverwrittenOrExecuted() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        Path specsDir = projectRoot.resolve(".paicli/specs");
        Files.createDirectories(specsDir);
        Path existing = specsDir.resolve("CHANGE-001-r1.md");
        Files.writeString(existing, "existing locked spec");
        AtomicBoolean executed = new AtomicBoolean();
        SpecRunCoordinator coordinator = coordinator(document, executed);

        IOException error = assertThrows(IOException.class, () -> coordinator.run("修复问题"));

        assertTrue(error.getMessage().contains("不能覆盖"), error.getMessage());
        assertEquals("existing locked spec", Files.readString(existing));
        assertFalse(executed.get());
    }

    @Test
    void persistenceFailurePreventsExecution() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        Files.writeString(projectRoot.resolve(".paicli"), "not a directory");
        AtomicBoolean executed = new AtomicBoolean();
        SpecRunCoordinator coordinator = coordinator(document, executed);

        assertThrows(IOException.class, () -> coordinator.run("修复问题"));

        assertFalse(executed.get());
    }

    @Test
    void specIdCannotEscapeTheLockedSpecsDirectory() {
        ChangeSpecDocument document = codec.decode(
                validDocument().replace("id: CHANGE-001", "id: ../escape"));
        AtomicBoolean executed = new AtomicBoolean();
        SpecRunCoordinator coordinator = coordinator(document, executed);

        IOException error = assertThrows(IOException.class, () -> coordinator.run("修复问题"));

        assertTrue(error.getMessage().contains("安全文件名"), error.getMessage());
        assertFalse(executed.get());
        assertFalse(Files.exists(projectRoot.resolve(".paicli/escape-r1.md")));
    }

    @Test
    void executionFailureKeepsLockedSpec() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        SpecDraftSession session = new SpecDraftSession(
                request -> document,
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (input, lockedSpec) -> {
                    throw new IllegalStateException("react failed");
                });

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> coordinator.run("修复问题"));

        assertEquals("react failed", error.getMessage());
        Path lockedPath = projectRoot.resolve(".paicli/specs/CHANGE-001-r1.md");
        assertTrue(Files.isRegularFile(lockedPath));
        assertNotNull(codec.decode(Files.readString(lockedPath)));
    }

    private SpecRunCoordinator coordinator(ChangeSpecDocument document, AtomicBoolean executed) {
        SpecDraftSession session = new SpecDraftSession(
                request -> document,
                draft -> SpecDraftSession.ReviewDecision.confirm());
        return new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (input, lockedSpec) -> {
                    executed.set(true);
                    return "unexpected";
                });
    }

    private static String validDocument() {
        return """
                ---
                schema: paicli/change-spec/v1
                id: CHANGE-001
                revision: 1
                title: 修复问题
                intent:
                  goal: 修复问题
                  non_goals: []
                scope:
                  mode: open
                  include: []
                  exclude: []
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: 问题已修复
                    oracle:
                      type: human
                      verifiers: []
                verifiers: []
                ---

                # 背景

                需要修复当前问题。
                """;
    }
}
