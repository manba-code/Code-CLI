package com.paicli.change;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Second phase of the M7b script: only restored PostgreSQL and restored S3 bucket are in scope. */
class M7bRecoveryVerificationTest {
    @TempDir Path root;

    @Test void restoredDatabaseAndEvidenceMeetDeclaredRecoveryObjectives() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("paichange.m7b.recovery.enabled"));
        String jdbc = System.getProperty("paichange.m7b.restore.jdbc");
        String bucket = System.getProperty("paichange.m7b.restore.bucket");
        S3ObjectStorage objects = new S3ObjectStorage(System.getProperty("paichange.m7b.test.s3.endpoint"), bucket,
                "us-east-1", "paichange-test", "paichange-test-secret");
        ProductionRecoveryVerifier.Report report = ProductionRecoveryVerifier.verify(jdbc, "paichange",
                "paichange-test-only", objects, root.resolve("restored-cache"),
                Instant.parse(System.getProperty("paichange.m7b.recovery.point-at")),
                Instant.parse(System.getProperty("paichange.m7b.backup.completed-at")),
                Instant.parse(System.getProperty("paichange.m7b.recovery.started-at")),
                new ProductionOperationsSettings(300, 600));
        assertTrue(report.tasks() >= 1);
        assertTrue(report.events() >= 1);
        assertTrue(report.memberAudits() >= 2);
        assertTrue(report.evidenceArchives() >= 1);
        assertTrue(report.evidenceObjects() >= 3);
        assertTrue(report.rpoMet());
        assertTrue(report.rtoMet());
        System.out.println("M7b recovery verification: " + ChangeJson.MAPPER.writeValueAsString(report));
    }
}
