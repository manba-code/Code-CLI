package com.paicli.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/** Verifies a restored PostgreSQL + S3 Evidence recovery point before traffic can be reopened. */
public final class ProductionRecoveryVerifier {
    private ProductionRecoveryVerifier() { }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 4) {
            throw new IllegalArgumentException("用法: ProductionRecoveryVerifier <recoveryPointAt> <backupCompletedAt> <recoveryStartedAt> <cacheRoot>");
        }
        ProductionStorageSettings storage = ProductionStorageSettings.fromProcess();
        ProductionOperationsSettings objectives = ProductionOperationsSettings.fromProcess();
        try (ObjectStorage objects = new S3ObjectStorage(storage.objectEndpoint(), storage.objectBucket(),
                storage.objectRegion(), storage.objectAccessKey(), storage.objectSecretKey())) {
            Report report = verify(storage.jdbcUrl(), storage.user(), storage.password(), objects, Path.of(args[3]),
                    Instant.parse(args[0]), Instant.parse(args[1]), Instant.parse(args[2]), objectives);
            System.out.println(ChangeJson.MAPPER.writeValueAsString(report));
        }
    }

    public static Report verify(String jdbcUrl, String user, String password, ObjectStorage objects, Path cacheRoot,
                                Instant recoveryPointAt, Instant backupCompletedAt, Instant recoveryStartedAt,
                                ProductionOperationsSettings objectives) throws Exception {
        if (recoveryPointAt == null || backupCompletedAt == null || recoveryStartedAt == null
                || recoveryPointAt.isAfter(backupCompletedAt) || backupCompletedAt.isAfter(recoveryStartedAt)) {
            throw new IllegalArgumentException("时间顺序必须是 recoveryPointAt <= backupCompletedAt <= recoveryStartedAt");
        }
        Instant verificationStartedAt = Instant.now();
        long tasks;
        long events;
        long memberAudits;
        long publications;
        try (PostgresChangeStore store = new PostgresChangeStore(jdbcUrl, user, password);
             PostgresEvidenceStore evidence = new PostgresEvidenceStore(jdbcUrl, user, password, objects, cacheRoot);
             var connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            store.checkHealth();
            PostgresEvidenceStore.VerificationSummary evidenceSummary = evidence.verifyAll();
            tasks = scalar(connection, "SELECT COUNT(*) FROM change_tasks");
            events = scalar(connection, "SELECT COUNT(*) FROM change_events");
            memberAudits = scalar(connection, "SELECT COUNT(*) FROM project_member_audit");
            publications = scalar(connection, "SELECT COUNT(*) FROM scm_publications");
            long invalidCompleted = scalar(connection, """
                    SELECT COUNT(*) FROM change_tasks t
                    WHERE t.state='COMPLETED' AND NOT EXISTS (
                      SELECT 1 FROM scm_publications p WHERE p.change_id=t.id AND p.conclusion='success')
                    """);
            if (invalidCompleted != 0) {
                throw new ChangeConflictException("恢复数据包含没有 success publication 的 COMPLETED 任务");
            }
            long duplicatePublications = scalar(connection, """
                    SELECT COUNT(*) FROM (
                      SELECT change_id, task_version FROM scm_publications
                      GROUP BY change_id, task_version HAVING COUNT(*) > 1
                    ) duplicates
                    """);
            if (duplicatePublications != 0) throw new ChangeConflictException("恢复数据包含重复发布身份");
            Instant finishedAt = Instant.now();
            long backupDuration = Math.max(0, Duration.between(recoveryPointAt, backupCompletedAt).toSeconds());
            long observedRpo = Math.max(0, Duration.between(recoveryPointAt, recoveryStartedAt).toSeconds());
            long observedRto = Math.max(0, Duration.between(recoveryStartedAt, finishedAt).toSeconds());
            String digest = digest(tasks, events, memberAudits, publications,
                    evidenceSummary.archives(), evidenceSummary.objects(), store.schemaVersion());
            Report report = new Report(store.schemaVersion(), tasks, events, memberAudits, publications,
                    evidenceSummary.archives(), evidenceSummary.objects(), recoveryPointAt, backupCompletedAt,
                    recoveryStartedAt, verificationStartedAt, finishedAt, backupDuration, observedRpo, observedRto,
                    objectives.rpoSeconds(),
                    objectives.rtoSeconds(), observedRpo <= objectives.rpoSeconds(),
                    observedRto <= objectives.rtoSeconds(), digest);
            if (!report.rpoMet() || !report.rtoMet()) {
                throw new ChangeValidationException("恢复完整性通过，但实测 RPO/RTO 超出声明目标");
            }
            return report;
        }
    }

    private static long scalar(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            if (!row.next()) throw new IllegalStateException("恢复校验查询没有结果");
            return row.getLong(1);
        }
    }

    private static String digest(long... values) throws Exception {
        StringBuilder canonical = new StringBuilder("paichange/recovery-report/v1");
        for (long value : values) canonical.append('\n').append(value);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public record Report(int schemaVersion, long tasks, long events, long memberAudits, long publications,
                         long evidenceArchives, long evidenceObjects, Instant recoveryPointAt,
                         Instant backupCompletedAt, Instant recoveryStartedAt, Instant verificationStartedAt,
                         Instant verifiedAt, long backupDurationSeconds, long observedRpoSeconds,
                         long observedRtoSeconds, long targetRpoSeconds,
                         long targetRtoSeconds, boolean rpoMet, boolean rtoMet, String integrityDigest) { }
}
