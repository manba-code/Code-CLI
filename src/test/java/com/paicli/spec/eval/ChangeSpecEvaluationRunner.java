package com.paicli.spec.eval;

import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient;
import com.paicli.spec.SpecDraftGenerator;
import com.paicli.spec.SpecDraftSession;
import com.paicli.spec.SpecRunCoordinator;
import com.paicli.spec.SpecRunResult;
import com.paicli.spec.SpecVerifier;
import com.paicli.tool.CommandExecutionResult;
import com.paicli.tool.ToolRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class ChangeSpecEvaluationRunner {
    private final Supplier<LlmClient> clientFactory;
    private final Path runRoot;
    private final double inputCostPerMillion;
    private final double outputCostPerMillion;
    private final long censoredDurationMs;

    ChangeSpecEvaluationRunner(
            Supplier<LlmClient> clientFactory,
            Path runRoot,
            double inputCostPerMillion,
            double outputCostPerMillion,
            long censoredDurationMs
    ) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.runRoot = Objects.requireNonNull(runRoot, "runRoot").toAbsolutePath().normalize();
        this.inputCostPerMillion = inputCostPerMillion;
        this.outputCostPerMillion = outputCostPerMillion;
        this.censoredDurationMs = Math.max(1L, censoredDurationMs);
    }

    ChangeSpecPairedDraft preparePairedDraft(ChangeSpecEvaluationCase evaluationCase, int repetition) {
        ChangeSpecEvaluationLlmClient client = new ChangeSpecEvaluationLlmClient(clientFactory.get());
        ChangeSpecDraftDiagnostic diagnostic = new ChangeSpecDraftDiagnostic();
        long startedAt = System.nanoTime();
        try {
            SpecDraftSession.DraftGeneration generation = new SpecDraftGenerator(client, diagnostic)
                    .generateWithMetrics(evaluationCase.task(), evaluationCase.draftContext(), "");
            return new ChangeSpecPairedDraft(
                    generation.document(),
                    generation.llmUsage(),
                    generation.durationMs(),
                    "",
                    null);
        } catch (Exception e) {
            Path diagnosticFile = null;
            String error = messageOf(e);
            try {
                diagnosticFile = diagnostic.write(runRoot, evaluationCase.id(), repetition, error);
            } catch (IOException diagnosticError) {
                error = join(error, "Draft 诊断保存失败: " + messageOf(diagnosticError));
            }
            return new ChangeSpecPairedDraft(
                    null,
                    usage(client),
                    elapsedMillis(startedAt),
                    error,
                    diagnosticFile);
        }
    }

    ChangeSpecEvaluationResult run(
            ChangeSpecEvaluationCase evaluationCase,
            ChangeSpecEvaluationMode mode,
            int repetition,
            ChangeSpecPairedDraft pairedDraft
    ) throws Exception {
        String suffix = evaluationCase.id() + "-r" + repetition + "-" + mode.name().toLowerCase();
        Path workspace = uniqueDirectory(runRoot.resolve("workspaces"), suffix);
        Path firstPass = runRoot.resolve("first-pass").resolve(workspace.getFileName().toString());
        evaluationCase.materialize(workspace);
        ChangeSpecEvaluationCase.WorkspaceSnapshot baseline = evaluationCase.snapshot(workspace);
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        ProductExecution product;
        long productStartedAt = System.nanoTime();
        try (PrintStream out = new PrintStream(transcript, true, StandardCharsets.UTF_8)) {
            product = withIsolatedMemory(workspace, () -> mode == ChangeSpecEvaluationMode.REACT
                    ? runReact(evaluationCase, workspace, firstPass, out)
                    : runChangeSpec(evaluationCase, mode, pairedDraft, workspace, firstPass, out));
        } catch (Exception e) {
            boolean usesDraft = mode.usesChangeSpec() && pairedDraft != null;
            product = ProductExecution.failed(
                    usesDraft ? pairedDraft.usage() : SpecRunResult.LlmUsage.empty(),
                    Math.max(usesDraft ? pairedDraft.durationMs() : 0L, elapsedMillis(productStartedAt)),
                    messageOf(e));
        }
        Files.writeString(workspace.resolve("run.log"), transcript.toString(StandardCharsets.UTF_8));

        ChangeSpecEvaluationCase.ValidationResult firstValidation = null;
        String snapshotError = product.snapshotError();
        if (Files.isDirectory(firstPass)) {
            try {
                firstValidation = evaluationCase.verify(firstPass, baseline);
            } catch (Exception e) {
                snapshotError = join(snapshotError, "首次候选隐藏 Oracle 异常: " + messageOf(e));
            }
        } else {
            snapshotError = join(snapshotError, "未形成首次验证候选快照");
        }

        ChangeSpecEvaluationCase.ValidationResult finalValidation;
        try {
            finalValidation = evaluationCase.verify(workspace, baseline);
        } catch (Exception e) {
            finalValidation = failedValidation("最终隐藏 Oracle 异常: " + messageOf(e));
        }

        boolean taskSuccess = finalValidation.passed();
        boolean firstPassSuccess = firstValidation != null && firstValidation.passed();
        long oracleDurationMs = finalValidation.oracleDurationMs();
        long timeToAccepted = taskSuccess
                ? product.durationMs() + oracleDurationMs
                : censoredDurationMs;
        SpecRunResult.LlmUsage usage = product.usage();
        double cost = usage.inputTokens() * inputCostPerMillion / 1_000_000d
                + usage.outputTokens() * outputCostPerMillion / 1_000_000d;
        String detail = join(finalValidation.detail(), snapshotError);

        return new ChangeSpecEvaluationResult(
                evaluationCase.id(),
                evaluationCase.tier(),
                mode,
                repetition,
                taskSuccess,
                firstPassSuccess,
                product.completionClaimed(),
                mode.usesChangeSpec(),
                product.acceptancePassed(),
                !finalValidation.unexpectedFiles().isEmpty(),
                product.publicVerdict(),
                product.repairCount(),
                usage.calls(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                product.durationMs(),
                oracleDurationMs,
                timeToAccepted,
                cost,
                product.specDigest(),
                detail,
                product.error(),
                workspace,
                mode.usesChangeSpec() && pairedDraft != null ? pairedDraft.diagnosticFile() : null);
    }

    private ProductExecution runReact(
            ChangeSpecEvaluationCase evaluationCase,
            Path workspace,
            Path firstPass,
            PrintStream out
    ) throws IOException {
        ChangeSpecEvaluationLlmClient client = new ChangeSpecEvaluationLlmClient(clientFactory.get());
        ToolRegistry registry = registry(workspace);
        Agent agent = agent(client, registry, out);
        long startedAt = System.nanoTime();
        Agent.RunResult result = agent.runDetailed(evaluationCase.task());
        String snapshotError = copyFirstPass(evaluationCase, workspace, firstPass);
        long durationMs = Math.max(result.elapsedMs(), elapsedMillis(startedAt));
        boolean completed = result.outcome() == Agent.RunOutcome.COMPLETED;
        return new ProductExecution(
                completed,
                false,
                result.outcome().name(),
                0,
                usage(result),
                durationMs,
                "",
                snapshotError,
                completed ? "" : result.response());
    }

    private ProductExecution runChangeSpec(
            ChangeSpecEvaluationCase evaluationCase,
            ChangeSpecEvaluationMode mode,
            ChangeSpecPairedDraft pairedDraft,
            Path workspace,
            Path firstPass,
            PrintStream out
    ) throws IOException {
        if (pairedDraft == null || !pairedDraft.available()) {
            String snapshotError = copyFirstPass(evaluationCase, workspace, firstPass);
            SpecRunResult.LlmUsage draftUsage = pairedDraft == null
                    ? SpecRunResult.LlmUsage.empty()
                    : pairedDraft.usage();
            return new ProductExecution(
                    false,
                    false,
                    "DRAFT_INVALID",
                    0,
                    draftUsage,
                    pairedDraft == null ? 0L : pairedDraft.durationMs(),
                    "",
                    snapshotError,
                    pairedDraft == null ? "配对 Draft 缺失" : pairedDraft.error());
        }

        ChangeSpecEvaluationLlmClient client = new ChangeSpecEvaluationLlmClient(clientFactory.get());
        ToolRegistry registry = registry(workspace);
        Agent agent = agent(client, registry, out);
        AtomicReference<String> snapshotError = new AtomicReference<>("");
        SpecDraftSession session = new SpecDraftSession(
                request -> pairedDraft.asGeneration(),
                document -> SpecDraftSession.ReviewDecision.confirm());
        SpecRunCoordinator.RepairPolicy repairPolicy = mode == ChangeSpecEvaluationMode.SPEC_WITH_REPAIR
                ? SpecRunCoordinator.RepairPolicy.ENABLED
                : SpecRunCoordinator.RepairPolicy.DISABLED;
        SpecRunCoordinator.RunOptions options = new SpecRunCoordinator.RunOptions(
                repairPolicy,
                attempt -> {
                    if (attempt.attempt() == 1) {
                        snapshotError.set(copyFirstPass(evaluationCase, workspace, firstPass));
                    }
                });
        SpecVerifier verifier = new SpecVerifier(workspace, command -> {
            if (!evaluationCase.isAllowedVerifierCommand(command)) {
                return CommandExecutionResult.denied(
                        command,
                        CommandExecutionResult.Status.HITL_DENIED,
                        "自动评测只批准预声明的公开 Verifier 命令");
            }
            return registry.executeCommandForVerification(command);
        });
        SpecRunCoordinator coordinator = new SpecRunCoordinator(
                workspace,
                session,
                request -> request,
                (phase, input, lockedSpec) -> toSpecExecution(agent.runDetailed(input)),
                verifier,
                (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped(
                        "自动评测不替代人工判断"),
                options);

        SpecRunResult result = coordinator.run(evaluationCase.task());
        SpecRunResult.Metrics metrics = result.metrics();
        boolean passed = result.verdict() == SpecRunResult.Verdict.PASSED;
        return new ProductExecution(
                passed,
                passed,
                result.verdict().name(),
                metrics.repairCount(),
                metrics.totalLlmUsage(),
                metrics.totalMs(),
                result.identity() == null ? "" : result.identity().specDigest(),
                snapshotError.get(),
                result.detail());
    }

    private static Agent agent(
            ChangeSpecEvaluationLlmClient client,
            ToolRegistry registry,
            PrintStream out
    ) {
        Agent agent = new Agent(client, registry);
        agent.setRenderer(new ChangeSpecEvaluationRenderer(out));
        agent.setReturnFinalResponseWhenStreamed(true);
        return agent;
    }

    private static ToolRegistry registry(Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toAbsolutePath().normalize().toString());
        return registry;
    }

    private static SpecRunCoordinator.ReActExecutionResult toSpecExecution(Agent.RunResult result) {
        SpecRunResult.LlmUsage usage = usage(result);
        return switch (result.outcome()) {
            case COMPLETED -> SpecRunCoordinator.ReActExecutionResult.completed(
                    result.response(), usage, result.elapsedMs());
            case CANCELED -> SpecRunCoordinator.ReActExecutionResult.canceled(
                    result.response(), usage, result.elapsedMs());
            case FAILED -> SpecRunCoordinator.ReActExecutionResult.failed(
                    result.response(), usage, result.elapsedMs());
        };
    }

    private static SpecRunResult.LlmUsage usage(Agent.RunResult result) {
        return new SpecRunResult.LlmUsage(
                result.llmCalls(),
                result.inputTokens(),
                result.outputTokens(),
                result.cachedInputTokens());
    }

    private static SpecRunResult.LlmUsage usage(ChangeSpecEvaluationLlmClient client) {
        return new SpecRunResult.LlmUsage(
                client.calls(), client.inputTokens(), client.outputTokens(), client.cachedInputTokens());
    }

    private static String copyFirstPass(
            ChangeSpecEvaluationCase evaluationCase,
            Path workspace,
            Path firstPass
    ) {
        try {
            evaluationCase.copyCandidate(workspace, firstPass);
            return "";
        } catch (IOException e) {
            return "首次候选快照失败: " + messageOf(e);
        }
    }

    private static ChangeSpecEvaluationCase.ValidationResult failedValidation(String detail) {
        return new ChangeSpecEvaluationCase.ValidationResult(
                false,
                0,
                2,
                detail,
                java.util.Set.of(),
                java.util.Set.of(),
                new ChangeSpecEvaluationCase.CommandResult(-1, false, detail),
                0L);
    }

    private static Path uniqueDirectory(Path parent, String prefix) throws IOException {
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, prefix + "-");
    }

    private static <T> T withIsolatedMemory(Path workspace, CheckedSupplier<T> supplier) throws Exception {
        String property = "paicli.memory.dir";
        String previous = System.getProperty(property);
        System.setProperty(property, workspace.resolve(".eval-memory").toString());
        try {
            return supplier.get();
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
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

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + "；" + second;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private record ProductExecution(
            boolean completionClaimed,
            boolean acceptancePassed,
            String publicVerdict,
            int repairCount,
            SpecRunResult.LlmUsage usage,
            long durationMs,
            String specDigest,
            String snapshotError,
            String error
    ) {
        private ProductExecution {
            publicVerdict = publicVerdict == null ? "" : publicVerdict;
            usage = usage == null ? SpecRunResult.LlmUsage.empty() : usage;
            durationMs = Math.max(0L, durationMs);
            specDigest = specDigest == null ? "" : specDigest;
            snapshotError = snapshotError == null ? "" : snapshotError;
            error = error == null ? "" : error;
        }

        static ProductExecution failed(SpecRunResult.LlmUsage usage, long durationMs, String error) {
            return new ProductExecution(false, false, "ERROR", 0, usage, durationMs, "", "", error);
        }
    }
}
