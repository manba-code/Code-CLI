package com.paicli.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * 将确认后的 ChangeSpec 锁定、交给 ReAct、验证、逐条判断 Criterion，并持久化最终运行结果。
 */
public final class SpecRunCoordinator {
    private static final String SPECS_DIR = ".paicli/specs";
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final Path projectRoot;
    private final SpecDraftSession draftSession;
    private final UnaryOperator<String> confirmedRequestExpander;
    private final ReActExecutor reactExecutor;
    private final ChangeSpecCodec codec;
    private final WorkspaceChangeTracker workspaceTracker;
    private final SpecVerifier verifier;
    private final HumanCriterionJudge humanCriterionJudge;
    private final SpecRunStore runStore;
    private final Clock clock;

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
                new SpecVerifier(
                        projectRoot,
                        command -> com.paicli.tool.CommandExecutionResult.startError(
                                command,
                                "未配置 command Verifier 执行器")),
                (criterion, changes) -> HumanJudgment.skipped("未配置 Human Criterion 交互"));
    }

    public SpecRunCoordinator(
            Path projectRoot,
            SpecDraftSession draftSession,
            UnaryOperator<String> confirmedRequestExpander,
            ReActExecutor reactExecutor,
            SpecVerifier verifier
    ) {
        this(
                projectRoot,
                draftSession,
                confirmedRequestExpander,
                reactExecutor,
                verifier,
                (criterion, changes) -> HumanJudgment.skipped("未配置 Human Criterion 交互"));
    }

    public SpecRunCoordinator(
            Path projectRoot,
            SpecDraftSession draftSession,
            UnaryOperator<String> confirmedRequestExpander,
            ReActExecutor reactExecutor,
            SpecVerifier verifier,
            HumanCriterionJudge humanCriterionJudge
    ) {
        this(
                projectRoot,
                draftSession,
                confirmedRequestExpander,
                reactExecutor,
                new ChangeSpecCodec(),
                new WorkspaceChangeTracker(projectRoot),
                verifier,
                humanCriterionJudge,
                new SpecRunStore(projectRoot),
                Clock.systemUTC());
    }

    SpecRunCoordinator(
            Path projectRoot,
            SpecDraftSession draftSession,
            UnaryOperator<String> confirmedRequestExpander,
            ReActExecutor reactExecutor,
            ChangeSpecCodec codec,
            WorkspaceChangeTracker workspaceTracker,
            SpecVerifier verifier,
            HumanCriterionJudge humanCriterionJudge,
            SpecRunStore runStore,
            Clock clock
    ) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.draftSession = Objects.requireNonNull(draftSession, "draftSession");
        this.confirmedRequestExpander = Objects.requireNonNull(
                confirmedRequestExpander,
                "confirmedRequestExpander");
        this.reactExecutor = Objects.requireNonNull(reactExecutor, "reactExecutor");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.workspaceTracker = Objects.requireNonNull(workspaceTracker, "workspaceTracker");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.humanCriterionJudge = Objects.requireNonNull(humanCriterionJudge, "humanCriterionJudge");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SpecRunResult run(String request) throws IOException {
        long runStartedAt = System.nanoTime();
        SpecDraftSession.Result review = draftSession.run(request);
        if (review.status() == SpecDraftSession.Status.CANCELED) {
            return SpecRunResult.canceled(metrics(
                    review,
                    0L,
                    0L,
                    0L,
                    runStartedAt,
                    SpecRunResult.LlmUsage.empty()));
        }

        ChangeSpecDocument document = Objects.requireNonNull(review.document(), "confirmed document");
        String confirmedRequest = Objects.requireNonNull(review.confirmedRequest(), "confirmed request");
        LockedSpec lockedSpec = lock(document);
        SpecRunResult.RunIdentity identity = new SpecRunResult.RunIdentity(
                createRunId(),
                lockedSpec.specId(),
                lockedSpec.revision(),
                lockedSpec.specDigest(),
                lockedSpec.path());
        WorkspaceChangeTracker.Baseline baseline;
        String executionInput;
        try {
            baseline = workspaceTracker.captureBaseline();
            String expandedRequest = Objects.requireNonNull(
                    confirmedRequestExpander.apply(confirmedRequest),
                    "expanded confirmed request");
            executionInput = buildExecutionInput(expandedRequest, document);
        } catch (IOException | RuntimeException e) {
            SpecRunResult result = new SpecRunResult(
                    SpecRunResult.Status.PREPARATION_FAILED,
                    identity,
                    null,
                    new WorkspaceChangeTracker.WorkspaceChanges(List.of(), "", false),
                    List.of(),
                    notRunCriteria(document.spec(), "运行准备失败，未启动 ReAct"),
                    List.of(),
                    SpecRunResult.Verdict.INCOMPLETE,
                    metrics(review, 0L, 0L, 0L, runStartedAt, SpecRunResult.LlmUsage.empty()),
                    SpecRunResult.Artifacts.notApplicable(),
                    "运行准备失败: " + messageOf(e));
            return persist(result);
        }

        long reactStartedAt = System.nanoTime();
        ReActExecutionResult execution;
        try {
            execution = Objects.requireNonNull(
                    reactExecutor.run(executionInput, lockedSpec),
                    "react execution result");
        } catch (RuntimeException e) {
            execution = ReActExecutionResult.failed(
                    "❌ 执行失败: " + messageOf(e),
                    SpecRunResult.LlmUsage.empty(),
                    elapsedMillis(reactStartedAt));
        }
        long reactMs = Math.max(execution.elapsedMs(), elapsedMillis(reactStartedAt));

        String lockedSpecError = validateLockedSpec(lockedSpec);
        if (lockedSpecError != null) {
            WorkspaceCapture capture = collectWorkspace(baseline);
            SpecRunResult result = new SpecRunResult(
                    SpecRunResult.Status.SPEC_INVALID,
                    identity,
                    execution.response(),
                    capture.changes(),
                    List.of(),
                    notRunCriteria(document.spec(), "锁定的 ChangeSpec 身份无效，未运行验收"),
                    List.of(),
                    SpecRunResult.Verdict.SPEC_INVALID,
                    metrics(review, reactMs, 0L, 0L, runStartedAt, execution.llmUsage()),
                    SpecRunResult.Artifacts.notApplicable(),
                    joinDetails(lockedSpecError, capture.error()));
            return persist(result);
        }

        if (execution.status() != ReActStatus.COMPLETED) {
            WorkspaceCapture capture = collectWorkspace(baseline);
            SpecRunResult.Status status = execution.status() == ReActStatus.CANCELED
                    ? SpecRunResult.Status.REACT_CANCELED
                    : SpecRunResult.Status.REACT_FAILED;
            List<SpecRunResult.CriterionResult> criteria = notRunCriteria(
                    document.spec(),
                    execution.status() == ReActStatus.CANCELED
                            ? "ReAct 已取消，未运行验收"
                            : "ReAct 执行失败，未运行验收");
            SpecRunResult result = new SpecRunResult(
                    status,
                    identity,
                    execution.response(),
                    capture.changes(),
                    List.of(),
                    criteria,
                    List.of(),
                    SpecRunResult.Verdict.INCOMPLETE,
                    metrics(review, reactMs, 0L, 0L, runStartedAt, execution.llmUsage()),
                    SpecRunResult.Artifacts.notApplicable(),
                    capture.error());
            return persist(result);
        }

        long verificationStartedAt = System.nanoTime();
        SpecVerifier.VerificationRun verification;
        try {
            verification = verifier.verify(
                    document.spec(),
                    workspaceTracker,
                    baseline);
        } catch (RuntimeException e) {
            long verificationMs = elapsedMillis(verificationStartedAt);
            WorkspaceCapture capture = collectWorkspace(baseline);
            String verificationError = "Verifier 流程异常: " + messageOf(e);
            SpecRunResult result = new SpecRunResult(
                    SpecRunResult.Status.VERIFICATION_FAILED,
                    identity,
                    execution.response(),
                    capture.changes(),
                    List.of(),
                    notRunCriteria(document.spec(), verificationError),
                    List.of(),
                    SpecRunResult.Verdict.INCOMPLETE,
                    metrics(review, reactMs, verificationMs, 0L, runStartedAt, execution.llmUsage()),
                    SpecRunResult.Artifacts.notApplicable(),
                    joinDetails(verificationError, capture.error()));
            return persist(result);
        }
        long verificationMs = elapsedMillis(verificationStartedAt);

        CriterionEvaluation evaluation = evaluateCriteria(
                document.spec(),
                verification.verifierResults(),
                verification.workspaceChanges());
        SpecRunResult result = new SpecRunResult(
                SpecRunResult.Status.FINISHED,
                identity,
                execution.response(),
                verification.workspaceChanges(),
                verification.verifierResults(),
                evaluation.criterionResults(),
                evaluation.humanEvidence(),
                reduceVerdict(evaluation.criterionResults()),
                metrics(
                        review,
                        reactMs,
                        verificationMs,
                        evaluation.humanDurationMs(),
                        runStartedAt,
                        execution.llmUsage()),
                SpecRunResult.Artifacts.notApplicable(),
                "");
        return persist(result);
    }

    private CriterionEvaluation evaluateCriteria(
            ChangeSpec spec,
            List<SpecVerifier.VerifierResult> verifierResults,
            WorkspaceChangeTracker.WorkspaceChanges changes
    ) {
        Map<String, SpecVerifier.VerifierResult> verifierById = new LinkedHashMap<>();
        for (SpecVerifier.VerifierResult verifierResult : verifierResults) {
            verifierById.put(verifierResult.verifierId(), verifierResult);
        }

        Map<String, SpecRunResult.CriterionResult> resultsById = new LinkedHashMap<>();
        for (ChangeSpec.AcceptanceCriterion criterion : spec.acceptance()) {
            if (criterion.oracle().type() == ChangeSpec.OracleType.DETERMINISTIC) {
                resultsById.put(criterion.id(), evaluateDeterministic(criterion, verifierById));
            }
        }

        boolean deterministicPassed = resultsById.values().stream()
                .allMatch(result -> result.status() == SpecRunResult.CriterionStatus.PASS);
        boolean humanHalted = false;
        String haltReason = null;
        long humanDurationMs = 0L;
        List<SpecRunResult.HumanEvidence> humanEvidence = new ArrayList<>();

        for (ChangeSpec.AcceptanceCriterion criterion : spec.acceptance()) {
            if (criterion.oracle().type() != ChangeSpec.OracleType.HUMAN) {
                continue;
            }
            if (!deterministicPassed) {
                resultsById.put(criterion.id(), notRunHuman(
                        criterion,
                        "确定性 Criterion 未全部通过，未进入人工判断"));
                continue;
            }
            if (humanHalted) {
                resultsById.put(criterion.id(), notRunHuman(criterion, haltReason));
                continue;
            }

            long startedAt = System.nanoTime();
            HumanJudgment judgment;
            try {
                judgment = Objects.requireNonNull(
                        humanCriterionJudge.judge(criterion, changes),
                        "human judgment");
            } catch (RuntimeException e) {
                judgment = HumanJudgment.skipped("人工判断交互失败: " + messageOf(e));
            }
            long durationMs = elapsedMillis(startedAt);
            humanDurationMs += durationMs;
            String evidenceId = "human:" + criterion.id();
            humanEvidence.add(new SpecRunResult.HumanEvidence(
                    evidenceId,
                    criterion.id(),
                    judgment.decision(),
                    durationMs,
                    judgment.reason()));

            SpecRunResult.CriterionStatus status = switch (judgment.decision()) {
                case PASS -> SpecRunResult.CriterionStatus.PASS;
                case FAIL -> SpecRunResult.CriterionStatus.FAIL;
                case SKIPPED -> SpecRunResult.CriterionStatus.NOT_RUN;
            };
            resultsById.put(criterion.id(), new SpecRunResult.CriterionResult(
                    criterion.id(),
                    status,
                    List.of(evidenceId),
                    SpecRunResult.Judge.HUMAN,
                    judgment.reason()));
            if (judgment.decision() == SpecRunResult.HumanDecision.FAIL) {
                humanHalted = true;
                haltReason = "前一条 Human Criterion 已拒绝，后续判断未运行";
            } else if (judgment.decision() == SpecRunResult.HumanDecision.SKIPPED) {
                humanHalted = true;
                haltReason = "前一条 Human Criterion 已跳过，后续判断未运行";
            }
        }

        List<SpecRunResult.CriterionResult> ordered = spec.acceptance().stream()
                .map(criterion -> resultsById.get(criterion.id()))
                .toList();
        return new CriterionEvaluation(ordered, humanEvidence, humanDurationMs);
    }

    private static SpecRunResult.CriterionResult evaluateDeterministic(
            ChangeSpec.AcceptanceCriterion criterion,
            Map<String, SpecVerifier.VerifierResult> verifierById
    ) {
        List<String> evidenceIds = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        for (String verifierId : criterion.oracle().verifiers()) {
            SpecVerifier.VerifierResult verifier = verifierById.get(verifierId);
            if (verifier == null) {
                inconclusive.add(verifierId + " 缺少结果");
                continue;
            }
            evidenceIds.add("verifier:" + verifierId);
            if (verifier.status() == SpecVerifier.Status.FAIL) {
                failures.add(verifierId + ": " + verifier.detail());
            } else if (verifier.status() == SpecVerifier.Status.ERROR) {
                inconclusive.add(verifierId + ": " + verifier.detail());
            }
        }
        if (!failures.isEmpty()) {
            return new SpecRunResult.CriterionResult(
                    criterion.id(),
                    SpecRunResult.CriterionStatus.FAIL,
                    evidenceIds,
                    SpecRunResult.Judge.VERIFIER,
                    "Verifier 证明条件不满足: " + String.join("；", failures));
        }
        if (!inconclusive.isEmpty()) {
            return new SpecRunResult.CriterionResult(
                    criterion.id(),
                    SpecRunResult.CriterionStatus.INCONCLUSIVE,
                    evidenceIds,
                    SpecRunResult.Judge.VERIFIER,
                    "未获得完整有效证据: " + String.join("；", inconclusive));
        }
        return new SpecRunResult.CriterionResult(
                criterion.id(),
                SpecRunResult.CriterionStatus.PASS,
                evidenceIds,
                SpecRunResult.Judge.VERIFIER,
                "引用的 Verifier 全部通过");
    }

    private static SpecRunResult.Verdict reduceVerdict(List<SpecRunResult.CriterionResult> results) {
        if (results.stream().anyMatch(result -> result.status() == SpecRunResult.CriterionStatus.FAIL)) {
            return SpecRunResult.Verdict.FAILED;
        }
        if (results.stream().anyMatch(result -> result.status() == SpecRunResult.CriterionStatus.INCONCLUSIVE)) {
            return SpecRunResult.Verdict.INCOMPLETE;
        }
        if (results.stream().anyMatch(result -> result.status() == SpecRunResult.CriterionStatus.NOT_RUN
                && result.judge() == SpecRunResult.Judge.VERIFIER)) {
            return SpecRunResult.Verdict.INCOMPLETE;
        }
        if (results.stream().anyMatch(result -> result.status() == SpecRunResult.CriterionStatus.NOT_RUN
                && result.judge() == SpecRunResult.Judge.HUMAN)) {
            return SpecRunResult.Verdict.NEEDS_HUMAN;
        }
        return results.isEmpty() ? SpecRunResult.Verdict.INCOMPLETE : SpecRunResult.Verdict.PASSED;
    }

    private static List<SpecRunResult.CriterionResult> notRunCriteria(ChangeSpec spec, String reason) {
        return spec.acceptance().stream()
                .map(criterion -> new SpecRunResult.CriterionResult(
                        criterion.id(),
                        SpecRunResult.CriterionStatus.NOT_RUN,
                        List.of(),
                        criterion.oracle().type() == ChangeSpec.OracleType.HUMAN
                                ? SpecRunResult.Judge.HUMAN
                                : SpecRunResult.Judge.VERIFIER,
                        reason))
                .toList();
    }

    private static SpecRunResult.CriterionResult notRunHuman(
            ChangeSpec.AcceptanceCriterion criterion,
            String reason
    ) {
        return new SpecRunResult.CriterionResult(
                criterion.id(),
                SpecRunResult.CriterionStatus.NOT_RUN,
                List.of(),
                SpecRunResult.Judge.HUMAN,
                reason);
    }

    private SpecRunResult persist(SpecRunResult result) {
        Path expectedRunDir = projectRoot.resolve(".paicli/runs").resolve(result.identity().runId()).normalize();
        try {
            return result.withArtifacts(result.status(), runStore.persist(result), result.detail());
        } catch (IOException e) {
            String persistenceError = "运行结果持久化失败: " + messageOf(e);
            String detail = result.detail().isBlank()
                    ? persistenceError
                    : result.detail() + "；" + persistenceError;
            return result.withArtifacts(
                    result.status(),
                    SpecRunResult.Artifacts.failed(expectedRunDir, persistenceError),
                    detail);
        }
    }

    private WorkspaceCapture collectWorkspace(WorkspaceChangeTracker.Baseline baseline) {
        try {
            return new WorkspaceCapture(workspaceTracker.collectChanges(baseline), "");
        } catch (IOException e) {
            return new WorkspaceCapture(
                    new WorkspaceChangeTracker.WorkspaceChanges(List.of(), "", false),
                    "无法采集最终 workspace: " + messageOf(e));
        }
    }

    private SpecRunResult.Metrics metrics(
            SpecDraftSession.Result review,
            long reactMs,
            long verificationMs,
            long humanMs,
            long runStartedAt,
            SpecRunResult.LlmUsage reactUsage
    ) {
        return new SpecRunResult.Metrics(
                review.generationMs(),
                review.confirmationMs(),
                reactMs,
                verificationMs,
                humanMs,
                elapsedMillis(runStartedAt),
                review.llmUsage(),
                reactUsage);
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
                你可以把测试作为实现工作的一部分运行。ReAct 正常结束后系统会运行锁定 Spec 中的确定性 Verifier；你的最终回答不是验收 Verdict，不得把自述称为验收通过，也不得生成 PASSED Verdict。
                """.formatted(
                confirmedRequest,
                document.spec().id(),
                document.spec().revision(),
                document.specDigest(),
                machineContract);
    }

    private String createRunId() {
        return "RUN-" + RUN_TIME.format(Instant.now(clock)) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String validateLockedSpec(LockedSpec lockedSpec) {
        try {
            ChangeSpecDocument saved = codec.decode(Files.readString(lockedSpec.path(), StandardCharsets.UTF_8));
            if (!lockedSpec.specId().equals(saved.spec().id())
                    || lockedSpec.revision() != saved.spec().revision()
                    || !lockedSpec.specDigest().equals(saved.specDigest())) {
                return "锁定的 ChangeSpec 的 specId、revision 或 digest 已发生变化";
            }
            return null;
        } catch (Exception e) {
            return "锁定的 ChangeSpec 无法回读验证: " + messageOf(e);
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

    private static void moveWithoutReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static String joinDetails(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "；" + second;
    }

    @FunctionalInterface
    public interface ReActExecutor {
        ReActExecutionResult run(String executionInput, LockedSpec lockedSpec);
    }

    @FunctionalInterface
    public interface HumanCriterionJudge {
        HumanJudgment judge(
                ChangeSpec.AcceptanceCriterion criterion,
                WorkspaceChangeTracker.WorkspaceChanges changes);
    }

    public record HumanJudgment(SpecRunResult.HumanDecision decision, String reason) {
        public HumanJudgment {
            Objects.requireNonNull(decision, "decision");
            reason = reason == null ? "" : reason;
        }

        public static HumanJudgment pass() {
            return new HumanJudgment(SpecRunResult.HumanDecision.PASS, "人工确认通过");
        }

        public static HumanJudgment fail() {
            return new HumanJudgment(SpecRunResult.HumanDecision.FAIL, "人工确认不满足要求");
        }

        public static HumanJudgment skipped(String reason) {
            return new HumanJudgment(SpecRunResult.HumanDecision.SKIPPED, reason);
        }
    }

    public record LockedSpec(Path path, String specId, int revision, String specDigest) {
    }

    public record ReActExecutionResult(
            ReActStatus status,
            String response,
            SpecRunResult.LlmUsage llmUsage,
            long elapsedMs
    ) {
        public ReActExecutionResult {
            status = Objects.requireNonNull(status, "status");
            llmUsage = llmUsage == null ? SpecRunResult.LlmUsage.empty() : llmUsage;
            elapsedMs = Math.max(0L, elapsedMs);
        }

        public static ReActExecutionResult completed(String response) {
            return completed(response, SpecRunResult.LlmUsage.empty(), 0L);
        }

        public static ReActExecutionResult completed(
                String response,
                SpecRunResult.LlmUsage usage,
                long elapsedMs
        ) {
            return new ReActExecutionResult(ReActStatus.COMPLETED, response, usage, elapsedMs);
        }

        public static ReActExecutionResult canceled(String response) {
            return canceled(response, SpecRunResult.LlmUsage.empty(), 0L);
        }

        public static ReActExecutionResult canceled(
                String response,
                SpecRunResult.LlmUsage usage,
                long elapsedMs
        ) {
            return new ReActExecutionResult(ReActStatus.CANCELED, response, usage, elapsedMs);
        }

        public static ReActExecutionResult failed(String response) {
            return failed(response, SpecRunResult.LlmUsage.empty(), 0L);
        }

        public static ReActExecutionResult failed(
                String response,
                SpecRunResult.LlmUsage usage,
                long elapsedMs
        ) {
            return new ReActExecutionResult(ReActStatus.FAILED, response, usage, elapsedMs);
        }
    }

    public enum ReActStatus {
        COMPLETED,
        CANCELED,
        FAILED
    }

    private record CriterionEvaluation(
            List<SpecRunResult.CriterionResult> criterionResults,
            List<SpecRunResult.HumanEvidence> humanEvidence,
            long humanDurationMs
    ) {
    }

    private record WorkspaceCapture(WorkspaceChangeTracker.WorkspaceChanges changes, String error) {
    }
}
