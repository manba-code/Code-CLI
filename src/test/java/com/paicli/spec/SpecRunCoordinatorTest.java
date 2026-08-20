package com.paicli.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.tool.CommandExecutionResult;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request.replace("@src", "<directory>src</directory>"),
                (phase, input, lockedSpec) -> {
                    executionInputs.add(input);
                    locks.add(lockedSpec);
                    return SpecRunCoordinator.ReActExecutionResult.completed("agent response");
                });

        SpecRunResult result = coordinator.run("修复问题 @src");

        assertEquals(SpecRunResult.Status.FINISHED, result.status());
        assertEquals("agent response", result.agentResponse());
        assertEquals(1, result.verifierResults().size());
        assertEquals(SpecVerifier.Status.PASS, result.verifierResults().get(0).status());
        assertTrue(result.workspaceChanges().changedFiles().isEmpty());
        assertEquals(1, executionInputs.size());
        assertTrue(executionInputs.get(0).contains("修复问题 <directory>src</directory>"));
        assertTrue(executionInputs.get(0).contains("id: CHANGE-001"));
        assertTrue(executionInputs.get(0).contains(document.specDigest()));
        assertTrue(executionInputs.get(0).contains("不是验收 Verdict"));

        SpecRunCoordinator.LockedSpec locked = locks.get(0);
        assertEquals(locked, locks.get(0));
        assertEquals(locked.specId(), result.identity().specId());
        assertEquals(locked.specDigest(), result.identity().specDigest());
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
                request -> draft(document),
                draft -> reviews.getAndIncrement() == 0
                        ? SpecDraftSession.ReviewDecision.supplement("不得修改公共接口")
                        : SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    executionInputs.add(input);
                    return SpecRunCoordinator.ReActExecutionResult.completed("done");
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
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.cancel());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    executed.set(true);
                    return SpecRunCoordinator.ReActExecutionResult.completed("unexpected");
                });

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Status.CANCELED, result.status());
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
    void executionFailureKeepsLockedSpecAndCollectsWorkspace() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    throw new IllegalStateException("react failed");
                });

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Status.REACT_FAILED, result.status());
        assertTrue(result.agentResponse().contains("react failed"));
        assertNotNull(result.workspaceChanges());
        assertTrue(result.verifierResults().isEmpty());
        assertEquals(SpecRunResult.Verdict.INCOMPLETE, result.verdict());
        assertTrue(Files.isRegularFile(result.artifacts().resultJson()));
        Path lockedPath = projectRoot.resolve(".paicli/specs/CHANGE-001-r1.md");
        assertTrue(Files.isRegularFile(lockedPath));
        assertNotNull(codec.decode(Files.readString(lockedPath)));
    }

    @Test
    void reactCancellationSkipsAllVerifiers() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        AtomicInteger commandRuns = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> SpecRunCoordinator.ReActExecutionResult.canceled("canceled"),
                new SpecVerifier(projectRoot, command -> {
                    commandRuns.incrementAndGet();
                    return com.paicli.tool.CommandExecutionResult.completed(command, 0, "ok");
                }));

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Status.REACT_CANCELED, result.status());
        assertTrue(result.verifierResults().isEmpty());
        assertNotNull(result.workspaceChanges());
        assertEquals(0, commandRuns.get());
        assertEquals(SpecRunResult.Verdict.INCOMPLETE, result.verdict());
        assertTrue(Files.isRegularFile(result.identity().lockedSpecPath()));
    }

    @Test
    void passesAllCriteriaAfterHumanConfirmationAndPersistsMetrics() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        SpecDraftSession session = new SpecDraftSession(
                request -> new SpecDraftSession.DraftGeneration(
                        document,
                        new SpecRunResult.LlmUsage(2, 30, 12, 4),
                        7L),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> SpecRunCoordinator.ReActExecutionResult.completed(
                        "done",
                        new SpecRunResult.LlmUsage(3, 100, 40, 10),
                        11L),
                new SpecVerifier(projectRoot, command -> CommandExecutionResult.completed(command, 0, "ok")),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Verdict.PASSED, result.verdict());
        assertTrue(result.criterionResults().stream()
                .allMatch(criterion -> criterion.status() == SpecRunResult.CriterionStatus.PASS));
        assertEquals(5, result.metrics().totalLlmUsage().calls());
        assertEquals(130, result.metrics().totalLlmUsage().inputTokens());
        assertEquals(SpecRunResult.PersistenceStatus.SAVED, result.artifacts().status());
        assertTrue(Files.isRegularFile(result.artifacts().resultJson()));
        assertTrue(Files.isRegularFile(result.artifacts().changeDiff()));

        JsonNode json = new ObjectMapper().readTree(result.artifacts().resultJson().toFile());
        assertEquals("paicli/spec-run-result/v1", json.path("schema").asText());
        assertEquals("PASSED", json.path("verdict").asText());
        assertEquals(document.specDigest(), json.path("spec").path("digest").asText());
        assertEquals(5, json.path("metrics").path("totalLlmUsage").path("calls").asInt());
        String diffEvidence = Files.readString(result.artifacts().changeDiff());
        assertTrue(diffEvidence.contains("# runId: " + result.identity().runId()));
        assertTrue(diffEvidence.contains("# specDigest: " + document.specDigest()));
    }

    @Test
    void verifierFailWinsOverErrorAndSkipsHumanCriteria() throws Exception {
        ChangeSpecDocument document = codec.decode(multiCommandDocument());
        AtomicInteger humanCalls = new AtomicInteger();
        SpecRunCoordinator coordinator = coordinator(
                document,
                new SpecVerifier(projectRoot, command -> switch (command) {
                    case "first" -> CommandExecutionResult.completed(command, 1, "assertion failed");
                    case "second" -> CommandExecutionResult.denied(
                            command,
                            CommandExecutionResult.Status.HITL_DENIED,
                            "user denied");
                    default -> CommandExecutionResult.completed(command, 0, "ok");
                }),
                (criterion, changes) -> {
                    humanCalls.incrementAndGet();
                    return SpecRunCoordinator.HumanJudgment.pass();
                });

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Verdict.FAILED, result.verdict());
        SpecRunResult.CriterionResult combined = result.criterionResults().stream()
                .filter(criterion -> criterion.criterionId().equals("AC-COMBINED"))
                .findFirst()
                .orElseThrow();
        assertEquals(SpecRunResult.CriterionStatus.FAIL, combined.status());
        assertEquals(
                List.of("verifier:attempt-1:VT-FIRST", "verifier:attempt-1:VT-SECOND"),
                combined.evidenceIds());
        assertEquals(0, result.metrics().repairCount());
        assertEquals(1, result.verificationAttempts().size());
        assertEquals(0, humanCalls.get());
        assertEquals(SpecRunResult.CriterionStatus.NOT_RUN, result.criterionResults().stream()
                .filter(criterion -> criterion.criterionId().equals("AC-HUMAN"))
                .findFirst().orElseThrow().status());
    }

    @Test
    void verifierErrorProducesIncompleteVerdict() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        SpecRunCoordinator coordinator = coordinator(
                document,
                new SpecVerifier(projectRoot, command -> CommandExecutionResult.startError(command, "missing binary")),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Verdict.INCOMPLETE, result.verdict());
        assertTrue(result.criterionResults().stream()
                .anyMatch(criterion -> criterion.status() == SpecRunResult.CriterionStatus.INCONCLUSIVE));
        assertEquals(0, result.metrics().repairCount());
    }

    @Test
    void repairsOnceThenRerunsAllVerifiersAndPersistsBothAttempts() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        AtomicInteger reactRuns = new AtomicInteger();
        AtomicInteger commandRuns = new AtomicInteger();
        List<SpecRunCoordinator.ReActPhase> phases = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    phases.add(phase);
                    inputs.add(input);
                    int run = reactRuns.incrementAndGet();
                    return SpecRunCoordinator.ReActExecutionResult.completed(
                            "run-" + run,
                            new SpecRunResult.LlmUsage(run, run * 10L, run * 4L, run),
                            run);
                },
                new SpecVerifier(projectRoot, command -> {
                    int run = commandRuns.incrementAndGet();
                    return CommandExecutionResult.completed(
                            command,
                            run == 1 ? 1 : 0,
                            run == 1 ? "API_KEY=supersecret\nassertion failed" : "ok");
                }),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Verdict.PASSED, result.verdict());
        assertEquals(2, reactRuns.get());
        assertEquals(2, commandRuns.get());
        assertEquals(List.of(
                SpecRunCoordinator.ReActPhase.INITIAL,
                SpecRunCoordinator.ReActPhase.REPAIR), phases);
        assertEquals(2, result.verificationAttempts().size());
        assertEquals(SpecRunResult.VerificationPhase.INITIAL, result.verificationAttempts().get(0).phase());
        assertEquals(SpecRunResult.VerificationPhase.POST_REPAIR, result.verificationAttempts().get(1).phase());
        assertEquals(1, result.metrics().repairCount());
        assertEquals(3, result.metrics().reactLlmUsage().calls());
        assertEquals("run-2", result.agentResponse());
        assertTrue(result.criterionResults().stream()
                .filter(criterion -> criterion.judge() == SpecRunResult.Judge.VERIFIER)
                .flatMap(criterion -> criterion.evidenceIds().stream())
                .allMatch(id -> id.contains("attempt-2")));

        String repairInput = inputs.get(1);
        assertTrue(repairInput.contains(document.specDigest()));
        assertTrue(repairInput.contains("AC-1"));
        assertTrue(repairInput.contains("API_KEY=***"));
        assertFalse(repairInput.contains("supersecret"));
        assertTrue(repairInput.contains("锁定的 ChangeSpec 不可修改"));

        JsonNode json = new ObjectMapper().readTree(result.artifacts().resultJson().toFile());
        assertEquals(2, json.path("verificationAttempts").size());
        assertEquals(
                "verifier:attempt-1:VT-COMMAND",
                json.path("verificationAttempts").get(0).path("verifierResults").get(1).path("evidenceId").asText());
        assertEquals(
                "verifier:attempt-2:VT-COMMAND",
                json.path("verificationAttempts").get(1).path("verifierResults").get(1).path("evidenceId").asText());
        assertFalse(json.has("verifierResults"));
    }

    @Test
    void stopsAfterOneRepairWhenFinalVerificationStillFails() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        AtomicInteger reactRuns = new AtomicInteger();
        AtomicInteger commandRuns = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    reactRuns.incrementAndGet();
                    return SpecRunCoordinator.ReActExecutionResult.completed("done");
                },
                new SpecVerifier(projectRoot, command -> {
                    commandRuns.incrementAndGet();
                    return CommandExecutionResult.completed(command, 1, "still failing");
                }),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Verdict.FAILED, result.verdict());
        assertEquals(2, reactRuns.get());
        assertEquals(2, commandRuns.get());
        assertEquals(2, result.verificationAttempts().size());
        assertEquals(1, result.metrics().repairCount());
    }

    @Test
    void repairFailureLeavesHistoricalEvidenceButFinalVerdictIsIncomplete() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        AtomicInteger reactRuns = new AtomicInteger();
        AtomicInteger commandRuns = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    if (reactRuns.incrementAndGet() == 1) {
                        return SpecRunCoordinator.ReActExecutionResult.completed("initial");
                    }
                    throw new IllegalStateException("repair exploded");
                },
                new SpecVerifier(projectRoot, command -> {
                    commandRuns.incrementAndGet();
                    return CommandExecutionResult.completed(command, 1, "assertion failed");
                }),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Status.REPAIR_FAILED, result.status());
        assertEquals(SpecRunResult.Verdict.INCOMPLETE, result.verdict());
        assertEquals(2, reactRuns.get());
        assertEquals(1, commandRuns.get());
        assertEquals(1, result.verificationAttempts().size());
        assertEquals(1, result.metrics().repairCount());
        assertTrue(result.agentResponse().contains("repair exploded"));
        assertTrue(result.criterionResults().stream()
                .allMatch(criterion -> criterion.status() == SpecRunResult.CriterionStatus.NOT_RUN));
        assertEquals(SpecRunResult.PersistenceStatus.SAVED, result.artifacts().status());
    }

    @Test
    void canceledRepairProducesIncompleteWithoutSecondVerification() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        AtomicInteger reactRuns = new AtomicInteger();
        AtomicInteger commandRuns = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> reactRuns.incrementAndGet() == 1
                        ? SpecRunCoordinator.ReActExecutionResult.completed("initial")
                        : SpecRunCoordinator.ReActExecutionResult.canceled("repair canceled"),
                new SpecVerifier(projectRoot, command -> {
                    commandRuns.incrementAndGet();
                    return CommandExecutionResult.completed(command, 1, "assertion failed");
                }),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Status.REPAIR_CANCELED, result.status());
        assertEquals(SpecRunResult.Verdict.INCOMPLETE, result.verdict());
        assertEquals(2, reactRuns.get());
        assertEquals(1, commandRuns.get());
        assertEquals(1, result.verificationAttempts().size());
    }

    @Test
    void tamperedLockedSpecDuringRepairProducesSpecInvalid() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        AtomicInteger reactRuns = new AtomicInteger();
        AtomicInteger commandRuns = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    if (reactRuns.incrementAndGet() == 2) {
                        try {
                            Files.writeString(lockedSpec.path(), "tampered during repair");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return SpecRunCoordinator.ReActExecutionResult.completed("done");
                },
                new SpecVerifier(projectRoot, command -> {
                    commandRuns.incrementAndGet();
                    return CommandExecutionResult.completed(command, 1, "assertion failed");
                }),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Status.SPEC_INVALID, result.status());
        assertEquals(SpecRunResult.Verdict.SPEC_INVALID, result.verdict());
        assertEquals(2, reactRuns.get());
        assertEquals(1, commandRuns.get());
        assertEquals(1, result.verificationAttempts().size());
        assertEquals(1, result.metrics().repairCount());
    }

    @Test
    void skippedHumanCriterionNeedsHumanAndRejectedCriterionFails() throws Exception {
        ChangeSpecDocument skippedDocument = codec.decode(validDocument());
        SpecRunResult skipped = coordinator(
                skippedDocument,
                new SpecVerifier(projectRoot, command -> CommandExecutionResult.completed(command, 0, "ok")),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped("later"))
                .run("修复问题");
        assertEquals(SpecRunResult.Verdict.NEEDS_HUMAN, skipped.verdict());

        Path rejectedRoot = Files.createDirectory(projectRoot.resolve("rejected"));
        ChangeSpecDocument rejectedDocument = codec.decode(validDocument());
        SpecDraftSession rejectedSession = new SpecDraftSession(
                request -> draft(rejectedDocument),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunResult rejected = new SpecRunCoordinator(
                rejectedRoot,
                rejectedSession,
                request -> request,
                (phase, input, lockedSpec) -> SpecRunCoordinator.ReActExecutionResult.completed("done"),
                new SpecVerifier(rejectedRoot, command -> CommandExecutionResult.completed(command, 0, "ok")),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.fail())
                .run("修复问题");
        assertEquals(SpecRunResult.Verdict.FAILED, rejected.verdict());
    }

    @Test
    void persistedCommandOutputIsTruncatedAndRedacted() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        String output = "API_KEY=supersecret\n" + "x".repeat(10_000);
        SpecRunCoordinator coordinator = coordinator(
                document,
                new SpecVerifier(projectRoot, command -> CommandExecutionResult.completed(command, 1, output)),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");
        String json = Files.readString(result.artifacts().resultJson());

        assertEquals(SpecRunResult.Verdict.FAILED, result.verdict());
        assertFalse(json.contains("supersecret"));
        assertTrue(json.contains("API_KEY=***"));
        assertTrue(json.contains("\"outputTruncated\" : true"));
        assertTrue(json.contains("command output truncated"));
    }

    @Test
    void persistenceFailureDoesNotRewriteAcceptanceVerdict() throws Exception {
        ChangeSpecDocument document = codec.decode(validDocument());
        Path paiDir = Files.createDirectories(projectRoot.resolve(".paicli"));
        Files.writeString(paiDir.resolve("runs"), "not a directory");
        SpecRunCoordinator coordinator = coordinator(
                document,
                new SpecVerifier(projectRoot, command -> CommandExecutionResult.completed(command, 0, "ok")),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Verdict.PASSED, result.verdict());
        assertEquals(SpecRunResult.PersistenceStatus.FAILED, result.artifacts().status());
        assertTrue(result.artifacts().detail().contains("持久化失败"));
        assertNull(result.artifacts().resultJson());
    }

    @Test
    void tamperedLockedSpecProducesSpecInvalidWithoutRunningVerifiers() throws Exception {
        ChangeSpecDocument document = codec.decode(commandDocument());
        AtomicInteger verifierCalls = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    try {
                        Files.writeString(lockedSpec.path(), "tampered");
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return SpecRunCoordinator.ReActExecutionResult.completed("done");
                },
                new SpecVerifier(projectRoot, command -> {
                    verifierCalls.incrementAndGet();
                    return CommandExecutionResult.completed(command, 0, "ok");
                }),
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.pass());

        SpecRunResult result = coordinator.run("修复问题");

        assertEquals(SpecRunResult.Status.SPEC_INVALID, result.status());
        assertEquals(SpecRunResult.Verdict.SPEC_INVALID, result.verdict());
        assertEquals(0, verifierCalls.get());
        assertTrue(Files.isRegularFile(result.artifacts().resultJson()));
    }

    private SpecRunCoordinator coordinator(ChangeSpecDocument document, AtomicBoolean executed) {
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        return new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> {
                    executed.set(true);
                    return SpecRunCoordinator.ReActExecutionResult.completed("unexpected");
                });
    }

    private SpecRunCoordinator coordinator(
            ChangeSpecDocument document,
            SpecVerifier verifier,
            SpecRunCoordinator.HumanCriterionJudge humanJudge
    ) {
        SpecDraftSession session = new SpecDraftSession(
                request -> draft(document),
                draft -> SpecDraftSession.ReviewDecision.confirm());
        return new SpecRunCoordinator(
                projectRoot,
                session,
                request -> request,
                (phase, input, lockedSpec) -> SpecRunCoordinator.ReActExecutionResult.completed("done"),
                verifier,
                humanJudge);
    }

    private static SpecDraftSession.DraftGeneration draft(ChangeSpecDocument document) {
        return SpecDraftSession.DraftGeneration.unmeasured(document);
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
                  - id: AC-SCOPE
                    kind: scope
                    statement: 修改不得超出声明的 Scope
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-SCOPE
                    type: path_scope
                ---

                # 背景

                需要修复当前问题。
                """;
    }

    private static String commandDocument() {
        return validDocument()
                .replace(
                        "type: human\n      verifiers: []",
                        "type: deterministic\n      verifiers: [VT-COMMAND]")
                .replace(
                        "  - id: VT-SCOPE\n    type: path_scope",
                        "  - id: VT-SCOPE\n"
                                + "    type: path_scope\n"
                                + "  - id: VT-COMMAND\n"
                                + "    type: command\n"
                                + "    command: echo verify\n"
                                + "    expect:\n"
                                + "      exit_code: 0");
    }

    private static String multiCommandDocument() {
        return """
                ---
                schema: paicli/change-spec/v1
                id: CHANGE-001
                revision: 1
                title: 组合验证
                intent:
                  goal: 验证多个证据
                  non_goals: []
                scope:
                  mode: open
                  include: []
                  exclude: []
                acceptance:
                  - id: AC-COMBINED
                    kind: behavior
                    statement: 两项检查共同证明行为
                    oracle:
                      type: deterministic
                      verifiers: [VT-FIRST, VT-SECOND]
                  - id: AC-HUMAN
                    kind: quality
                    statement: 人工确认质量
                    oracle:
                      type: human
                      verifiers: []
                  - id: AC-SCOPE
                    kind: scope
                    statement: 修改不得超出声明的 Scope
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-SCOPE
                    type: path_scope
                  - id: VT-FIRST
                    type: command
                    command: first
                    expect:
                      exit_code: 0
                  - id: VT-SECOND
                    type: command
                    command: second
                    expect:
                      exit_code: 0
                ---
                """;
    }
}
