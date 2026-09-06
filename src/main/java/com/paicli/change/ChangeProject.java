package com.paicli.change;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable non-secret project boundary derived from the persisted repository identity. */
public final class ChangeProject {
    private ChangeProject() { }

    public static String id(RepositoryRef repository) {
        String canonical = repository.repository().trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "project_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
