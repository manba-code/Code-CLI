package com.paicli.change;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Immutable Evidence archive seam. Implementations may materialize remote objects into a trusted local cache. */
public interface EvidenceStore extends AutoCloseable {
    Path root();

    String manifestSha256(ChangeTaskId changeId, String runId);

    Capture capture(ChangeTaskId changeId, String runId, Path source) throws Exception;

    void verify(ChangeTaskId changeId, String runId, Path expectedPath) throws IOException;

    void discardSource(Path source);

    void checkHealth();

    @Override
    void close();

    record Capture(Path path, String manifestSha256, List<ObjectRecord> objects) { }

    record ObjectRecord(String relativePath, String sha256, long sizeBytes) { }
}
