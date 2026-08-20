package com.paicli.spec;

import com.paicli.tool.CommandExecutionResult;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * V1 的确定性 Verifier：命令按声明顺序串行执行，之后基于最终 workspace 执行 Scope 检查。
 * 本类只返回 VerifierResult，不归约 Criterion Result 或 Verdict。
 */
public final class SpecVerifier {
    private final Path projectRoot;
    private final CommandExecutor commandExecutor;

    public SpecVerifier(Path projectRoot, CommandExecutor commandExecutor) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
    }

    public VerificationRun verify(
            ChangeSpec spec,
            WorkspaceChangeTracker tracker,
            WorkspaceChangeTracker.Baseline baseline
    ) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(baseline, "baseline");

        Map<String, VerifierResult> results = new LinkedHashMap<>();
        String commandHaltReason = null;
        for (ChangeSpec.VerifierDefinition verifier : spec.verifiers()) {
            if (verifier.type() == ChangeSpec.VerifierType.COMMAND) {
                if (commandHaltReason != null) {
                    results.put(
                            verifier.id(),
                            VerifierResult.error(
                                    verifier,
                                    "前置命令中断了验证流程，当前命令未运行: " + commandHaltReason,
                                    null,
                                    null));
                    continue;
                }
                VerifierResult result = verifyCommand(verifier);
                results.put(verifier.id(), result);
                if (result.commandResult() != null
                        && (result.commandResult().status() == CommandExecutionResult.Status.HITL_DENIED
                        || result.commandResult().status() == CommandExecutionResult.Status.POLICY_DENIED
                        || result.commandResult().status() == CommandExecutionResult.Status.CANCELED)) {
                    commandHaltReason = result.detail();
                }
            }
        }

        WorkspaceChangeTracker.WorkspaceChanges changes;
        String workspaceError = null;
        try {
            changes = tracker.collectChanges(baseline);
        } catch (IOException e) {
            changes = new WorkspaceChangeTracker.WorkspaceChanges(List.of(), "", false);
            workspaceError = messageOf(e);
        }

        for (ChangeSpec.VerifierDefinition verifier : spec.verifiers()) {
            if (verifier.type() == ChangeSpec.VerifierType.PATH_SCOPE) {
                VerifierResult result = workspaceError == null
                        ? verifyScope(verifier, spec.scope(), changes.changedFiles())
                        : VerifierResult.error(verifier, "无法采集最终 workspace: " + workspaceError, null, null);
                results.put(verifier.id(), result);
            }
        }

        List<VerifierResult> ordered = spec.verifiers().stream()
                .map(verifier -> results.get(verifier.id()))
                .toList();
        return new VerificationRun(changes, ordered);
    }

    private VerifierResult verifyCommand(ChangeSpec.VerifierDefinition verifier) {
        ChangeSpec.CommandExpectation expectation = verifier.expect();
        Map<String, String> reportsBefore;
        try {
            reportsBefore = snapshotReports(expectation.junitReportGlob());
        } catch (Exception e) {
            return VerifierResult.error(
                    verifier,
                    "JUnit report glob 无法读取: " + messageOf(e),
                    null,
                    null);
        }

        CommandExecutionResult commandResult;
        try {
            commandResult = commandExecutor.execute(verifier.command());
        } catch (Exception e) {
            commandResult = CommandExecutionResult.startError(verifier.command(), messageOf(e));
        }
        if (commandResult == null) {
            commandResult = CommandExecutionResult.startError(verifier.command(), "命令执行器没有返回结果");
        }
        if (commandResult.status() != CommandExecutionResult.Status.COMPLETED) {
            return VerifierResult.error(
                    verifier,
                    commandFailureReason(commandResult),
                    commandResult,
                    null);
        }

        List<String> failures = new ArrayList<>();
        if (!Objects.equals(expectation.exitCode(), commandResult.exitCode())) {
            failures.add("exit code 期望 " + expectation.exitCode() + "，实际 " + commandResult.exitCode());
        }

        JUnitSummary junit = null;
        if (expectation.junitReportGlob() != null && !expectation.junitReportGlob().isBlank()) {
            try {
                junit = readFreshJUnitReports(expectation.junitReportGlob(), reportsBefore);
            } catch (NoFreshReportsException e) {
                failures.add(e.getMessage());
            } catch (Exception e) {
                return VerifierResult.error(
                        verifier,
                        "JUnit XML 无法形成有效结果: " + messageOf(e),
                        commandResult,
                        null);
            }
            if (junit != null) {
                if (junit.failures() > 0 || junit.errors() > 0) {
                    failures.add("JUnit failures=" + junit.failures() + ", errors=" + junit.errors());
                }
                if (expectation.minimumTests() != null && junit.executedTests() < expectation.minimumTests()) {
                    failures.add("实际执行测试 " + junit.executedTests()
                            + "，少于 minimum_tests=" + expectation.minimumTests());
                }
            }
        }

        if (!failures.isEmpty()) {
            return VerifierResult.fail(verifier, String.join("；", failures), commandResult, junit);
        }
        String detail = "命令满足预期 exit code=" + commandResult.exitCode();
        if (junit != null) {
            detail += "；JUnit tests=" + junit.tests()
                    + ", skipped=" + junit.skipped()
                    + ", failures=" + junit.failures()
                    + ", errors=" + junit.errors();
        }
        return VerifierResult.pass(verifier, detail, commandResult, junit);
    }

    private VerifierResult verifyScope(
            ChangeSpec.VerifierDefinition verifier,
            ChangeSpec.Scope scope,
            List<String> changedFiles
    ) {
        List<ProjectGlob> includes;
        List<ProjectGlob> excludes;
        try {
            includes = compileGlobs(scope.include());
            excludes = compileGlobs(scope.exclude());
        } catch (IllegalArgumentException e) {
            return VerifierResult.error(verifier, "Scope glob 无法执行: " + e.getMessage(), null, null);
        }

        List<String> violations = new ArrayList<>();
        for (String path : changedFiles) {
            boolean excluded = matchesAny(excludes, path);
            boolean included = scope.mode() == ChangeSpec.ScopeMode.OPEN || matchesAny(includes, path);
            if (excluded || !included) {
                violations.add(path);
            }
        }
        if (!violations.isEmpty()) {
            return VerifierResult.fail(
                    verifier,
                    "Scope 越界: " + String.join(", ", violations),
                    null,
                    null);
        }
        return VerifierResult.pass(
                verifier,
                "Scope 通过，检查本轮 changed files=" + changedFiles.size(),
                null,
                null);
    }

    private Map<String, String> snapshotReports(String glob) throws IOException {
        if (glob == null || glob.isBlank()) {
            return Map.of();
        }
        ProjectGlob matcher = new ProjectGlob(glob);
        Map<String, String> hashes = new HashMap<>();
        Path searchRoot = matcher.searchRoot(projectRoot);
        if (!Files.exists(searchRoot)) {
            return Map.of();
        }
        Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    String relative = relative(file);
                    if (matcher.matches(relative)) {
                        hashes.put(relative, sha256(file));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return hashes;
    }

    private JUnitSummary readFreshJUnitReports(String glob, Map<String, String> before) throws Exception {
        Map<String, String> after = snapshotReports(glob);
        List<String> fresh = after.entrySet().stream()
                .filter(entry -> !Objects.equals(before.get(entry.getKey()), entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (fresh.isEmpty()) {
            throw new NoFreshReportsException("没有本次命令新生成或更新的 JUnit XML");
        }

        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        for (String relative : fresh) {
            Path report = projectRoot.resolve(relative).normalize();
            if (!report.startsWith(projectRoot) || Files.isSymbolicLink(report)) {
                throw new IOException("JUnit report 超出项目根目录或是符号链接: " + relative);
            }
            JUnitSummary parsed = parseJUnitReport(report);
            tests += parsed.tests();
            failures += parsed.failures();
            errors += parsed.errors();
            skipped += parsed.skipped();
        }
        return new JUnitSummary(tests, failures, errors, skipped, fresh);
    }

    private JUnitSummary parseJUnitReport(Path report) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException {
                throw error;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException {
                throw error;
            }
        });
        var document = builder.parse(report.toFile());
        var root = document.getDocumentElement();
        if (root == null) {
            throw new IOException("JUnit XML 没有根元素: " + relative(report));
        }

        List<org.w3c.dom.Element> suites = new ArrayList<>();
        if ("testsuite".equals(root.getTagName())) {
            suites.add(root);
        } else if ("testsuites".equals(root.getTagName())) {
            var children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof org.w3c.dom.Element element
                        && "testsuite".equals(element.getTagName())) {
                    suites.add(element);
                }
            }
        } else {
            throw new IOException("JUnit XML 根元素必须是 testsuite 或 testsuites: " + relative(report));
        }
        if (suites.isEmpty()) {
            throw new IOException("JUnit XML 不包含 testsuite: " + relative(report));
        }

        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        for (org.w3c.dom.Element suite : suites) {
            tests += requiredNonNegativeInt(suite, "tests", report);
            failures += optionalNonNegativeInt(suite, "failures", report);
            errors += optionalNonNegativeInt(suite, "errors", report);
            skipped += optionalNonNegativeInt(suite, "skipped", report);
        }
        if (skipped > tests) {
            throw new IOException("JUnit skipped 大于 tests: " + relative(report));
        }
        return new JUnitSummary(tests, failures, errors, skipped, List.of(relative(report)));
    }

    private int requiredNonNegativeInt(org.w3c.dom.Element element, String name, Path report) throws IOException {
        if (!element.hasAttribute(name)) {
            throw new IOException("JUnit testsuite 缺少 " + name + ": " + relative(report));
        }
        return parseNonNegativeInt(element.getAttribute(name), name, report);
    }

    private int optionalNonNegativeInt(org.w3c.dom.Element element, String name, Path report) throws IOException {
        return element.hasAttribute(name) ? parseNonNegativeInt(element.getAttribute(name), name, report) : 0;
    }

    private int parseNonNegativeInt(String value, String name, Path report) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("JUnit " + name + " 不是非负整数: " + relative(report));
        }
    }

    private List<ProjectGlob> compileGlobs(List<String> patterns) {
        return patterns.stream().map(ProjectGlob::new).toList();
    }

    private static boolean matchesAny(List<ProjectGlob> globs, String path) {
        return globs.stream().anyMatch(glob -> glob.matches(path));
    }

    private String relative(Path path) {
        return projectRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = Files.newInputStream(file);
                 DigestInputStream input = new DigestInputStream(raw, digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String commandFailureReason(CommandExecutionResult result) {
        return switch (result.status()) {
            case TIMED_OUT -> "命令执行超时";
            case START_ERROR -> "命令无法启动: " + result.reason();
            case CANCELED -> "命令执行被取消: " + result.reason();
            case POLICY_DENIED -> "命令被策略拒绝: " + result.reason();
            case HITL_DENIED -> "命令未获用户授权: " + result.reason();
            case COMPLETED -> "";
        };
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    @FunctionalInterface
    public interface CommandExecutor {
        CommandExecutionResult execute(String command);
    }

    public record VerificationRun(
            WorkspaceChangeTracker.WorkspaceChanges workspaceChanges,
            List<VerifierResult> verifierResults
    ) {
        public VerificationRun {
            verifierResults = List.copyOf(verifierResults);
        }
    }

    public record VerifierResult(
            String verifierId,
            ChangeSpec.VerifierType type,
            Status status,
            String detail,
            CommandExecutionResult commandResult,
            JUnitSummary junitSummary
    ) {
        private static VerifierResult pass(
                ChangeSpec.VerifierDefinition verifier,
                String detail,
                CommandExecutionResult command,
                JUnitSummary junit
        ) {
            return new VerifierResult(verifier.id(), verifier.type(), Status.PASS, detail, command, junit);
        }

        private static VerifierResult fail(
                ChangeSpec.VerifierDefinition verifier,
                String detail,
                CommandExecutionResult command,
                JUnitSummary junit
        ) {
            return new VerifierResult(verifier.id(), verifier.type(), Status.FAIL, detail, command, junit);
        }

        private static VerifierResult error(
                ChangeSpec.VerifierDefinition verifier,
                String detail,
                CommandExecutionResult command,
                JUnitSummary junit
        ) {
            return new VerifierResult(verifier.id(), verifier.type(), Status.ERROR, detail, command, junit);
        }
    }

    public record JUnitSummary(int tests, int failures, int errors, int skipped, List<String> reports) {
        public JUnitSummary {
            reports = List.copyOf(reports);
        }

        public int executedTests() {
            return tests - skipped;
        }
    }

    public enum Status {
        PASS,
        FAIL,
        ERROR
    }

    private static final class ProjectGlob {
        private final String source;
        private final Pattern regex;

        private ProjectGlob(String source) {
            this.source = Objects.requireNonNull(source, "glob");
            this.regex = Pattern.compile(toRegex(source));
        }

        private boolean matches(String path) {
            return regex.matcher(path.replace('\\', '/')).matches();
        }

        private Path searchRoot(Path projectRoot) {
            int wildcard = source.length();
            int star = source.indexOf('*');
            int question = source.indexOf('?');
            if (star >= 0) {
                wildcard = Math.min(wildcard, star);
            }
            if (question >= 0) {
                wildcard = Math.min(wildcard, question);
            }
            String literalPrefix = source.substring(0, wildcard);
            int lastSlash = literalPrefix.lastIndexOf('/');
            String directory = lastSlash < 0 ? "" : literalPrefix.substring(0, lastSlash);
            Path resolved = directory.isEmpty()
                    ? projectRoot
                    : projectRoot.resolve(directory).normalize();
            if (!resolved.startsWith(projectRoot)) {
                throw new IllegalArgumentException("glob 超出项目根目录: " + source);
            }
            return resolved;
        }

        private static String toRegex(String glob) {
            StringBuilder out = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char current = glob.charAt(i);
                if (current == '*') {
                    boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    if (doubleStar) {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                            out.append("(?:.*/)?");
                        } else {
                            out.append(".*");
                        }
                    } else {
                        out.append("[^/]*");
                    }
                } else if (current == '?') {
                    out.append("[^/]");
                } else {
                    if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                        out.append('\\');
                    }
                    out.append(current);
                }
            }
            return out.append('$').toString();
        }

        @Override
        public String toString() {
            return source;
        }
    }

    private static final class NoFreshReportsException extends Exception {
        private NoFreshReportsException(String message) {
            super(message);
        }
    }
}
