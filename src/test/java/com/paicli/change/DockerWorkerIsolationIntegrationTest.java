package com.paicli.change;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Docker acceptance. It never pulls or builds an image. */
class DockerWorkerIsolationIntegrationTest {
    @TempDir Path root;

    @Test
    void twoLiveTasksCanOnlyReadAndModifyTheirOwnWorktrees() throws Exception {
        try (RealDocker docker = RealDocker.require(root)) {
            Path control = Files.createDirectories(root.resolve("control"));
            Path database = Files.writeString(control.resolve("changes.db"), "sqlite-secret");
            Path archive = Files.createDirectories(control.resolve("evidence-archive"));
            Files.writeString(archive.resolve("result.json"), "evidence-secret");
            Path hostSecret = Files.writeString(control.resolve("host-test-secret"), "host-secret-value");
            Path source = Files.createDirectories(root.resolve("source-repository"));
            Files.writeString(source.resolve("source-only.txt"), "source-secret");
            WorkspaceProvisioner.WorkspaceLease first = workspace("change_111111111111", "first", source);
            WorkspaceProvisioner.WorkspaceLease second = workspace("change_222222222222", "second", source);
            DockerWorkerIsolation isolation = docker.isolation(Map.of(), EphemeralSecretProvider.none(), 128, 32);

            try (WorkerIsolation.Session firstSession = isolation.open(task(first), first);
                 WorkerIsolation.Session secondSession = isolation.open(task(second), second)) {
                String forbiddenForFirst = absentPaths(second.workspaceRoot(), source, database, archive,
                        Path.of(System.getProperty("user.home")), hostSecret, Path.of("/var/run/docker.sock"));
                String forbiddenForSecond = absentPaths(first.workspaceRoot(), source, database, archive,
                        Path.of(System.getProperty("user.home")), hostSecret, Path.of("/var/run/docker.sock"));
                var firstResult = firstSession.executeCommand(
                        "test \"$(cat owner.txt)\" = first && " + forbiddenForFirst
                                + " && printf first-updated > owner.txt && printf first-created > created.txt",
                        Duration.ofSeconds(10));
                var secondResult = secondSession.executeCommand(
                        "test \"$(cat owner.txt)\" = second && " + forbiddenForSecond
                                + " && printf second-updated > owner.txt && printf second-created > created.txt",
                        Duration.ofSeconds(10));
                assertEquals(0, firstResult.exitCode(), firstResult.output());
                assertEquals(0, secondResult.exitCode(), secondResult.output());
            } finally {
                isolation.close();
            }

            assertEquals("first-updated", Files.readString(first.workspaceRoot().resolve("owner.txt")));
            assertEquals("second-updated", Files.readString(second.workspaceRoot().resolve("owner.txt")));
            assertEquals("first-created", Files.readString(first.workspaceRoot().resolve("created.txt")));
            assertEquals("second-created", Files.readString(second.workspaceRoot().resolve("created.txt")));
            docker.assertNoTaskContainers();
        }
    }

