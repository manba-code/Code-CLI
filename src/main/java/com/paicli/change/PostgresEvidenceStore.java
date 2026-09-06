package com.paicli.change;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL metadata plus immutable S3 objects, materialized into a bounded local read cache. */
public final class PostgresEvidenceStore implements EvidenceStore {
    private static final int MAX_FILES = 512;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final String MANIFEST = "manifest.json";
    private final Connection connection;
    private final ObjectStorage objects;
    private final Path root;
    private final Clock clock;

    public PostgresEvidenceStore(String jdbcUrl, String user, String password, ObjectStorage objects,
                                 Path cacheRoot) throws Exception {
        this(DriverManager.getConnection(jdbcUrl, user, password), objects, cacheRoot, Clock.systemUTC());
    }

    PostgresEvidenceStore(Connection connection, ObjectStorage objects, Path cacheRoot, Clock clock) throws Exception {
        this.connection = java.util.Objects.requireNonNull(connection);
        this.objects = java.util.Objects.requireNonNull(objects);
        this.clock = java.util.Objects.requireNonNull(clock);
        Path configured = cacheRoot.toAbsolutePath().normalize();
        Files.createDirectories(configured);
        if (Files.isSymbolicLink(configured)) throw new IOException("Evidence cache 根目录不能是符号链接");
        this.root = configured.toRealPath();
        PostgresStorageMigrations.migrate(connection);
    }

    @Override public Path root() { return root; }

    @Override public synchronized String manifestSha256(ChangeTaskId changeId, String runId) {
        Capture capture = find(changeId, runId);
        if (capture == null) throw new ChangeValidationException("Evidence 缺少可信对象归档记录");
        return capture.manifestSha256();
    }

