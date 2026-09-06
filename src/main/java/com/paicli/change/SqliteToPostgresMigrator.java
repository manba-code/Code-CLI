package com.paicli.change;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline, idempotent M6b cutover importer. The caller must stop SQLite writes before invoking it. */
public final class SqliteToPostgresMigrator {
    private SqliteToPostgresMigrator() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: SqliteToPostgresMigrator <changes.db> <evidence-archive> <evidence-cache>");
        }
        ProductionStorageSettings settings = ProductionStorageSettings.fromProcess();
        ObjectStorage objects = new S3ObjectStorage(settings.objectEndpoint(), settings.objectBucket(),
                settings.objectRegion(), settings.objectAccessKey(), settings.objectSecretKey());
        Result result = migrate(Path.of(args[0]), Path.of(args[1]), settings.jdbcUrl(), settings.user(),
                settings.password(), objects, Path.of(args[2]));
        System.out.println("M6b migration complete: tasks=" + result.tasks() + ", events=" + result.events()
                + ", toolApprovals=" + result.toolApprovals() + ", projectPolicies=" + result.projectPolicies()
                + ", evidenceArchives=" + result.evidenceArchives() + ", schemaVersion=" + result.schemaVersion());
    }

    public static Result migrate(Path sqliteDatabase, Path sqliteEvidenceRoot,
                                 String jdbcUrl, String user, String password,
                                 ObjectStorage objects, Path targetCacheRoot) throws Exception {
        int tasks = 0, events = 0, approvals = 0, evidence = 0;
        try (SqliteChangeStore source = new SqliteChangeStore(sqliteDatabase);
             TrustedEvidenceStore sourceEvidence = new TrustedEvidenceStore(sqliteDatabase, sqliteEvidenceRoot);
             PostgresChangeStore target = new PostgresChangeStore(jdbcUrl, user, password);
             PostgresEvidenceStore targetEvidence = new PostgresEvidenceStore(
                     jdbcUrl, user, password, objects, targetCacheRoot)) {
            Map<String, ProjectToolPolicy> policies = new LinkedHashMap<>();
            List<ChangeTask> sourceTasks = source.list();
            java.util.Set<ChangeTaskId> sourceIds = sourceTasks.stream().map(ChangeTask::id)
                    .collect(java.util.stream.Collectors.toSet());
            List<ChangeTask> foreignTargets = target.list().stream().filter(task -> !sourceIds.contains(task.id())).toList();
            if (!foreignTargets.isEmpty()) {
                throw new ChangeConflictException("目标 PostgreSQL 不是空库或本次迁移的幂等重试目标");
            }
            for (ChangeTask original : sourceTasks) {
                String projectId = ChangeProject.id(original.repository());
                policies.putIfAbsent(projectId, source.policy(projectId));
                List<ChangeEvent> history = source.events(original.id());
                List<ToolApproval> tools = source.approvals(original.id());
                ChangeTask migrated = migrateEvidencePath(original, targetEvidence.root());
                target.importSnapshot(migrated, history, List.of(policies.get(projectId)), tools);
                tasks++; events += history.size(); approvals += tools.size();
                if (original.run() != null) {
                    Path sourcePath = original.run().evidencePath();
                    if (!sourcePath.toAbsolutePath().normalize().startsWith(sqliteEvidenceRoot.toAbsolutePath().normalize()))
                        throw new ChangeConflictException("历史 Evidence 不属于声明的 SQLite archive 根: " + original.id());
                    sourceEvidence.verify(original.id(), original.run().runId(), sourcePath);
                    Path staging = stageLegacyEvidence(sourcePath, targetCacheRoot);
                    try { targetEvidence.capture(original.id(), original.run().runId(), staging); }
                    finally { deleteTree(staging); }
                    targetEvidence.verify(original.id(), original.run().runId(), migrated.run().evidencePath());
                    evidence++;
                }
            }
            target.checkHealth(); targetEvidence.checkHealth();
            return new Result(tasks, events, approvals, policies.size(), evidence,
                    target.schemaVersion());
        }
    }

    private static Path stageLegacyEvidence(Path source, Path cacheRoot) throws Exception {
        Path stagingRoot = cacheRoot.toAbsolutePath().normalize().resolve(".migration-staging");
        Files.createDirectories(stagingRoot);
        Path staging = Files.createTempDirectory(stagingRoot, "evidence-");
        try {
            try (var paths = Files.walk(source)) {
                for (Path file : paths.filter(path -> !path.equals(source)).toList()) {
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        throw new ChangeConflictException("历史 Evidence 包含非法对象");
                    String relative = source.relativize(file).toString().replace('\\', '/');
                    if ("manifest.json".equals(relative)) continue;
                    if (relative.contains("/")) throw new ChangeConflictException("历史 Evidence 不是扁平归档");
                    Files.copy(file, staging.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            return staging;
        } catch (Exception failure) {
            deleteTree(staging);
            throw failure;
        }
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private static ChangeTask migrateEvidencePath(ChangeTask task, Path cacheRoot) {
        if (task.run() == null) return task;
        RunRef run = task.run();
        Path target = cacheRoot.toAbsolutePath().normalize().resolve(task.id().value()).resolve(run.runId()).normalize();
        RunRef migratedRun = new RunRef(run.runId(), run.specDigest(), run.status(), run.verdict(), run.workspaceId(),
                run.branch(), run.headSha(), target, run.completedAt());
        return new ChangeTask(task.id(), task.idempotencyKey(), task.version(), task.state(), task.source(),
                task.repository(), task.title(), task.requirement(), task.requesterId(), task.projectContext(),
                task.referencedContext(), task.spec(), task.risk(), task.route(), task.specApproval(),
                task.deliveryApproval(), task.workerClaim(), migratedRun, task.draftJob(), task.humanReview(),
                task.createdAt(), task.updatedAt());
    }

    public record Result(int tasks, int events, int toolApprovals, int projectPolicies,
                         int evidenceArchives, int schemaVersion) { }
}
