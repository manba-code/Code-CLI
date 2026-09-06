package com.paicli.change;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Immutable object storage seam used by the Evidence archive. */
public interface ObjectStorage extends AutoCloseable {
    void putIfAbsent(String key, byte[] content, String sha256) throws IOException;

    byte[] get(String key, long maxBytes) throws IOException;

    Optional<Metadata> metadata(String key) throws IOException;

    List<String> list(String prefix) throws IOException;

    void checkHealth();

    @Override default void close() { }

    record Metadata(long sizeBytes, String sha256) { }
}
