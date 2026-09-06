package com.paicli.change;

/** Explicit M6b configuration. SQLite remains the default and the mandatory offline-demo backend. */
public record ProductionStorageSettings(
        String jdbcUrl, String user, String password,
        String objectEndpoint, String objectBucket, String objectRegion,
        String objectAccessKey, String objectSecretKey,
        int workerCount, long queueLeaseMillis, long queuePollMillis
) {
    public static boolean enabled() {
        return "postgresql".equalsIgnoreCase(value("paichange.storage", "PAICHANGE_STORAGE", "sqlite"));
    }

    public static ProductionStorageSettings fromProcess() {
        if (!enabled()) throw new IllegalStateException("PAICHANGE_STORAGE 未启用 postgresql");
        String objectType = value("paichange.object.store", "PAICHANGE_OBJECT_STORE", "");
        if (!"s3".equalsIgnoreCase(objectType)) throw new IllegalStateException("生产存储要求 PAICHANGE_OBJECT_STORE=s3");
        return new ProductionStorageSettings(
                required("paichange.postgres.url", "PAICHANGE_POSTGRES_URL"),
                required("paichange.postgres.user", "PAICHANGE_POSTGRES_USER"),
                required("paichange.postgres.password", "PAICHANGE_POSTGRES_PASSWORD"),
                required("paichange.s3.endpoint", "PAICHANGE_S3_ENDPOINT"),
                required("paichange.s3.bucket", "PAICHANGE_S3_BUCKET"),
                value("paichange.s3.region", "PAICHANGE_S3_REGION", "us-east-1"),
                required("paichange.s3.access.key", "PAICHANGE_S3_ACCESS_KEY"),
                required("paichange.s3.secret.key", "PAICHANGE_S3_SECRET_KEY"),
                integer("paichange.queue.workers", "PAICHANGE_QUEUE_WORKERS", 2, 1, 32),
                integer("paichange.queue.lease.ms", "PAICHANGE_QUEUE_LEASE_MS", 60_000, 5_000, 3_600_000),
                integer("paichange.queue.poll.ms", "PAICHANGE_QUEUE_POLL_MS", 250, 10, 60_000));
    }

    private static int integer(String property, String environment, int fallback, int min, int max) {
        String configured = value(property, environment, Integer.toString(fallback));
        try {
            int parsed = Integer.parseInt(configured);
            if (parsed < min || parsed > max) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(environment + " 必须在 " + min + ".." + max + " 范围内");
        }
    }

    private static String required(String property, String environment) {
        String configured = value(property, environment, "");
        if (configured.isBlank()) throw new IllegalStateException(environment + " 必填");
        return configured;
    }

    private static String value(String property, String environment, String fallback) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) configured = System.getenv(environment);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }
}
