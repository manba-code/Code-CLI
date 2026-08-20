package com.paicli.spec.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

record ChangeSpecEvaluationCase(
        String id,
        ChangeSpecEvaluationTier tier,
        String task,
        String draftContext,
        Map<String, String> visibleFiles,
        Map<String, String> hiddenVerificationFiles,
        Set<String> allowedChangedFiles,
        String publicVerifierCommand,
        List<String> hiddenVerificationCommand,
        Duration verificationTimeout
) {
    ChangeSpecEvaluationCase {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
        Objects.requireNonNull(tier, "tier");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task 不能为空");
        if (draftContext == null || draftContext.isBlank()) {
            throw new IllegalArgumentException("draftContext 不能为空");
        }
        visibleFiles = Map.copyOf(visibleFiles);
        hiddenVerificationFiles = Map.copyOf(hiddenVerificationFiles);
        allowedChangedFiles = Set.copyOf(allowedChangedFiles);
        publicVerifierCommand = requireText(publicVerifierCommand, "publicVerifierCommand");
        hiddenVerificationCommand = List.copyOf(hiddenVerificationCommand);
        verificationTimeout = verificationTimeout == null ? Duration.ofMinutes(2) : verificationTimeout;
    }

    void materialize(Path workspace) throws IOException {
        Files.createDirectories(workspace);
        writeFiles(workspace, visibleFiles);
    }

    WorkspaceSnapshot snapshot(Path workspace) throws IOException {
        return new WorkspaceSnapshot(fileHashes(workspace));
    }

    void copyCandidate(Path source, Path destination) throws IOException {
        Path sourceRoot = source.toAbsolutePath().normalize();
        Path destinationRoot = destination.toAbsolutePath().normalize();
        Files.createDirectories(destinationRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.sorted().toList()) {
                String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
                if (relative.isEmpty() || isRuntimeArtifact(relative)) continue;
                Path target = destinationRoot.resolve(relative).normalize();
                if (!target.startsWith(destinationRoot)) {
                    throw new IOException("候选快照逃逸目录: " + relative);
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    ValidationResult verify(Path workspace, WorkspaceSnapshot baseline)
            throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        Map<String, String> afterRun = fileHashes(workspace);
        Set<String> changed = changedFiles(baseline.fileHashes(), afterRun);
        Set<String> unexpected = new HashSet<>(changed);
        unexpected.removeAll(allowedChangedFiles);

        List<String> details = new ArrayList<>();
        int passedChecks = 0;
        if (unexpected.isEmpty()) {
            passedChecks++;
            details.add("变更范围通过");
        } else {
            details.add("未授权变更: " + String.join(", ", unexpected.stream().sorted().toList()));
        }

        writeFiles(workspace, hiddenVerificationFiles);
        CommandResult commandResult = runVerification(workspace);
        if (commandResult.exitCode() == 0 && !commandResult.timedOut()) {
            passedChecks++;
            details.add("隐藏 Oracle 通过");
        } else {
            details.add("隐藏 Oracle 失败(exit=" + commandResult.exitCode()
                    + (commandResult.timedOut() ? ", timeout" : "") + "): " + commandResult.output());
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return new ValidationResult(
                passedChecks == 2,
                passedChecks,
                2,
                String.join("；", details),
                changed,
                unexpected,
                commandResult,
                durationMs);
    }

    boolean isAllowedVerifierCommand(String command) {
        return publicVerifierCommand.equals(normalizeCommand(command));
    }

    private CommandResult runVerification(Path workspace) throws IOException, InterruptedException {
        Path logFile = workspace.resolve("hidden-verification.log");
        ProcessBuilder builder = new ProcessBuilder(hiddenVerificationCommand)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(verificationTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        if (output.length() > 8_000) output = output.substring(0, 8_000) + "...";
        return new CommandResult(finished ? process.exitValue() : -1, !finished, output.strip());
    }

    private static void writeFiles(Path workspace, Map<String, String> files) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = root.resolve(entry.getKey()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("评测文件逃逸工作区: " + entry.getKey());
            }
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> fileHashes(Path workspace) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = workspace.relativize(path).toString().replace('\\', '/');
                if (isRuntimeArtifact(relative)) continue;
                hashes.put(relative, sha256(path));
            }
        }
        return hashes;
    }

    private static boolean isRuntimeArtifact(String relative) {
        return relative.equals("run.log")
                || relative.equals("hidden-verification.log")
                || relative.startsWith("target/")
                || relative.startsWith(".git/")
                || relative.startsWith(".paicli/")
                || relative.startsWith(".eval-memory/");
    }

    private static Set<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        Set<String> changed = new HashSet<>();
        for (String path : paths) {
            if (!Objects.equals(before.get(path), after.get(path))) changed.add(path);
        }
        return changed;
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String normalizeCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return normalizeCommand(value);
    }

    record WorkspaceSnapshot(Map<String, String> fileHashes) {
        WorkspaceSnapshot {
            fileHashes = Map.copyOf(fileHashes);
        }
    }

    record CommandResult(int exitCode, boolean timedOut, String output) {
    }

    record ValidationResult(
            boolean passed,
            int passedChecks,
            int totalChecks,
            String detail,
            Set<String> changedFiles,
            Set<String> unexpectedFiles,
            CommandResult commandResult,
            long oracleDurationMs
    ) {
        ValidationResult {
            changedFiles = Set.copyOf(changedFiles);
            unexpectedFiles = Set.copyOf(unexpectedFiles);
        }
    }
}
