package com.paicli.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.tool.CommandExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 将运行结果写成两个紧凑、不可覆盖的本地产物。 */
final class SpecRunStore {
    static final int MAX_COMMAND_OUTPUT_CHARS = 8 * 1024;
    private static final String RUNS_DIR = ".paicli/runs";
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)(--?(?:api[_-]?key|authorization|password|passwd|secret|token)\\s+)(\\S+)"),
            Pattern.compile("(?i)((?:api[_-]?key|authorization|password|passwd|secret|token)\\s*[=:]\\s*)(\\S+)"),
            Pattern.compile("(?i)(\\bbearer\\s+)(\\S+)"));

    private final Path projectRoot;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    SpecRunStore(Path projectRoot) {
        this(projectRoot, Clock.systemUTC());
    }

    SpecRunStore(Path projectRoot, Clock clock) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    SpecRunResult.Artifacts persist(SpecRunResult result) throws IOException {
        Objects.requireNonNull(result, "result");
        SpecRunResult.RunIdentity identity = Objects.requireNonNull(result.identity(), "result.identity");
        WorkspaceChangeTracker.WorkspaceChanges changes = Objects.requireNonNull(
                result.workspaceChanges(),
                "result.workspaceChanges");

        Path runsDir = projectRoot.resolve(RUNS_DIR).normalize();
        if (!runsDir.startsWith(projectRoot)) {
            throw new IOException("ChangeSpec 运行目录超出项目根目录");
        }
        Files.createDirectories(runsDir);
        Path runDir = runsDir.resolve(identity.runId()).normalize();
        if (!runsDir.equals(runDir.getParent())) {
            throw new IOException("runId 不能用于安全目录名: " + identity.runId());
        }
        Files.createDirectory(runDir);

        Path diffPath = runDir.resolve("change.diff");
        Path resultPath = runDir.resolve("result.json");
        writeAtomic(diffPath, diffArtifact(result));
        writeAtomic(resultPath, mapper.writeValueAsString(toJson(result)) + System.lineSeparator());
        return SpecRunResult.Artifacts.saved(runDir, resultPath, diffPath);
    }

    private static String diffArtifact(SpecRunResult result) {
        SpecRunResult.RunIdentity identity = result.identity();
        return """
                # PaiCLI ChangeSpec Evidence
                # runId: %s
                # specId: %s
                # revision: %d
                # specDigest: %s

                %s""".formatted(
                identity.runId(),
                identity.specId(),
                identity.revision(),
                identity.specDigest(),
                result.workspaceChanges().diff());
    }

    private Map<String, Object> toJson(SpecRunResult result) {
        SpecRunResult.RunIdentity identity = result.identity();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "paicli/spec-run-result/v1");
        root.put("runId", identity.runId());
        root.put("createdAt", Instant.now(clock).toString());
        root.put("status", result.status().name());
        root.put("verdict", result.verdict() == null ? null : result.verdict().name());
        root.put("spec", mapOf(
                "id", identity.specId(),
                "revision", identity.revision(),
                "digest", identity.specDigest(),
                "lockedPath", projectRelative(identity.lockedSpecPath())));

        WorkspaceChangeTracker.WorkspaceChanges changes = result.workspaceChanges();
        root.put("workspace", mapOf(
                "changedFiles", changes.changedFiles(),
                "diffFile", "change.diff",
                "diffTruncated", changes.diffTruncated()));
        root.put("verifierResults", result.verifierResults().stream().map(this::verifierJson).toList());
        root.put("criterionResults", result.criterionResults().stream().map(this::criterionJson).toList());
        root.put("humanEvidence", result.humanEvidence().stream().map(this::humanEvidenceJson).toList());
        root.put("metrics", metricsJson(result.metrics()));
        if (!result.detail().isBlank()) {
            root.put("detail", result.detail());
        }
        return root;
    }

    private Map<String, Object> verifierJson(SpecVerifier.VerifierResult verifier) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("evidenceId", evidenceId(verifier.verifierId()));
        json.put("verifierId", verifier.verifierId());
        json.put("type", verifier.type().name().toLowerCase(java.util.Locale.ROOT));
        json.put("status", verifier.status().name());
        json.put("detail", verifier.detail());
        if (verifier.commandResult() != null) {
            CommandExecutionResult command = verifier.commandResult();
            OutputSummary summary = verifier.status() == SpecVerifier.Status.PASS
                    ? new OutputSummary("", false)
                    : summarizeOutput(command.output());
            json.put("command", mapOf(
                    "input", command.command(),
                    "status", command.status().name(),
                    "exitCode", command.exitCode(),
                    "reason", command.reason(),
                    "outputSummary", summary.text(),
                    "outputTruncated", summary.truncated()));
        }
        if (verifier.junitSummary() != null) {
            SpecVerifier.JUnitSummary junit = verifier.junitSummary();
            json.put("junit", mapOf(
                    "tests", junit.tests(),
                    "executedTests", junit.executedTests(),
                    "failures", junit.failures(),
                    "errors", junit.errors(),
                    "skipped", junit.skipped(),
                    "reports", junit.reports()));
        }
        return json;
    }

    private Map<String, Object> criterionJson(SpecRunResult.CriterionResult criterion) {
        return mapOf(
                "criterionId", criterion.criterionId(),
                "status", criterion.status().name(),
                "evidenceIds", criterion.evidenceIds(),
                "judge", criterion.judge().name().toLowerCase(java.util.Locale.ROOT),
                "reason", criterion.reason());
    }

    private Map<String, Object> humanEvidenceJson(SpecRunResult.HumanEvidence evidence) {
        return mapOf(
                "evidenceId", evidence.evidenceId(),
                "criterionId", evidence.criterionId(),
                "decision", evidence.decision().name(),
                "durationMs", evidence.durationMs(),
                "reason", evidence.reason());
    }

    private Map<String, Object> metricsJson(SpecRunResult.Metrics metrics) {
        return mapOf(
                "specGenerationMs", metrics.specGenerationMs(),
                "specConfirmationMs", metrics.specConfirmationMs(),
                "reactExecutionMs", metrics.reactExecutionMs(),
                "verificationMs", metrics.verificationMs(),
                "humanCriterionMs", metrics.humanCriterionMs(),
                "totalMs", metrics.totalMs(),
                "draftLlmUsage", usageJson(metrics.draftLlmUsage()),
                "reactLlmUsage", usageJson(metrics.reactLlmUsage()),
                "totalLlmUsage", usageJson(metrics.totalLlmUsage()));
    }

    private Map<String, Object> usageJson(SpecRunResult.LlmUsage usage) {
        return mapOf(
                "calls", usage.calls(),
                "inputTokens", usage.inputTokens(),
                "outputTokens", usage.outputTokens(),
                "cachedInputTokens", usage.cachedInputTokens());
    }

    private String projectRelative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private static OutputSummary summarizeOutput(String output) {
        String sanitized = ANSI.matcher(output == null ? "" : output).replaceAll("");
        for (Pattern pattern : SECRET_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("$1***");
        }
        if (sanitized.length() <= MAX_COMMAND_OUTPUT_CHARS) {
            return new OutputSummary(sanitized, false);
        }
        int head = 2 * 1024;
        String marker = "\n... command output truncated ...\n";
        int tail = MAX_COMMAND_OUTPUT_CHARS - head - marker.length();
        return new OutputSummary(
                sanitized.substring(0, head)
                        + marker
                        + sanitized.substring(sanitized.length() - tail),
                true);
    }

    private static String evidenceId(String verifierId) {
        return "verifier:" + verifierId;
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path directory = target.getParent();
        Path temporary = Files.createTempFile(directory, "." + target.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content == null ? "" : content, StandardCharsets.UTF_8);
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

    private record OutputSummary(String text, boolean truncated) {
    }
}
