package com.paicli.change;

import java.nio.file.Path;
import java.util.Objects;

public record SpecRef(
        String specId,
        int revision,
        String digest,
        Path draftPath,
        Path lockedPath
) {
    public SpecRef {
        specId = requireText(specId, "specId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须大于等于 1");
        }
        digest = requireText(digest, "digest");
        draftPath = normalize(draftPath);
        lockedPath = normalize(lockedPath);
        if (draftPath == null && lockedPath == null) {
            throw new IllegalArgumentException("draftPath 和 lockedPath 不能同时为空");
        }
    }

    public boolean locked() {
        return lockedPath != null;
    }

    public SpecRef lockAt(Path path) {
        if (locked()) {
            throw new IllegalStateException("ChangeSpec 已锁定");
        }
        return new SpecRef(specId, revision, digest, draftPath, path);
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