    @Test
    void defaultDenyAndLabeledInternalProxyEnforceAndAuditTheHostAllowlist() throws Exception {
        try (RealDocker docker = RealDocker.require(root)) {
            Path repository = Files.createDirectories(root.resolve("network-repository"));
            WorkspaceProvisioner.WorkspaceLease deniedWorkspace = workspace(
                    "change_333333333333", "network-denied", repository);
            DockerWorkerIsolation deniedIsolation = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32);
            try (WorkerIsolation.Session denied = deniedIsolation.open(task(deniedWorkspace), deniedWorkspace)) {
                for (String url : List.of(
                        "http://host.docker.internal:65535/host",
                        "http://169.254.169.254/latest/meta-data/",
                        "http://example.com/")) {
                    var result = denied.executeCommand(
                            "wget -Y off -q -T 2 -O - " + shellQuote(url), Duration.ofSeconds(5));
                    assertNotEquals(0, result.exitCode(), "network=none unexpectedly reached " + url);
                }
                assertFalse(denied.networkDecision("web_fetch",
                        "{\"url\":\"https://allowed.example/package\"}").allowed());
            }

            WorkspaceProvisioner.WorkspaceLease allowedWorkspace = workspace(
                    "change_444444444444", "network-allowed", repository);
            ChangeTask allowedTask = task(allowedWorkspace);
            String projectId = ChangeProject.id(allowedTask.repository());
            String network = docker.uniqueName("egress");
            DockerWorkerIsolation.Egress egress = new DockerWorkerIsolation.Egress(network,
                    "http://egress-proxy:3128", Set.of("allowed.example"), Set.of("web_fetch"));
            Path audit = docker.startAuditedProxy(network, projectId, egress.policyDigest());
            DockerWorkerIsolation allowedIsolation = docker.isolation(
                    Map.of(projectId, egress), EphemeralSecretProvider.none(), 128, 32);
            try (WorkerIsolation.Session allowed = allowedIsolation.open(allowedTask, allowedWorkspace)) {
                assertTrue(allowed.networkDecision("web_fetch",
                        "{\"url\":\"http://allowed.example/package\"}").allowed());
                assertFalse(allowed.networkDecision("web_fetch",
                        "{\"url\":\"http://blocked.example/package\"}").allowed());
                var accepted = allowed.executeCommand(
                        "wget -q -T 3 -O - http://allowed.example/package", Duration.ofSeconds(5));
                assertEquals(0, accepted.exitCode(), accepted.output());
                assertEquals("allowed-through-proxy", accepted.output());
                var rejected = allowed.executeCommand(
                        "wget -q -T 3 -O - http://blocked.example/package", Duration.ofSeconds(5));
                assertNotEquals(0, rejected.exitCode(), "proxy must reject non-allowlisted host");
            }
            String requests = Files.readString(audit.resolve("requests.log"));
            assertTrue(requests.contains("GET http://allowed.example/package"), requests);
            assertTrue(requests.contains("ALLOW allowed.example"), requests);
            assertTrue(requests.contains("DENY blocked.example"), requests);

            for (Map.Entry<String, String> invalid : Map.of(
                    "wrong-egress-label", "false|" + projectId + "|" + egress.policyDigest(),
                    "wrong-project-label", "true|project_wrong|" + egress.policyDigest(),
                    "wrong-policy-digest", "true|" + projectId + "|" + "0".repeat(64)).entrySet()) {
                String invalidNetwork = docker.createEgressNetwork(invalid.getKey(), invalid.getValue());
                DockerWorkerIsolation.Egress invalidEgress = new DockerWorkerIsolation.Egress(invalidNetwork,
                        egress.proxyUrl(), egress.allowedHosts(), egress.allowedTools());
                DockerWorkerIsolation invalidIsolation = docker.isolation(
                        Map.of(projectId, invalidEgress), EphemeralSecretProvider.none(), 128, 32);
                assertThrows(IllegalStateException.class,
                        () -> invalidIsolation.open(allowedTask, allowedWorkspace), invalid.getKey());
            }
        }
    }

    @Test
    void activeCancellationImmediatelyRemovesTheContainerProcessTree() throws Exception {
        try (RealDocker docker = RealDocker.require(root)) {
            Path repository = Files.createDirectories(root.resolve("cancel-repository"));
            WorkspaceProvisioner.WorkspaceLease workspace = workspace(
                    "change_555555555555", "cancel", repository);
            DockerWorkerIsolation isolation = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32);
            WorkerIsolation.Session session = isolation.open(task(workspace), workspace);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var command = executor.submit(() -> session.executeCommand(
                        "printf started > cancel-started; sleep 300", Duration.ofSeconds(10)));
                awaitFile(workspace.workspaceRoot().resolve("cancel-started"), Duration.ofSeconds(5));
                long startedAt = System.nanoTime();
                var cancellation = executor.submit(() -> session.abort("acceptance cancellation"));
                cancellation.get(2, TimeUnit.SECONDS);
                assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(2)) < 0,
                        "active cancellation must not wait for the command timeout");
                assertEquals(com.paicli.tool.CommandExecutionResult.Status.CANCELED,
                        command.get(3, TimeUnit.SECONDS).status());
                docker.assertNoTaskContainers();
            } finally {
                session.close();
                executor.shutdownNow();
                executor.awaitTermination(3, TimeUnit.SECONDS);
                isolation.close();
            }
        }
    }

    @Test
    void cgroupPidMemoryAndCommandTimeoutLimitsAreActuallyEnforced() throws Exception {
        try (RealDocker docker = RealDocker.require(root)) {
            Path repository = Files.createDirectories(root.resolve("resource-repository"));
            WorkspaceProvisioner.WorkspaceLease memoryWorkspace = workspace(
                    "change_666666666666", "memory", repository);
            DockerWorkerIsolation memoryIsolation = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32);
            try (WorkerIsolation.Session memorySession = memoryIsolation.open(task(memoryWorkspace), memoryWorkspace)) {
                var limits = memorySession.executeCommand(
                        "read pids < /sys/fs/cgroup/pids.max; read memory < /sys/fs/cgroup/memory.max; "
                                + "printf 'pids=%s memory=%s' \"$pids\" \"$memory\"",
                        Duration.ofSeconds(5));
                assertEquals(0, limits.exitCode(), limits.output());
                assertEquals("pids=32 memory=134217728", limits.output());

                var memory = memorySession.executeCommand("""
                        before=0
                        while read key value; do [ "$key" = oom_kill ] && before=$value; done < /sys/fs/cgroup/memory.events
                        awk 'BEGIN{s="x"; for(i=0;i<29;i++)s=s s; print length(s)}'
                        after=0
                        while read key value; do [ "$key" = oom_kill ] && after=$value; done < /sys/fs/cgroup/memory.events
                        printf 'oom_kill_delta=%s' "$((after-before))"
                        test "$after" -gt "$before"
                        """, Duration.ofSeconds(25));
                assertEquals(0, memory.exitCode(), memory.output());
                assertTrue(memory.output().matches("(?s).*oom_kill_delta=[1-9][0-9]*.*"), memory.output());
            } finally {
                memoryIsolation.close();
            }

            WorkspaceProvisioner.WorkspaceLease pidsWorkspace = workspace(
                    "change_888888888888", "pids", repository);
            DockerWorkerIsolation pidsIsolation = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 16);
            WorkerIsolation.Session pidsSession = pidsIsolation.open(task(pidsWorkspace), pidsWorkspace);
            try {
                String container = docker.taskContainer(pidsWorkspace.changeId());
                int beforePidRejections = docker.cgroupEvent(container, "pids.events", "max");
                DockerWorkerIsolation.DockerResult pressure = docker.run(List.of(
                        "docker", "exec", "-d", container, "sh", "-c", """
                        i=0
                        while [ "$i" -lt 32 ]; do sleep 2 & i=$((i+1)); done
                        wait
                        """), Duration.ofSeconds(5));
                assertEquals(0, pressure.exitCode(), pressure.output());
                Thread.sleep(2500);
                int afterPidRejections = docker.cgroupEvent(container, "pids.events", "max");
                assertTrue(afterPidRejections > beforePidRejections,
                        "16 PID cgroup must record at least one rejected fork");
            } finally {
                pidsSession.close();
                pidsIsolation.close();
            }

            WorkspaceProvisioner.WorkspaceLease timeoutWorkspace = workspace(
                    "change_777777777777", "timeout", repository);
            DockerWorkerIsolation timeoutIsolation = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32);
            WorkerIsolation.Session timeoutSession = timeoutIsolation.open(task(timeoutWorkspace), timeoutWorkspace);
            try {
                assertEquals(com.paicli.tool.CommandExecutionResult.Status.TIMED_OUT,
                        timeoutSession.executeCommand("sleep 300", Duration.ofMillis(500)).status());
                docker.assertNoTaskContainers();
            } finally {
                timeoutSession.close();
                timeoutIsolation.close();
            }
        }
    }

    @Test
    void expiringFileOnlySecretAndUnexpectedContainerExitFailClosedAndCleanUp() throws Exception {
        try (RealDocker docker = RealDocker.require(root)) {
            Path repository = Files.createDirectories(root.resolve("failure-repository"));
            WorkspaceProvisioner.WorkspaceLease secretWorkspace = workspace(
                    "change_999999999999", "secret", repository);
            byte[] value = "target-host-secret-value".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Instant expiresAt = Instant.now().plusSeconds(3);
            EphemeralSecretProvider provider = ignored -> new EphemeralSecretProvider.SecretLease(
                    Map.of("TEST_TOKEN_FILE", value), expiresAt);
            DockerWorkerIsolation secretIsolation = docker.isolation(Map.of(), provider, 128, 32);
            WorkerIsolation.Session secretSession = secretIsolation.open(task(secretWorkspace), secretWorkspace);
            try {
                var visibleByPathOnly = secretSession.executeCommand(
                        "test -f \"$TEST_TOKEN_FILE\" && test \"$(cat \"$TEST_TOKEN_FILE\")\" = target-host-secret-value "
                                + "&& test \"$(stat -c %a \"$TEST_TOKEN_FILE\")\" = 600 && printf secret-ok",
                        Duration.ofSeconds(2));
                assertEquals(0, visibleByPathOnly.exitCode(), visibleByPathOnly.output());
                assertEquals("secret-ok", visibleByPathOnly.output());
                String container = docker.taskContainer(secretWorkspace.changeId());
                DockerWorkerIsolation.DockerResult inspect = docker.run(
                        List.of("docker", "inspect", container), Duration.ofSeconds(5));
                assertFalse(inspect.output().contains("target-host-secret-value"),
                        "secret value must not enter Docker metadata");
                while (Instant.now().isBefore(expiresAt.plusMillis(50))) Thread.sleep(25);
                assertEquals(com.paicli.tool.CommandExecutionResult.Status.CANCELED,
                        secretSession.executeCommand("true", Duration.ofSeconds(1)).status());
                docker.assertNoTaskContainers();
            } finally {
                secretSession.close();
                secretIsolation.close();
            }

            WorkspaceProvisioner.WorkspaceLease exitWorkspace = workspace(
                    "change_aaaaaaaaaaab", "unexpected-exit", repository);
            DockerWorkerIsolation exitIsolation = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32);
            WorkerIsolation.Session exitSession = exitIsolation.open(task(exitWorkspace), exitWorkspace);
            try {
                String container = docker.taskContainer(exitWorkspace.changeId());
                DockerWorkerIsolation.DockerResult killed = docker.run(
                        List.of("docker", "kill", container), Duration.ofSeconds(5));
                assertEquals(0, killed.exitCode(), killed.output());
                assertThrows(IllegalStateException.class, exitSession::ensureHealthy);
            } finally {
                exitSession.close();
                exitIsolation.close();
            }
            docker.assertNoTaskContainers();
        }
    }

    @Test
    void taskDeadlineAndApplicationRestartRemoveOrphansAndReverifyEvidence() throws Exception {
        try (RealDocker docker = RealDocker.require(root)) {
            Path repository = Files.createDirectories(root.resolve("restart-repository"));
            WorkspaceProvisioner.WorkspaceLease deadlineWorkspace = workspace(
                    "change_bbbbbbbbbbbb", "deadline", repository);
            MutableClock clock = new MutableClock(Instant.parse("2026-09-06T00:00:00Z"));
            DockerWorkerIsolation deadlineIsolation = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32, clock);
            WorkerIsolation.Session deadlineSession = deadlineIsolation.open(
                    task(deadlineWorkspace), deadlineWorkspace);
            clock.advance(Duration.ofSeconds(61));
            assertEquals(com.paicli.tool.CommandExecutionResult.Status.TIMED_OUT,
                    deadlineSession.executeCommand("true", Duration.ofSeconds(1)).status());
            docker.assertNoTaskContainers();
            deadlineSession.close();
            deadlineIsolation.close();

            WorkspaceProvisioner.WorkspaceLease orphanWorkspace = workspace(
                    "change_cccccccccccc", "orphan", repository);
            DockerWorkerIsolation previousProcess = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32);
            WorkerIsolation.Session orphan = previousProcess.open(task(orphanWorkspace), orphanWorkspace);
            assertFalse(docker.taskContainer(orphanWorkspace.changeId()).isBlank());
            DockerWorkerIsolation restartedProcess = docker.isolation(
                    Map.of(), EphemeralSecretProvider.none(), 128, 32);
            restartedProcess.recoverOrphans();
            docker.assertNoTaskContainers();
            assertThrows(IllegalStateException.class, orphan::ensureHealthy);
            orphan.close();
            previousProcess.close();
            restartedProcess.close();

            Path database = root.resolve("restart-control/changes.db");
            Files.createDirectories(database.getParent());
            ChangeTask evidenceTask = task(workspace(
                    "change_ddddddddddde", "evidence", repository));
            try (SqliteChangeStore changes = new SqliteChangeStore(database)) {
                changes.create(evidenceTask, new ChangeEvent(0, evidenceTask.id(), "change.created",
                        "HUMAN", "acceptance", null, ChangeState.READY, "{}", evidenceTask.createdAt()));
            }
            Path validSource = evidenceSource("valid-run");
            Path tamperedSource = evidenceSource("tampered-run");
            Path validArchive;
            Path tamperedArchive;
            try (TrustedEvidenceStore evidence = new TrustedEvidenceStore(
                    database, root.resolve("restart-control/evidence-archive"))) {
                validArchive = evidence.capture(evidenceTask.id(), "valid-run", validSource).path();
                tamperedArchive = evidence.capture(evidenceTask.id(), "tampered-run", tamperedSource).path();
            }
            try (TrustedEvidenceStore restartedEvidence = new TrustedEvidenceStore(
                    database, root.resolve("restart-control/evidence-archive"))) {
                restartedEvidence.verify(evidenceTask.id(), "valid-run", validArchive);
                makeWorldWritable(tamperedArchive.resolve("change.diff"));
                Files.writeString(tamperedArchive.resolve("change.diff"), "tampered-after-restart\n");
                assertThrows(ChangeConflictException.class,
                        () -> restartedEvidence.verify(evidenceTask.id(), "tampered-run", tamperedArchive));
            }
        }
    }

    @Test
    void realDockerKeepsControlPlaneOutsideMountAndRemovesProcessTree() throws Exception {
        String image = System.getProperty("paichange.docker.integration.image", "").trim();
        Assumptions.assumeTrue(!image.isBlank(),
                "set -Dpaichange.docker.integration.image=<digest-pinned-local-image> to run real Docker acceptance");
        DockerWorkerIsolation.ProcessDockerCommandRunner runner = new DockerWorkerIsolation.ProcessDockerCommandRunner();
        DockerWorkerIsolation.DockerResult available = runner.run(
                List.of("docker", "version", "--format", "{{.Server.Version}}"), null, Duration.ofSeconds(5));
        Assumptions.assumeTrue(available.exitCode() == 0, "Docker Engine is not available");

        Path repository = Files.createDirectories(root.resolve("repository"));
        Path workspaceRoot = Files.createDirectories(root.resolve("workspace"));
        Path evidence = Files.createDirectories(root.resolve("worker-evidence"));
        Path control = Files.createDirectories(root.resolve("control-plane"));
        Files.writeString(workspaceRoot.resolve("visible.txt"), "visible");
        Files.writeString(control.resolve("database-secret.txt"), "must-not-mount");
        ChangeTaskId id = new ChangeTaskId("change_eeeeeeeeeeee");
        WorkspaceProvisioner.WorkspaceLease workspace = new WorkspaceProvisioner.WorkspaceLease(
                id, "workspace-e", repository, workspaceRoot, evidence, "paichange/e", "base");
        ChangeTask task = new ChangeTask(id, "docker-integration", 0, ChangeState.READY,
                new WorkItemRef("mock", "docker", ""), new RepositoryRef(repository.toString(), "main"),
                "docker", "docker", "", "", null, null, Instant.now(), Instant.now());
        DockerWorkerIsolation.Config config = new DockerWorkerIsolation.Config(true, "docker", image,
                0.25, 256, 32, 60, "65532:65532", "integrationowner12345", Map.of(), false);
        DockerWorkerIsolation isolation = new DockerWorkerIsolation(config, runner,
                EphemeralSecretProvider.none(), Clock.systemUTC());

        WorkerIsolation.Session session = null;
        try {
            session = isolation.open(task, workspace);
            DockerWorkerIsolation.DockerResult container = runner.run(List.of("docker", "ps", "-q", "--filter",
                    "label=com.paicli.paichange.change=" + id.value()), null, Duration.ofSeconds(10));
            assertEquals(0, container.exitCode(), container.output());
            String containerId = container.output().trim();
            assertFalse(containerId.isBlank());
            DockerWorkerIsolation.DockerResult limits = runner.run(List.of("docker", "inspect", "--format",
                    "{{.HostConfig.NetworkMode}}|{{.HostConfig.ReadonlyRootfs}}|{{.HostConfig.PidsLimit}}|"
                            + "{{.HostConfig.Memory}}|{{.HostConfig.NanoCpus}}|{{.Config.User}}|{{len .Mounts}}|"
                            + "{{(index .Mounts 0).RW}}|{{(index .Mounts 0).Destination}}",
                    containerId), null, Duration.ofSeconds(10));
            assertEquals("none|true|32|268435456|250000000|65532:65532|1|true|/workspace",
                    limits.output().trim());
            var result = session.executeCommand(
                    "test -f visible.txt && test ! -e /var/run/docker.sock && test ! -e /control-plane && printf ok",
                    Duration.ofSeconds(10));
            assertEquals(0, result.exitCode(), result.output());
            assertEquals(com.paicli.tool.CommandExecutionResult.Status.TIMED_OUT,
                    session.executeCommand("sleep 300", Duration.ofMillis(500)).status());

            DockerWorkerIsolation.DockerResult remaining = runner.run(List.of("docker", "ps", "-aq", "--filter",
                    "label=com.paicli.paichange.change=" + id.value()), null, Duration.ofSeconds(10));
            assertEquals("", remaining.output().trim(), "task container/process tree must be removed");
        } finally {
            if (session != null) session.close();
            isolation.close();
        }
    }

    private WorkspaceProvisioner.WorkspaceLease workspace(String id, String marker, Path repository) throws Exception {
        Path work = Files.createDirectories(root.resolve("workspace-" + marker));
        Path evidence = Files.createDirectories(root.resolve("worker-evidence-" + marker));
        makeWorldWritable(work);
        Files.writeString(work.resolve("owner.txt"), marker);
        makeWorldWritable(work.resolve("owner.txt"));
        ChangeTaskId changeId = new ChangeTaskId(id);
        return new WorkspaceProvisioner.WorkspaceLease(changeId, "workspace-" + marker, repository, work,
                evidence, "paichange/" + marker, "base");
    }

    private ChangeTask task(WorkspaceProvisioner.WorkspaceLease workspace) {
        Instant now = Instant.now();
        return new ChangeTask(workspace.changeId(), "docker-" + workspace.workspaceId(), 0, ChangeState.READY,
                new WorkItemRef("mock", workspace.workspaceId(), ""),
                new RepositoryRef(workspace.repositoryRoot().toString(), "main"),
                "docker", "docker", "", "", null, null, now, now);
    }

    private static String absentPaths(Path... paths) {
        return java.util.Arrays.stream(paths)
                .map(path -> "test ! -e " + shellQuote(path.toAbsolutePath().normalize().toString()))
                .collect(java.util.stream.Collectors.joining(" && "));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void makeWorldWritable(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path)
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Docker Desktop test host may use a non-POSIX temporary filesystem.
        }
    }

    private static void awaitFile(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (!Files.exists(path) && Instant.now().isBefore(deadline)) {
            Thread.sleep(25);
        }
        assertTrue(Files.exists(path), "timed out waiting for " + path.getFileName());
    }

    private Path evidenceSource(String runId) throws Exception {
        Path source = Files.createDirectories(root.resolve("source-evidence-" + runId));
        Files.writeString(source.resolve("result.json"), "{\"runId\":\"" + runId + "\"}");
        Files.writeString(source.resolve("change.diff"), "diff\n");
        return source;
    }

    private static final class RealDocker implements AutoCloseable {
        private final Path root;
        private final String image;
        private final String owner;
        private final DockerWorkerIsolation.ProcessDockerCommandRunner runner =
                new DockerWorkerIsolation.ProcessDockerCommandRunner();
        private final List<String> helperContainers = new ArrayList<>();
        private final List<String> networks = new ArrayList<>();
        private final Set<DockerWorkerIsolation> isolations = new HashSet<>();

        private RealDocker(Path root, String image) {
            this.root = root;
            this.image = image;
            this.owner = "it" + UUID.randomUUID().toString().replace("-", "");
        }

        static RealDocker require(Path root) throws Exception {
            String image = System.getProperty("paichange.docker.integration.image", "").trim();
            Assumptions.assumeTrue(!image.isBlank(),
                    "set -Dpaichange.docker.integration.image=<digest-pinned-local-image> to run real Docker acceptance");
            RealDocker docker = new RealDocker(root, image);
            DockerWorkerIsolation.DockerResult available = docker.run(List.of(
                    "docker", "version", "--format", "{{.Server.Version}}"), Duration.ofSeconds(5));
            Assumptions.assumeTrue(available.exitCode() == 0, "Docker Engine is not available");
            DockerWorkerIsolation.DockerResult local = docker.run(List.of(
                    "docker", "image", "inspect", image), Duration.ofSeconds(5));
            Assumptions.assumeTrue(local.exitCode() == 0, "the digest-pinned image is not available locally");
            return docker;
        }

        DockerWorkerIsolation isolation(Map<String, DockerWorkerIsolation.Egress> egress,
                                         EphemeralSecretProvider secrets, int memoryMb, int pidsLimit) {
            return isolation(egress, secrets, memoryMb, pidsLimit, Clock.systemUTC());
        }

        DockerWorkerIsolation isolation(Map<String, DockerWorkerIsolation.Egress> egress,
                                         EphemeralSecretProvider secrets, int memoryMb, int pidsLimit,
                                         Clock clock) {
            DockerWorkerIsolation.Config config = new DockerWorkerIsolation.Config(true, "docker", image,
                    0.25, memoryMb, pidsLimit, 60, "65532:65532", owner, egress,
                    secrets != EphemeralSecretProvider.none());
            DockerWorkerIsolation isolation = new DockerWorkerIsolation(config, runner, secrets, clock);
            isolations.add(isolation);
            return isolation;
        }

        DockerWorkerIsolation.DockerResult run(List<String> command, Duration timeout) throws Exception {
            return runner.run(command, null, timeout);
        }

        String uniqueName(String kind) {
            return ("paichange-it-" + kind + "-" + owner.substring(2, 10)).toLowerCase();
        }

        Path startAuditedProxy(String network, String projectId, String policyDigest) throws Exception {
            createEgressNetwork(network, "true|" + projectId + "|" + policyDigest, true);
            Path proxyRoot = Files.createDirectories(root.resolve("proxy-" + owner));
            Path audit = Files.createDirectories(proxyRoot.resolve("audit"));
            makeWorldWritable(audit);
            Path script = proxyRoot.resolve("proxy.sh");
            Files.writeString(script, """
                    #!/bin/sh
                    IFS= read -r request
                    while IFS= read -r header; do
                      [ \"$header\" = \"$(printf '\\r')\" ] && break
                    done
                    case \"$request\" in
                      'GET http://allowed.example/'*)
                        printf '%s\\n' \"$request\" 'ALLOW allowed.example' >> /audit/requests.log
                        body='allowed-through-proxy'
                        printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 21\\r\\nConnection: close\\r\\n\\r\\n%s' \"$body\"
                        ;;
                      *)
                        printf '%s\\n' \"$request\" 'DENY blocked.example' >> /audit/requests.log
                        body='denied-by-proxy'
                        printf 'HTTP/1.1 403 Forbidden\\r\\nContent-Length: 15\\r\\nConnection: close\\r\\n\\r\\n%s' \"$body\"
                        ;;
                    esac
                    """);
            makeExecutable(script);
            String container = uniqueName("proxy");
            helperContainers.add(container);
            DockerWorkerIsolation.DockerResult created = run(List.of(
                    "docker", "create", "--name", container,
                    "--label", "com.paicli.paichange.acceptance=" + owner,
                    "--network", network, "--network-alias", "egress-proxy",
                    "--pull=never", "--init", "--read-only", "--cap-drop=ALL",
                    "--security-opt=no-new-privileges:true", "--user", "65532:65532",
                    "--mount", "type=bind,src=" + script + ",dst=/proxy.sh,readonly",
                    "--mount", "type=bind,src=" + audit + ",dst=/audit",
                    image, "busybox", "nc", "-lk", "-p", "3128", "-e", "/proxy.sh"),
                    Duration.ofSeconds(10));
            assertEquals(0, created.exitCode(), created.output());
            DockerWorkerIsolation.DockerResult started = run(
                    List.of("docker", "start", container), Duration.ofSeconds(10));
            assertEquals(0, started.exitCode(), started.output());
            return audit;
        }

        String createEgressNetwork(String kind, String labels) throws Exception {
            return createEgressNetwork(uniqueName(kind), labels, false);
        }

        private String createEgressNetwork(String name, String labels, boolean exactName) throws Exception {
            String[] values = labels.split("\\|", -1);
            if (values.length != 3) throw new IllegalArgumentException("three network label values required");
            if (!exactName) name = uniqueName(name);
            networks.add(name);
            DockerWorkerIsolation.DockerResult result = run(List.of(
                    "docker", "network", "create", "--internal",
                    "--label", DockerWorkerIsolation.EGRESS_LABEL + "=" + values[0],
                    "--label", DockerWorkerIsolation.EGRESS_PROJECT_LABEL + "=" + values[1],
                    "--label", DockerWorkerIsolation.EGRESS_POLICY_LABEL + "=" + values[2],
                    "--label", "com.paicli.paichange.acceptance=" + owner,
                    name), Duration.ofSeconds(10));
            assertEquals(0, result.exitCode(), result.output());
            return name;
        }

        void assertNoTaskContainers() throws Exception {
            DockerWorkerIsolation.DockerResult remaining = run(List.of("docker", "ps", "-aq", "--filter",
                    "label=" + DockerWorkerIsolation.OWNER_LABEL + "=" + owner), Duration.ofSeconds(10));
            assertEquals(0, remaining.exitCode(), remaining.output());
            assertEquals("", remaining.output().trim(), "test task containers must be removed");
        }

        String taskContainer(ChangeTaskId id) throws Exception {
            DockerWorkerIsolation.DockerResult result = run(List.of("docker", "ps", "-q", "--filter",
                    "label=com.paicli.paichange.change=" + id.value(), "--filter",
                    "label=" + DockerWorkerIsolation.OWNER_LABEL + "=" + owner), Duration.ofSeconds(10));
            assertEquals(0, result.exitCode(), result.output());
            assertFalse(result.output().trim().isBlank(), "task container must be running");
            return result.output().trim();
        }

        int cgroupEvent(String container, String file, String key) throws Exception {
            DockerWorkerIsolation.DockerResult result = run(List.of("docker", "exec", container, "sh", "-c",
                    "while read name value; do [ \"$name\" = " + shellQuote(key)
                            + " ] && { printf '%s' \"$value\"; exit; }; done < /sys/fs/cgroup/" + file),
                    Duration.ofSeconds(5));
            assertEquals(0, result.exitCode(), result.output());
            return Integer.parseInt(result.output().trim());
        }

        @Override
        public void close() throws Exception {
            isolations.forEach(DockerWorkerIsolation::close);
            cleanupContainersByOwner();
            for (String container : helperContainers) run(List.of("docker", "rm", "-f", container), Duration.ofSeconds(10));
            for (String network : networks) run(List.of("docker", "network", "rm", network), Duration.ofSeconds(10));
            assertNoTaskContainers();
            DockerWorkerIsolation.DockerResult helpers = run(List.of("docker", "ps", "-aq", "--filter",
                    "label=com.paicli.paichange.acceptance=" + owner), Duration.ofSeconds(10));
            assertEquals(0, helpers.exitCode(), helpers.output());
            assertEquals("", helpers.output().trim(), "test proxy containers must be removed");
            DockerWorkerIsolation.DockerResult remainingNetworks = run(List.of("docker", "network", "ls", "-q",
                    "--filter", "label=com.paicli.paichange.acceptance=" + owner), Duration.ofSeconds(10));
            assertEquals(0, remainingNetworks.exitCode(), remainingNetworks.output());
            assertEquals("", remainingNetworks.output().trim(), "test egress networks must be removed");
        }

        private void cleanupContainersByOwner() throws Exception {
            DockerWorkerIsolation.DockerResult listed = run(List.of("docker", "ps", "-aq", "--filter",
                    "label=" + DockerWorkerIsolation.OWNER_LABEL + "=" + owner), Duration.ofSeconds(10));
            if (listed.exitCode() != 0) return;
            for (String id : listed.output().split("\\R")) {
                if (!id.isBlank()) run(List.of("docker", "rm", "-f", id.trim()), Duration.ofSeconds(10));
            }
        }
    }

    private static void makeExecutable(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Docker Desktop test host may use a non-POSIX temporary filesystem.
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
