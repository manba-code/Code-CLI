package com.paicli.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class FileObjectStorageTest {
    @TempDir Path root;

    @Test
    void putIsImmutableIdempotentAndListable() throws Exception {
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] other = "other".getBytes(StandardCharsets.UTF_8);
        try (FileObjectStorage store = new FileObjectStorage(root.resolve("objects"))) {
            store.putIfAbsent("evidence/change/run/result.json", first, sha(first));
            store.putIfAbsent("evidence/change/run/result.json", first, sha(first));
            assertArrayEquals(first, store.get("evidence/change/run/result.json", first.length));
            assertEquals(sha(first), store.metadata("evidence/change/run/result.json").orElseThrow().sha256());
            assertEquals(java.util.List.of("evidence/change/run/result.json"), store.list("evidence/change/run/"));
            assertThrows(ChangeConflictException.class,
                    () -> store.putIfAbsent("evidence/change/run/result.json", other, sha(other)));
            store.checkHealth();
        }
    }

    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
