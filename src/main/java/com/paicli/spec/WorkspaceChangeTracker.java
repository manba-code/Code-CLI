package com.paicli.spec;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 以运行开始时的文件内容为 baseline，采集本轮新增、修改、删除文件及相对 baseline 的统一 diff。
 * 不依赖 Git HEAD 或 Side-Git，因此可以正确处理运行前已经存在的脏文件。
 */
public final class WorkspaceChangeTracker {
    private static final int MAX_CAPTURED_FILE_BYTES = 1024 * 1024;
    private static final int MAX_DIFF_BYTES = 256 * 1024;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "target", "build", "dist", "node_modules", "coverage", ".gradle", ".idea");
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            ".paicli/specs", ".paicli/runs");

    private final Path projectRoot;

    public WorkspaceChangeTracker(Path projectRoot) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    }

    public Baseline captureBaseline() throws IOException {
        return new Baseline(scanWorkspace());
    }

    public WorkspaceChanges collectChanges(Baseline baseline) throws IOException {
        Objects.requireNonNull(baseline, "baseline");
        Map<String, FileState> after = scanWorkspace();
        Set<String> allPaths = new LinkedHashSet<>(baseline.files().keySet());
        allPaths.addAll(after.keySet());
        List<String> changedFiles = allPaths.stream()
                .filter(path -> !sameContent(baseline.files().get(path), after.get(path)))
                .sorted()
                .toList();

        DiffBuild diff = buildDiff(changedFiles, baseline.files(), after);
        return new WorkspaceChanges(changedFiles, diff.text(), diff.truncated());
    }

    private Map<String, FileState> scanWorkspace() throws IOException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IOException("项目根目录不存在: " + projectRoot);
        }
        Map<String, FileState> files = new HashMap<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(projectRoot) && isExcluded(relative(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = relative(file);
                if (isExcluded(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isRegularFile()) {
                    files.put(relative, regularFileState(file, attrs.size()));
                } else if (attrs.isSymbolicLink()) {
                    byte[] target = Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8);
                    files.put(relative, new FileState(FileKind.SYMLINK, sha256(target), target));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);
    }

    private FileState regularFileState(Path file, long size) throws IOException {
        byte[] content = size <= MAX_CAPTURED_FILE_BYTES ? Files.readAllBytes(file) : null;
        return new FileState(FileKind.REGULAR, sha256(file), content);
    }

    private String relative(Path path) {
        return projectRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private boolean isExcluded(String relative) {
        String normalized = relative.replace('\\', '/');
        if (normalized.isEmpty()) {
            return false;
        }
        String firstSegment = normalized.contains("/")
                ? normalized.substring(0, normalized.indexOf('/'))
                : normalized;
        if (EXCLUDED_DIRECTORIES.contains(firstSegment)) {
            return true;
        }
        for (String prefix : EXCLUDED_PREFIXES) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private DiffBuild buildDiff(
            List<String> changedFiles,
            Map<String, FileState> before,
            Map<String, FileState> after
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean truncated = false;
        for (String path : changedFiles) {
            FileState oldState = before.get(path);
            FileState newState = after.get(path);
            String header = "diff --git a/" + path + " b/" + path + "\n";
            if (!appendWithinLimit(output, header.getBytes(StandardCharsets.UTF_8))) {
                truncated = true;
                break;
            }
            byte[] oldBytes = oldState == null ? new byte[0] : oldState.content();
            byte[] newBytes = newState == null ? new byte[0] : newState.content();
            if (oldBytes == null || newBytes == null) {
                String marker = "Binary or large file changed; textual diff omitted.\n";
                if (!appendWithinLimit(output, marker.getBytes(StandardCharsets.UTF_8))) {
                    truncated = true;
                    break;
                }
                truncated = true;
                continue;
            }
            if (RawText.isBinary(oldBytes) || RawText.isBinary(newBytes)
                    || oldState != null && oldState.kind() == FileKind.SYMLINK
                    || newState != null && newState.kind() == FileKind.SYMLINK) {
                String marker = "Binary files " + (oldState == null ? "/dev/null" : "a/" + path)
                        + " and " + (newState == null ? "/dev/null" : "b/" + path) + " differ\n";
                if (!appendWithinLimit(output, marker.getBytes(StandardCharsets.UTF_8))) {
                    truncated = true;
                    break;
                }
                continue;
            }

            ByteArrayOutputStream fileDiff = new ByteArrayOutputStream();
            fileDiff.write(("--- " + (oldState == null ? "/dev/null" : "a/" + path) + "\n").getBytes(StandardCharsets.UTF_8));
            fileDiff.write(("+++ " + (newState == null ? "/dev/null" : "b/" + path) + "\n").getBytes(StandardCharsets.UTF_8));
            RawText oldText = new RawText(oldBytes);
            RawText newText = new RawText(newBytes);
            try (DiffFormatter formatter = new DiffFormatter(fileDiff)) {
                formatter.setContext(3);
                formatter.format(
                        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                                .diff(RawTextComparator.DEFAULT, oldText, newText),
                        oldText,
                        newText);
            }
            if (!appendWithinLimit(output, fileDiff.toByteArray())) {
                truncated = true;
                break;
            }
        }
        if (truncated) {
            byte[] marker = "... diff truncated ...\n".getBytes(StandardCharsets.UTF_8);
            if (output.size() + marker.length <= MAX_DIFF_BYTES) {
                output.write(marker);
            }
        }
        return new DiffBuild(output.toString(StandardCharsets.UTF_8), truncated);
    }

    private static boolean appendWithinLimit(ByteArrayOutputStream output, byte[] bytes) throws IOException {
        if (output.size() + bytes.length > MAX_DIFF_BYTES) {
            return false;
        }
        output.write(bytes);
        return true;
    }

    private static boolean sameContent(FileState left, FileState right) {
        return left == null ? right == null : right != null
                && left.kind() == right.kind()
                && left.sha256().equals(right.sha256());
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    public record Baseline(Map<String, FileState> files) {
        public Baseline {
            files = Map.copyOf(files);
        }
    }

    public record WorkspaceChanges(List<String> changedFiles, String diff, boolean diffTruncated) {
        public WorkspaceChanges {
            changedFiles = List.copyOf(changedFiles);
            diff = diff == null ? "" : diff;
        }
    }

    public record FileState(FileKind kind, String sha256, byte[] content) {
        public FileState {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    public enum FileKind {
        REGULAR,
        SYMLINK
    }

    private record DiffBuild(String text, boolean truncated) {
    }
}
