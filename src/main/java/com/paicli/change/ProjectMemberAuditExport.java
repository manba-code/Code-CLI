package com.paicli.change;

import java.nio.charset.StandardCharsets;

/** Immutable, checksummed JSON Lines export intended for controlled SIEM/offline archival ingestion. */
public record ProjectMemberAuditExport(byte[] content, long recordCount, String sha256) {
    public ProjectMemberAuditExport {
        content = content == null ? new byte[0] : content.clone();
        if (recordCount < 0) throw new IllegalArgumentException("recordCount 不能为负数");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 无效");
    }

    @Override public byte[] content() { return content.clone(); }
    public String utf8() { return new String(content, StandardCharsets.UTF_8); }
}
