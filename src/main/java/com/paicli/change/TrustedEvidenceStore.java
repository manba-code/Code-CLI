package com.paicli.change;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Control-plane Evidence archive. Worker output is copied through a bounded, no-symlink path and is thereafter
 * verified against hashes stored in SQLite before it can be displayed or used for a success decision.
 */
public final class TrustedEvidenceStore implements EvidenceStore {
    private static final int MAX_FILES = 512;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final String MANIFEST = "manifest.json";

    private final Path root;
    private final Connection connection;
    private final Clock clock;

    public TrustedEvidenceStore(Path database, Path root) throws Exception {
        this(database, root, Clock.systemUTC());
    }

    TrustedEvidenceStore(Path database, Path root, Clock clock) throws Exception {
        Path configuredRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(configuredRoot);
        if (Files.isSymbolicLink(configuredRoot)) throw new IOException("Evidence archive 根目录不能是符号链接");
        this.root = configuredRoot.toRealPath();
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS evidence_archives (
                        change_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        root_path TEXT NOT NULL,
                        manifest_sha256 TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(change_id, run_id),
                        FOREIGN KEY(change_id) REFERENCES change_tasks(id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS evidence_objects (
                        change_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(change_id, run_id, relative_path),
                        FOREIGN KEY(change_id, run_id) REFERENCES evidence_archives(change_id, run_id)
                    )
                    """);
        }
    }

    public Path root() { return root; }

    public synchronized String manifestSha256(ChangeTaskId changeId, String runId) {
        Capture capture = find(changeId, runId);
        if (capture == null) throw new ChangeValidationException("Evidence 缺少控制面可信归档记录");
        return capture.manifestSha256();
    }

    public synchronized Capture capture(ChangeTaskId changeId, String runId, Path source) throws Exception {
        Objects.requireNonNull(changeId, "changeId");
        requireSafeId(runId, "runId");
        Path sourceReal = Objects.requireNonNull(source, "source").toRealPath();
        if (!Files.isDirectory(sourceReal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Evidence 源目录不存在");
        Path parent = root.resolve(changeId.value()).normalize();
        if (!parent.getParent().equals(root)) throw new IOException("非法 changeId Evidence 路径");
        Files.createDirectories(parent);
        Path destination = parent.resolve(runId).normalize();
        if (!destination.getParent().equals(parent)) throw new IOException("非法 runId Evidence 路径");
        Capture existing = find(changeId, runId);
        if (existing != null) {
            verify(changeId, runId, existing.path());
            return existing;
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Evidence 归档路径已存在但没有可信数据库记录");
        }

        Path stagingRoot = root.resolve(".staging");
        Files.createDirectories(stagingRoot);
        Path staging = Files.createDirectory(stagingRoot.resolve(UUID.randomUUID().toString()));
        boolean moved = false;
        try {
            List<ObjectRecord> objects = copyObjects(sourceReal, staging);
            ObjectNode manifest = ChangeJson.MAPPER.createObjectNode();
            manifest.put("schema", "paichange/evidence-manifest/v1");
            manifest.put("changeId", changeId.value());
            manifest.put("runId", runId);
            ArrayNode files = manifest.putArray("files");
            objects.forEach(item -> files.addObject().put("path", item.relativePath())
                    .put("sha256", item.sha256()).put("size", item.sizeBytes()));
            Path manifestFile = staging.resolve(MANIFEST);
            Files.writeString(manifestFile, ChangeJson.MAPPER.writeValueAsString(manifest) + System.lineSeparator());
            String manifestHash = sha256(manifestFile);
            move(staging, destination);
            moved = true;
            persist(changeId, runId, destination, manifestHash, objects);
            makeReadOnly(destination);
            return new Capture(destination, manifestHash, List.copyOf(objects));
        } catch (Exception failure) {
            if (!moved) deleteTree(staging);
            throw failure;
        }
    }

    public synchronized void verify(ChangeTaskId changeId, String runId, Path expectedPath) throws IOException {
        Capture capture = find(changeId, runId);
        if (capture == null) throw new ChangeValidationException("Evidence 缺少控制面可信归档记录");
        Path expected = expectedPath.toAbsolutePath().normalize();
        if (!capture.path().equals(expected)) throw new ChangeConflictException("Evidence 路径与可信归档记录不一致");
        requireInsideRoot(expected);
        Path real = expected.toRealPath();
        if (!real.equals(expected) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new ChangeConflictException("Evidence 归档目录已变化");
        }
        Set<String> expectedFiles = new HashSet<>();
        for (ObjectRecord object : capture.objects()) {
            expectedFiles.add(object.relativePath());
            Path file = safeChild(real, object.relativePath());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)
                    || Files.size(file) != object.sizeBytes()
                    || !sha256(file).equals(object.sha256())) {
                throw new ChangeConflictException("Evidence 对象缺失或内容哈希不匹配: " + object.relativePath());
            }
        }
        Path manifest = safeChild(real, MANIFEST);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || !sha256(manifest).equals(capture.manifestSha256())) {
            throw new ChangeConflictException("Evidence manifest 缺失或哈希不匹配");
        }
        expectedFiles.add(MANIFEST);
        try (var paths = Files.walk(real)) {
            for (Path path : paths.filter(p -> !p.equals(real)).toList()) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ChangeConflictException("Evidence 归档包含非法对象");
                }
                String relative = real.relativize(path).toString().replace('\\', '/');
                if (!expectedFiles.contains(relative)) throw new ChangeConflictException("Evidence 归档包含未登记对象");
            }
        }
    }

    public synchronized void discardSource(Path source) {
        try { deleteTree(source); } catch (Exception ignored) { }
    }

    @Override public synchronized void checkHealth() {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT 1")) {
            if (!row.next() || row.getInt(1) != 1 || !Files.isDirectory(root)) throw new IOException("unexpected probe result");
        } catch (Exception e) {
            throw new IllegalStateException("本地 Evidence Store 健康检查失败: " + e.getMessage(), e);
        }
    }

    private List<ObjectRecord> copyObjects(Path source, Path staging) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(source)) {
            files = paths.filter(path -> !path.equals(source)).sorted().toList();
        }
        List<ObjectRecord> result = new ArrayList<>();
        long total = 0;
        for (Path path : files) {
            if (Files.isSymbolicLink(path)) throw new IOException("Evidence 源不允许符号链接");
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("M6a Evidence 归档只接受扁平普通文件");
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Evidence 源包含非普通文件");
            if (result.size() >= MAX_FILES) throw new IOException("Evidence 文件数超过上限");
            long size = Files.size(path);
            total += size;
            if (size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) throw new IOException("Evidence 大小超过可信归档上限");
            String relative = source.relativize(path).toString().replace('\\', '/');
            if (relative.equals(MANIFEST) || relative.contains("/")) {
                throw new IOException("Evidence 文件名与控制面 manifest 冲突或包含子目录");
            }
            Path target = safeChild(staging, relative);
            Files.createDirectories(target.getParent());
            Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
            result.add(new ObjectRecord(relative, sha256(target), size));
        }
        result.sort(Comparator.comparing(ObjectRecord::relativePath));
        if (result.stream().noneMatch(item -> item.relativePath().equals("result.json"))
                || result.stream().noneMatch(item -> item.relativePath().equals("change.diff"))) {
            throw new IOException("Evidence 缺少 result.json 或 change.diff");
        }
        return result;
    }

    private void persist(ChangeTaskId changeId, String runId, Path path, String manifestHash,
                         List<ObjectRecord> objects) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Instant now = clock.instant();
            try (PreparedStatement archive = connection.prepareStatement(
                    "INSERT INTO evidence_archives(change_id, run_id, root_path, manifest_sha256, created_at) VALUES (?, ?, ?, ?, ?)")) {
                archive.setString(1, changeId.value()); archive.setString(2, runId);
                archive.setString(3, path.toString()); archive.setString(4, manifestHash);
                archive.setString(5, now.toString()); archive.executeUpdate();
            }
            try (PreparedStatement object = connection.prepareStatement(
                    "INSERT INTO evidence_objects(change_id, run_id, relative_path, sha256, size_bytes, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (ObjectRecord item : objects) {
                    object.setString(1, changeId.value()); object.setString(2, runId);
                    object.setString(3, item.relativePath()); object.setString(4, item.sha256());
                    object.setLong(5, item.sizeBytes()); object.setString(6, now.toString()); object.addBatch();
                }
                object.executeBatch();
            }
            connection.commit();
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private Capture find(ChangeTaskId changeId, String runId) {
        try (PreparedStatement archive = connection.prepareStatement(
                "SELECT root_path, manifest_sha256 FROM evidence_archives WHERE change_id=? AND run_id=?")) {
            archive.setString(1, changeId.value()); archive.setString(2, runId);
            try (ResultSet row = archive.executeQuery()) {
                if (!row.next()) return null;
                Path path = Path.of(row.getString(1)).toAbsolutePath().normalize();
                String manifest = row.getString(2);
                List<ObjectRecord> objects = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT relative_path, sha256, size_bytes FROM evidence_objects WHERE change_id=? AND run_id=? ORDER BY relative_path")) {
                    query.setString(1, changeId.value()); query.setString(2, runId);
                    try (ResultSet entries = query.executeQuery()) {
                        while (entries.next()) objects.add(new ObjectRecord(entries.getString(1), entries.getString(2), entries.getLong(3)));
                    }
                }
                return new Capture(path, manifest, List.copyOf(objects));
            }
        } catch (Exception error) {
            throw new IllegalStateException("读取可信 Evidence 记录失败", error);
        }
    }

    private void requireInsideRoot(Path path) throws IOException {
        Path rootReal = root.toRealPath();
        if (!path.startsWith(root) || !path.toRealPath().startsWith(rootReal)) {
            throw new ChangeForbiddenException("Evidence 归档路径逃逸");
        }
        Path cursor = root;
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new ChangeForbiddenException("Evidence 归档不允许符号链接");
        }
    }

    private static Path safeChild(Path parent, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.startsWith("/") || relative.contains("\\")) {
            throw new IOException("非法 Evidence 相对路径");
        }
        Path child = parent.resolve(relative).normalize();
        if (!child.startsWith(parent) || child.equals(parent)) throw new IOException("Evidence 路径逃逸");
        return child;
    }

    private static void requireSafeId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException(name + " 非法");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
    }

    private static void makeReadOnly(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.isDirectory(path)) Files.setPosixFilePermissions(path, Set.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                    else Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ));
                } catch (UnsupportedOperationException | IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    @Override public synchronized void close() {
        try { connection.close(); } catch (Exception ignored) { }
    }

}
