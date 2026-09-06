package com.paicli.change;

import com.paicli.tool.CommandExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DockerWorkerIsolationTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    void createsLeastPrivilegeTaskContainerAndMountsOnlyCurrentWorkspace() throws Exception {
        FakeDocker docker = new FakeDocker();
        DockerWorkerIsolation isolation = isolation(docker, Map.of(), EphemeralSecretProvider.none());
        WorkspaceProvisioner.WorkspaceLease workspace = workspace("change_aaaaaaaaaaaa");

        WorkerIsolation.Session session = isolation.open(task(workspace.changeId()), workspace);
        CommandExecutionResult result = session.executeCommand("printf isolated", Duration.ofSeconds(5));
        session.close();

        assertEquals(CommandExecutionResult.Status.COMPLETED, result.status());
        assertTrue(docker.commands.stream().anyMatch(call -> call.command().containsAll(
                List.of("image", "inspect", image()))));
        List<String> create = docker.first("create").command();
        assertTrue(create.containsAll(List.of("--pull=never", "--init", "--read-only", "--cap-drop=ALL",
                "--security-opt=no-new-privileges:true", "--pids-limit=64", "--memory=512m",
                "--cpus=0.5", "--user", "1234:1234", "--network", "none")));
        assertTrue(create.contains("/run/paichange-secrets:rw,noexec,nosuid,nodev,uid=1234,gid=1234,mode=0700,size=1048576"));
        String joined = String.join(" ", create);
        assertTrue(joined.contains("src=" + workspace.workspaceRoot().toRealPath() + ",dst=/workspace"));
        assertFalse(joined.contains("dst=/workspace,rw"), "--mount only accepts key=value fields; rw is the default");
        assertFalse(joined.contains(workspace.repositoryRoot().toString()));
        assertFalse(joined.contains(workspace.evidenceRoot().toString()));
        assertTrue(docker.commands.stream().anyMatch(call -> call.command().contains("exec")
                && call.command().contains("printf isolated")));
        assertTrue(docker.commands.stream().anyMatch(call -> call.command().containsAll(List.of("rm", "-f"))));
    }

    @Test
    void parallelTasksReceiveDifferentContainersAndCannotMountEachOthersFiles() throws Exception {
        FakeDocker docker = new FakeDocker();
        DockerWorkerIsolation isolation = isolation(docker, Map.of(), EphemeralSecretProvider.none());
        WorkspaceProvisioner.WorkspaceLease first = workspace("change_111111111111");
        WorkspaceProvisioner.WorkspaceLease second = workspace("change_222222222222");

        WorkerIsolation.Session firstSession = isolation.open(task(first.changeId()), first);
        WorkerIsolation.Session secondSession = isolation.open(task(second.changeId()), second);
        try {
            List<List<String>> creates = docker.commands.stream().map(Call::command)
                    .filter(command -> command.contains("create")).toList();
            assertEquals(2, creates.size());
            assertNotEquals(creates.get(0).get(creates.get(0).indexOf("--name") + 1),
                    creates.get(1).get(creates.get(1).indexOf("--name") + 1));
            String firstCommand = String.join(" ", creates.get(0));
            String secondCommand = String.join(" ", creates.get(1));
            assertTrue(firstCommand.contains(first.workspaceRoot().toRealPath().toString()));
            assertFalse(firstCommand.contains(second.workspaceRoot().toRealPath().toString()));
            assertTrue(secondCommand.contains(second.workspaceRoot().toRealPath().toString()));
            assertFalse(secondCommand.contains(first.workspaceRoot().toRealPath().toString()));
        } finally {
            firstSession.close(); secondSession.close();
        }
    }

    @Test
    void defaultDenyBlocksHostNetworkToolsAndProjectProxyNeedsLabeledNetwork() throws Exception {
        WorkspaceProvisioner.WorkspaceLease workspace = workspace("change_bbbbbbbbbbbb");
        ChangeTask task = task(workspace.changeId());
        FakeDocker deniedDocker = new FakeDocker();
        WorkerIsolation.Session denied = isolation(deniedDocker, Map.of(), EphemeralSecretProvider.none()).open(task, workspace);
        assertFalse(denied.networkDecision("web_fetch", "{\"url\":\"https://repo.example/a\"}").allowed());
        denied.close();

        String project = ChangeProject.id(task.repository());
        DockerWorkerIsolation.Egress egress = new DockerWorkerIsolation.Egress(
                "paichange-egress-project", "http://egress-proxy:3128", Set.of("repo.example"),
                Set.of("web_fetch", "mcp__deps__resolve"));
        FakeDocker externalNetwork = new FakeDocker();
        externalNetwork.networkInspect = "false|true|" + project + "|" + egress.policyDigest() + "\n";
        assertThrows(IllegalStateException.class, () -> isolation(externalNetwork, Map.of(project, egress),
                EphemeralSecretProvider.none()).open(task, workspace));
        FakeDocker allowedDocker = new FakeDocker();
        allowedDocker.networkInspect = "true|true|" + project + "|" + egress.policyDigest() + "\n";
        WorkerIsolation.Session allowed = isolation(allowedDocker, Map.of(project, egress),
                EphemeralSecretProvider.none()).open(task, workspace);
        assertTrue(allowed.networkDecision("web_fetch", "{\"url\":\"https://repo.example/a\"}").allowed());
        assertFalse(allowed.networkDecision("web_fetch", "{\"url\":\"http://169.254.169.254/latest\"}").allowed());
        assertFalse(allowed.networkDecision("web_search", "{}").allowed());
        List<String> create = allowedDocker.first("create").command();
        assertTrue(create.containsAll(List.of("--network", "paichange-egress-project",
                "--env", "HTTP_PROXY=http://egress-proxy:3128",
                "http_proxy=http://egress-proxy:3128",
                "NO_PROXY=localhost,127.0.0.1,::1",
                "no_proxy=localhost,127.0.0.1,::1")));
        allowed.close();
    }

    @Test
    void commandTimeoutAndStartupRecoveryForceRemoveWholeContainer() throws Exception {
        FakeDocker docker = new FakeDocker();
        docker.execTimedOut = true;
        DockerWorkerIsolation isolation = isolation(docker, Map.of(), EphemeralSecretProvider.none());
        WorkspaceProvisioner.WorkspaceLease workspace = workspace("change_cccccccccccc");
        WorkerIsolation.Session session = isolation.open(task(workspace.changeId()), workspace);

        assertEquals(CommandExecutionResult.Status.TIMED_OUT,
                session.executeCommand("sleep 999", Duration.ofMillis(10)).status());
        assertTrue(docker.commands.stream().anyMatch(call -> call.command().containsAll(List.of("rm", "-f"))));

        FakeDocker recovery = new FakeDocker();
        recovery.psOutput = "old-one\nold-two\n";
        isolation(recovery, Map.of(), EphemeralSecretProvider.none()).recoverOrphans();
        assertTrue(recovery.commands.stream().anyMatch(call -> call.command().contains("old-one")));
        assertTrue(recovery.commands.stream().anyMatch(call -> call.command().contains("old-two")));
    }

    @Test
    void unexpectedContainerExitFailsBeforeControlPlaneAcceptsResult() throws Exception {
        FakeDocker docker = new FakeDocker();
        docker.inspectRunning = false;
        DockerWorkerIsolation isolation = isolation(docker, Map.of(), EphemeralSecretProvider.none());
        WorkspaceProvisioner.WorkspaceLease workspace = workspace("change_333333333333");
        WorkerIsolation.Session session = isolation.open(task(workspace.changeId()), workspace);

        assertThrows(IllegalStateException.class, session::ensureHealthy);
        session.close();
    }

    @Test
    void secretIsFileOnlyNeverAppearsInDockerArgumentsAndExpiryFailsClosed() throws Exception {
        FakeDocker docker = new FakeDocker();
        byte[] secret = "very-secret-value".getBytes(StandardCharsets.UTF_8);
        EphemeralSecretProvider provider = ignored -> new EphemeralSecretProvider.SecretLease(
                Map.of("PACKAGE_TOKEN_FILE", secret), NOW.plusSeconds(30));
        WorkspaceProvisioner.WorkspaceLease workspace = workspace("change_dddddddddddd");
        WorkerIsolation.Session session = isolation(docker, Map.of(), provider).open(task(workspace.changeId()), workspace);
        session.executeCommand("build", Duration.ofSeconds(1));
        session.close();
        assertTrue(docker.commands.stream().anyMatch(call -> java.util.Arrays.equals(call.stdin(), secret)));
        assertTrue(docker.commands.stream().flatMap(call -> call.command().stream())
                .noneMatch(argument -> argument.contains("very-secret-value")));
        assertTrue(docker.commands.stream().flatMap(call -> call.command().stream())
                .anyMatch(argument -> argument.equals("PACKAGE_TOKEN_FILE=/run/paichange-secrets/package_token_file")));

        EphemeralSecretProvider expired = ignored -> new EphemeralSecretProvider.SecretLease(
                Map.of("PACKAGE_TOKEN_FILE", "expired".getBytes(StandardCharsets.UTF_8)), NOW);
        assertThrows(IllegalStateException.class,
                () -> isolation(new FakeDocker(), Map.of(), expired).open(task(workspace.changeId()), workspace));
    }

    @Test
    void rejectsMutableImageRootUserAndUnsafeNetworkConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> config("worker:latest", "1234:1234", Map.of()).validate());
        assertThrows(IllegalArgumentException.class, () -> config(image(), "0:0", Map.of()).validate());
        var unsafe = new DockerWorkerIsolation.Egress("bridge", "http://proxy:3128", Set.of("repo.example"), Set.of("web_fetch"));
        FakeDocker docker = new FakeDocker();
        assertThrows(IllegalArgumentException.class, () -> new DockerWorkerIsolation(
                config(image(), "1234:1234", Map.of("p", unsafe)), docker,
                EphemeralSecretProvider.none(), Clock.fixed(NOW, ZoneOffset.UTC)));
        var privateHost = new DockerWorkerIsolation.Egress("project-net", "http://proxy:3128",
                Set.of("10.0.0.1"), Set.of("web_fetch"));
        assertThrows(IllegalArgumentException.class, () -> privateHost.validate("project_test"));
        var proxyPath = new DockerWorkerIsolation.Egress("project-net", "http://proxy:3128/admin",
                Set.of("repo.example"), Set.of("web_fetch"));
        assertThrows(IllegalArgumentException.class, () -> proxyPath.validate("project_test"));
    }

    @Test
    void missingLocalImageFailsClosedBeforeContainerCreation() throws Exception {
        FakeDocker docker = new FakeDocker();
        docker.imagePresent = false;
        DockerWorkerIsolation isolation = isolation(docker, Map.of(), EphemeralSecretProvider.none());
        WorkspaceProvisioner.WorkspaceLease workspace = workspace("change_eeeeeeeeeeee");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> isolation.open(task(workspace.changeId()), workspace));

        assertTrue(failure.getMessage().contains("不会自动 pull 或 build"));
        assertTrue(docker.commands.stream().noneMatch(call -> call.command().contains("create")));
    }

    private DockerWorkerIsolation isolation(FakeDocker docker, Map<String, DockerWorkerIsolation.Egress> egress,
                                             EphemeralSecretProvider secrets) {
        return new DockerWorkerIsolation(config(image(), "1234:1234", egress), docker, secrets,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DockerWorkerIsolation.Config config(String image, String user, Map<String, DockerWorkerIsolation.Egress> egress) {
        return new DockerWorkerIsolation.Config(true, "docker", image, 0.5, 512, 64, 60,
                user, "0123456789abcdef0123", egress, !egress.isEmpty());
    }

    private WorkspaceProvisioner.WorkspaceLease workspace(String id) throws Exception {
        Path repository = Files.createDirectories(root.resolve("repository-" + id));
        Path work = Files.createDirectories(root.resolve("workspace-" + id));
        Path evidence = Files.createDirectories(root.resolve("staging-" + id));
        return new WorkspaceProvisioner.WorkspaceLease(new ChangeTaskId(id), id, repository, work, evidence,
                "paichange/" + id, "base");
    }

    private ChangeTask task(ChangeTaskId id) {
        Instant now = NOW;
        return new ChangeTask(id, "key-" + id.value(), 0, ChangeState.READY,
                new WorkItemRef("mock", id.value(), ""), new RepositoryRef(root.resolve("source").toString(), "main"),
                "test", "test", "", "", null, null, now, now);
    }

    private static String image() { return "registry.example/paichange@sha256:" + "a".repeat(64); }

    private static final class FakeDocker implements DockerWorkerIsolation.DockerCommandRunner {
        private final List<Call> commands = new ArrayList<>();
        private String networkInspect = "";
        private String psOutput = "";
        private boolean execTimedOut;
        private boolean inspectRunning = true;
        private boolean imagePresent = true;

        @Override
        public DockerWorkerIsolation.DockerResult run(List<String> command, byte[] stdin, Duration timeout) {
            commands.add(new Call(List.copyOf(command), stdin == null ? null : stdin.clone()));
            if (command.contains("version")) return ok("26.1\n");
            if (command.containsAll(List.of("image", "inspect"))) {
                return imagePresent ? ok("{}\n") : new DockerWorkerIsolation.DockerResult(1, "not found", false, false);
            }
            if (command.contains("network")) return ok(networkInspect);
            if (command.contains("inspect")) return ok(inspectRunning ? "true\n" : "false\n");
            if (command.contains("ps")) return ok(psOutput);
            if (command.contains("create")) return ok("container-id\n");
            if (command.contains("exec") && execTimedOut) return new DockerWorkerIsolation.DockerResult(-1, "", true, false);
            if (command.contains("exec")) return ok("isolated\n");
            return ok("");
        }

        private Call first(String value) { return commands.stream().filter(call -> call.command().contains(value)).findFirst().orElseThrow(); }
        private static DockerWorkerIsolation.DockerResult ok(String output) {
            return new DockerWorkerIsolation.DockerResult(0, output, false, false);
        }
    }

    private record Call(List<String> command, byte[] stdin) { }
}