    @Override public synchronized Capture capture(ChangeTaskId changeId, String runId, Path source) throws Exception {
        safeId(changeId.value(), "changeId"); safeId(runId, "runId");
        Capture existing = find(changeId, runId);
        if (existing != null) {
            verify(changeId, runId, existing.path());
            return existing;
        }
        Path sourceReal = source.toRealPath();
        if (!Files.isDirectory(sourceReal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Evidence 源目录不存在");
        List<ObjectRecord> records = readSource(sourceReal);
        String prefix = prefix(changeId, runId);
        ObjectNode manifest = ChangeJson.MAPPER.createObjectNode();
        manifest.put("schema", "paichange/evidence-manifest/v1");
        manifest.put("changeId", changeId.value()); manifest.put("runId", runId);
        var files = manifest.putArray("files");
        records.forEach(record -> files.addObject().put("path", record.relativePath())
                .put("sha256", record.sha256()).put("size", record.sizeBytes()));
        byte[] manifestBytes = (ChangeJson.MAPPER.writeValueAsString(manifest) + System.lineSeparator())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String manifestHash = sha256(manifestBytes);
        for (ObjectRecord record : records) {
            objects.putIfAbsent(prefix + record.relativePath(), Files.readAllBytes(sourceReal.resolve(record.relativePath())), record.sha256());
        }
        objects.putIfAbsent(prefix + MANIFEST, manifestBytes, manifestHash);
        persist(changeId, runId, prefix, manifestHash, records);
        Path cache = cachePath(changeId, runId);
        materialize(prefix, cache, manifestHash, records);
        return new Capture(cache, manifestHash, List.copyOf(records));
    }

    @Override public synchronized void verify(ChangeTaskId changeId, String runId, Path expectedPath) throws IOException {
        Capture record = find(changeId, runId);
        if (record == null) throw new ChangeValidationException("Evidence 缺少可信对象归档记录");
        Path expected = expectedPath.toAbsolutePath().normalize();
        if (!expected.equals(record.path())) throw new ChangeConflictException("Evidence cache 路径与可信记录不一致");
        String prefix = prefix(changeId, runId);
        Set<String> expectedKeys = new HashSet<>();
        for (ObjectRecord object : record.objects()) {
            String key = prefix + object.relativePath(); expectedKeys.add(key);
            requireRemote(key, object.sizeBytes(), object.sha256());
        }
        String manifestKey = prefix + MANIFEST; expectedKeys.add(manifestKey);
        requireRemote(manifestKey, -1, record.manifestSha256());
        if (!new HashSet<>(objects.list(prefix)).equals(expectedKeys))
            throw new ChangeConflictException("Evidence 对象前缀包含缺失或额外对象");
        if (!validCache(expected, record)) materialize(prefix, expected, record.manifestSha256(), record.objects());
    }

    @Override public void discardSource(Path source) {
        try { deleteTree(source); } catch (Exception ignored) { }
    }

    @Override public synchronized void checkHealth() {
        try {
            try (var statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM evidence_archives")) {
                if (!row.next()) throw new SQLException("unexpected Evidence schema probe result");
            }
            objects.checkHealth();
            if (!Files.isDirectory(root) || !Files.isWritable(root)) throw new IOException("Evidence cache 不可写");
        } catch (Exception e) { throw new IllegalStateException("生产 Evidence Store 健康检查失败: " + e.getMessage(), e); }
    }

    /** Full restored-archive verification used by the M7b recovery drill. */
    public synchronized VerificationSummary verifyAll() throws IOException {
        List<ArchiveIdentity> archives = new ArrayList<>();
        try (var query = connection.prepareStatement(
                "SELECT change_id, run_id FROM evidence_archives ORDER BY change_id, run_id");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) archives.add(new ArchiveIdentity(rows.getString(1), rows.getString(2)));
        } catch (SQLException e) { throw new IOException("读取 Evidence 恢复清单失败", e); }
        long objects = 0;
        for (ArchiveIdentity archive : archives) {
            ChangeTaskId changeId = new ChangeTaskId(archive.changeId());
            Capture capture = find(changeId, archive.runId());
            if (capture == null) throw new ChangeConflictException("Evidence 恢复记录在扫描期间消失");
            verify(changeId, archive.runId(), capture.path());
            objects += capture.objects().size() + 1L; // Include manifest.json.
        }
        return new VerificationSummary(archives.size(), objects);
    }

    private void requireRemote(String key, long expectedSize, String expectedHash) throws IOException {
        ObjectStorage.Metadata metadata = objects.metadata(key)
                .orElseThrow(() -> new ChangeConflictException("Evidence 对象缺失: " + key));
        if ((expectedSize >= 0 && metadata.sizeBytes() != expectedSize) || !metadata.sha256().equals(expectedHash))
            throw new ChangeConflictException("Evidence 对象元数据不匹配: " + key);
        byte[] content = objects.get(key, expectedSize >= 0 ? Math.max(expectedSize, 1) : MAX_FILE_BYTES);
        if (!sha256(content).equals(expectedHash)) throw new ChangeConflictException("Evidence 对象内容哈希不匹配: " + key);
    }

    private List<ObjectRecord> readSource(Path source) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(source)) { files = walk.filter(path -> !path.equals(source)).sorted().toList(); }
        List<ObjectRecord> result = new ArrayList<>(); long total = 0;
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Evidence 源只接受扁平普通文件");
            String relative = source.relativize(file).toString().replace('\\', '/');
            if (relative.equals(MANIFEST) || relative.contains("/")) throw new IOException("Evidence 文件名非法");
            long size = Files.size(file); total += size;
            if (result.size() >= MAX_FILES || size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
                throw new IOException("Evidence 超过可信归档上限");
            result.add(new ObjectRecord(relative, sha256(Files.readAllBytes(file)), size));
        }
        result.sort(Comparator.comparing(ObjectRecord::relativePath));
        if (result.stream().noneMatch(item -> item.relativePath().equals("result.json"))
                || result.stream().noneMatch(item -> item.relativePath().equals("change.diff")))
            throw new IOException("Evidence 缺少 result.json 或 change.diff");
        return result;
    }

    private void persist(ChangeTaskId changeId, String runId, String prefix, String manifest,
                         List<ObjectRecord> records) throws SQLException {
        boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
        try {
            Instant now = clock.instant();
            try (PreparedStatement archive = connection.prepareStatement("""
                    INSERT INTO evidence_archives(change_id, run_id, object_prefix, manifest_sha256, created_at)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT(change_id, run_id) DO NOTHING
                    """)) {
                archive.setString(1, changeId.value()); archive.setString(2, runId); archive.setString(3, prefix);
                archive.setString(4, manifest); archive.setString(5, now.toString()); archive.executeUpdate();
            }
            try (PreparedStatement object = connection.prepareStatement("""
                    INSERT INTO evidence_objects(change_id, run_id, relative_path, sha256, size_bytes, created_at)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(change_id, run_id, relative_path) DO NOTHING
                    """)) {
                for (ObjectRecord record : records) {
                    object.setString(1, changeId.value()); object.setString(2, runId); object.setString(3, record.relativePath());
                    object.setString(4, record.sha256()); object.setLong(5, record.sizeBytes());
                    object.setString(6, now.toString()); object.addBatch();
                }
                object.executeBatch();
            }
            connection.commit();
            Capture saved = find(changeId, runId);
            if (saved == null || !saved.manifestSha256().equals(manifest) || !saved.objects().equals(records))
                throw new ChangeConflictException("同一 Evidence identity 已有不同元数据");
        } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(autoCommit); }
    }

    private Capture find(ChangeTaskId changeId, String runId) {
        try (PreparedStatement archive = connection.prepareStatement("""
                SELECT object_prefix, manifest_sha256 FROM evidence_archives WHERE change_id=? AND run_id=?
                """)) {
            archive.setString(1, changeId.value()); archive.setString(2, runId);
            try (ResultSet row = archive.executeQuery()) {
                if (!row.next()) return null;
                String prefix = row.getString(1); String manifest = row.getString(2);
                List<ObjectRecord> records = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT relative_path, sha256, size_bytes FROM evidence_objects
                        WHERE change_id=? AND run_id=? ORDER BY relative_path
                        """)) {
                    query.setString(1, changeId.value()); query.setString(2, runId);
                    try (ResultSet entries = query.executeQuery()) {
                        while (entries.next()) records.add(new ObjectRecord(entries.getString(1), entries.getString(2), entries.getLong(3)));
                    }
                }
                if (!prefix.equals(prefix(changeId, runId))) throw new ChangeConflictException("Evidence object prefix 已变化");
                return new Capture(cachePath(changeId, runId), manifest, List.copyOf(records));
            }
        } catch (SQLException e) { throw new IllegalStateException("读取 Evidence 元数据失败: " + e.getMessage(), e); }
    }

    private boolean validCache(Path cache, Capture record) {
        try {
            if (!cache.toRealPath().equals(cache) || !Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) return false;
            for (ObjectRecord object : record.objects()) {
                Path file = cache.resolve(object.relativePath());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                        || Files.size(file) != object.sizeBytes() || !sha256(Files.readAllBytes(file)).equals(object.sha256())) return false;
            }
            Path manifest = cache.resolve(MANIFEST);
            return Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    && sha256(Files.readAllBytes(manifest)).equals(record.manifestSha256());
        } catch (Exception e) { return false; }
    }

    private void materialize(String prefix, Path destination, String manifest, List<ObjectRecord> records) throws IOException {
        Path parent = destination.getParent(); Files.createDirectories(parent);
        Path staging = Files.createDirectory(parent.resolve(".evidence-" + UUID.randomUUID()));
        try {
            for (ObjectRecord record : records) {
                byte[] bytes = objects.get(prefix + record.relativePath(), record.sizeBytes());
                if (bytes.length != record.sizeBytes() || !sha256(bytes).equals(record.sha256()))
                    throw new ChangeConflictException("Evidence 对象物化校验失败: " + record.relativePath());
                Files.write(staging.resolve(record.relativePath()), bytes);
            }
            byte[] manifestBytes = objects.get(prefix + MANIFEST, MAX_FILE_BYTES);
            if (!sha256(manifestBytes).equals(manifest)) throw new ChangeConflictException("Evidence manifest 物化校验失败");
            Files.write(staging.resolve(MANIFEST), manifestBytes);
            if (Files.exists(destination)) deleteTree(destination);
            try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(staging, destination); }
        } finally { if (Files.exists(staging)) deleteTree(staging); }
    }

    private Path cachePath(ChangeTaskId changeId, String runId) {
        Path parent = root.resolve(changeId.value()).normalize();
        Path result = parent.resolve(runId).normalize();
        if (!result.startsWith(root) || !result.getParent().equals(parent)) throw new ChangeValidationException("非法 Evidence cache identity");
        return result;
    }
    private static String prefix(ChangeTaskId changeId, String runId) { return "evidence/" + changeId.value() + "/" + runId + "/"; }
    private static void safeId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,160}")) throw new ChangeValidationException(name + " 非法");
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }
    @Override public synchronized void close() {
        try { objects.close(); } finally { try { connection.close(); } catch (SQLException ignored) { } }
    }

    public record VerificationSummary(long archives, long objects) { }
    private record ArchiveIdentity(String changeId, String runId) { }
}
