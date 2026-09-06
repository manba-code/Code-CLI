package com.paicli.change;

/** Operator-declared recovery objectives. M7b verifies measurements against these values; they are not an HA claim. */
public record ProductionOperationsSettings(long rpoSeconds, long rtoSeconds) {
    public ProductionOperationsSettings {
        if (rpoSeconds < 60 || rpoSeconds > 604_800) {
            throw new IllegalArgumentException("PAICHANGE_BACKUP_RPO_SECONDS 必须在 60..604800 范围内");
        }
        if (rtoSeconds < 60 || rtoSeconds > 86_400) {
            throw new IllegalArgumentException("PAICHANGE_RECOVERY_RTO_SECONDS 必须在 60..86400 范围内");
        }
    }

    public static ProductionOperationsSettings fromProcess() {
        return new ProductionOperationsSettings(
                requiredLong("paichange.backup.rpo.seconds", "PAICHANGE_BACKUP_RPO_SECONDS"),
                requiredLong("paichange.recovery.rto.seconds", "PAICHANGE_RECOVERY_RTO_SECONDS"));
    }

    private static long requiredLong(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        if (value == null || value.isBlank()) throw new IllegalStateException(environment + " 必填");
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException e) { throw new IllegalStateException(environment + " 必须是整数秒", e); }
    }
}
