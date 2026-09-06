package com.paicli.change;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Supplies optional short-lived, file-only task credentials. Default PaiChange assembly injects none. */
@FunctionalInterface
public interface EphemeralSecretProvider {
    EphemeralSecretProvider NONE = task -> SecretLease.empty();

    SecretLease acquire(ChangeTask task) throws Exception;

    static EphemeralSecretProvider none() { return NONE; }

    record SecretLease(Map<String, byte[]> filesByEnvironmentVariable, Instant expiresAt) implements AutoCloseable {
        public SecretLease {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            if (filesByEnvironmentVariable != null) {
                filesByEnvironmentVariable.forEach((name, value) -> {
                    String key = Objects.requireNonNull(name, "secret environment name").trim();
                    if (!key.matches("[A-Z][A-Z0-9_]*_FILE")) {
                        throw new IllegalArgumentException("Secret 只能通过 *_FILE 环境变量注入");
                    }
                    byte[] bytes = Objects.requireNonNull(value, "secret bytes").clone();
                    if (bytes.length == 0 || bytes.length > 65_536) {
                        throw new IllegalArgumentException("单个 Secret 必须为 1..65536 字节");
                    }
                    copy.put(key, bytes);
                });
            }
            filesByEnvironmentVariable = Map.copyOf(copy);
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
        }

        public static SecretLease empty() { return new SecretLease(Map.of(), Instant.MAX); }

        public boolean expired(Instant now) { return !expiresAt.isAfter(now); }

        @Override
        public void close() {
            filesByEnvironmentVariable.values().forEach(bytes -> java.util.Arrays.fill(bytes, (byte) 0));
        }
    }
}
