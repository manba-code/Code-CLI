package com.paicli.change;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Filesystem object adapter for contract tests and single-host development. */
public final class FileObjectStorage implements ObjectStorage {
    private final Path root;

    public FileObjectStorage(Path root) throws IOException {
        Path configured = root.toAbsolutePath().normalize();
        Files.createDirectories(configured);
        if (Files.isSymbolicLink(configured)) throw new IOException("对象存储根目录不能是符号链接");
        this.root = configured.toRealPath();
    }

    @Override public synchronized void putIfAbsent(String key, byte[] content, String sha256) throws IOException {
        Path target = resolve(key);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Metadata current = metadata(key).orElseThrow();
            if (current.sizeBytes() != content.length || !current.sha256().equals(sha256))
                throw new ChangeConflictException("同一对象 key 已有不同内容: " + key);
            return;
        }
        Files.createDirectories(target.getParent());
        Path temp = target.getParent().resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.write(temp, content);
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target); }
        catch (java.nio.file.FileAlreadyExistsException raced) {
            Files.deleteIfExists(temp);
            Metadata current = metadata(key).orElseThrow();
            if (current.sizeBytes() != content.length || !current.sha256().equals(sha256))
                throw new ChangeConflictException("同一对象 key 已有不同内容: " + key);
        }
    }

    @Override public byte[] get(String key, long maxBytes) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("对象不存在: " + key);
        long size = Files.size(path);
        if (size > maxBytes) throw new IOException("对象超过读取上限: " + key);
        return Files.readAllBytes(path);
    }

    @Override public Optional<Metadata> metadata(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("对象不是普通文件: " + key);
        return Optional.of(new Metadata(Files.size(path), sha256(Files.readAllBytes(path))));
    }

    @Override public List<String> list(String prefix) throws IOException {
        String normalized = normalizeKey(prefix, true);
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(key -> key.startsWith(normalized)).sorted().toList();
        }
    }

    @Override public void checkHealth() {
        if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root))
            throw new IllegalStateException("文件对象存储不可用");
    }

    private Path resolve(String key) throws IOException {
        String normalized = normalizeKey(key, false);
        Path value = root.resolve(normalized).normalize();
        if (!value.startsWith(root)) throw new IOException("对象 key 逃逸根目录");
        Path cursor = root;
        for (Path part : root.relativize(value)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor))
                throw new IOException("对象路径不允许符号链接");
        }
        return value;
    }

    private static String normalizeKey(String key, boolean prefix) {
        String value = key == null ? "" : key.trim().replace('\\', '/');
        if ((!prefix && value.isEmpty()) || value.startsWith("/") || value.contains("//")
                || List.of(value.split("/")).contains("..")) throw new IllegalArgumentException("非法对象 key");
        return value;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IOException("无法计算对象哈希", e); }
    }
}
