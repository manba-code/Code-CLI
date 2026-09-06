package com.paicli.change;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;
import com.paicli.spec.SpecRunResult;
import com.paicli.tool.CommandExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApprovalCoordinatorTest {
    @TempDir Path root;
    private ToolApprovalCoordinator coordinator;

    @AfterEach void close() { if (coordinator != null) coordinator.close(); }

    @Test
    void exactApprovalUnblocksCallAndRedactsSecrets() throws Exception {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.RESTRICTED, Duration.ofSeconds(2), 1);
        PersistentToolApprovalHandler handler = fixture.handler();
        CompletableFuture<ApprovalResult> result = CompletableFuture.supplyAsync(() -> handler.requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"out.txt\",\"content\":\"ok\",\"api_key\":\"secret-value\"}", "test")));
        ToolApproval pending = awaitPending(fixture.governance());

        assertFalse(pending.argumentsPreview().contains("secret-value"));
        assertTrue(pending.argumentsPreview().contains("***"));
        ToolApproval approved = coordinator.decide(fixture.task().id(), pending.id(), ToolApproval.Status.APPROVED,
                pending.policyVersion(), pending.argumentsDigest(), pending.callId(), pending.runId(), pending.specDigest(),
                "reviewed", fixture.approver());

        assertEquals(ToolApproval.Status.APPROVED, approved.status());
        assertTrue(result.get(2, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void policyOrPermissionChangeInvalidatesOldApproval() throws Exception {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.RESTRICTED, Duration.ofMillis(250), 1);
        CompletableFuture<ApprovalResult> result = CompletableFuture.supplyAsync(() -> fixture.handler().requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"out.txt\",\"content\":\"ok\"}", "test")));
        ToolApproval pending = awaitPending(fixture.governance());
        fixture.memberships().put(new ProjectMembership(pending.projectId(), "admin", Set.of(ProjectRole.PROJECT_ADMIN)));
        coordinator.updatePolicy(pending.projectId(), 1, List.of(),
                new Principal("admin", "Admin", PrincipalType.HUMAN, "test", null, false));

        assertThrows(ChangeConflictException.class, () -> coordinator.decide(fixture.task().id(), pending.id(),
                ToolApproval.Status.APPROVED, pending.policyVersion(), pending.argumentsDigest(), pending.callId(),
                pending.runId(), pending.specDigest(), "stale", fixture.approver()));
        assertTrue(result.get(2, TimeUnit.SECONDS).isRejected());
        assertEquals(ToolApproval.Status.STALE, fixture.governance().findApproval(pending.id()).orElseThrow().status());
    }

    @Test
    void unauthorizedOrServicePrincipalCannotApprove() throws Exception {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.RESTRICTED, Duration.ofSeconds(2), 1);
        CompletableFuture<ApprovalResult> result = CompletableFuture.supplyAsync(() -> fixture.handler().requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"out.txt\",\"content\":\"ok\"}", "test")));
        ToolApproval pending = awaitPending(fixture.governance());
        Principal outsider = new Principal("outsider", "Outsider", PrincipalType.HUMAN, "test", null, false);
        assertThrows(ChangeForbiddenException.class, () -> coordinator.decide(fixture.task().id(), pending.id(),
                ToolApproval.Status.APPROVED, pending.policyVersion(), pending.argumentsDigest(), pending.callId(),
                pending.runId(), pending.specDigest(), "no", outsider));
        Principal service = new Principal("service", "Service", PrincipalType.SERVICE, "test", null, true);
        assertThrows(ChangeForbiddenException.class, () -> coordinator.decide(fixture.task().id(), pending.id(),
                ToolApproval.Status.APPROVED, pending.policyVersion(), pending.argumentsDigest(), pending.callId(),
                pending.runId(), pending.specDigest(), "no", service));
        coordinator.decide(fixture.task().id(), pending.id(), ToolApproval.Status.REJECTED,
                pending.policyVersion(), pending.argumentsDigest(), pending.callId(), pending.runId(), pending.specDigest(),
                "rejected", fixture.approver());
        assertTrue(result.get(2, TimeUnit.SECONDS).isRejected());
    }

    @Test
    void boundedWaitLeavesCapacityForOtherCallsAndCloseDoesNotExecute() throws Exception {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.RESTRICTED, Duration.ofSeconds(5), 1);
        CompletableFuture<ApprovalResult> first = CompletableFuture.supplyAsync(() -> fixture.handler().requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"one\",\"content\":\"x\"}", "test")));
        awaitPending(fixture.governance());
        PersistentToolApprovalHandler secondHandler = coordinator.handler(fixture.task(), root);
        bind(secondHandler, fixture.task());
        ApprovalResult second = secondHandler.requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"two\",\"content\":\"x\"}", "test"));
        assertTrue(second.isRejected());
        assertTrue(second.reason().contains("容量"));

        coordinator.close();
        assertTrue(first.get(2, TimeUnit.SECONDS).isRejected());
        coordinator = null;
    }

    @Test
    void timeoutAndProfileDenyFailClosedWithoutExecuting() {
        Fixture timeoutFixture = fixture(ExecutionRoute.ToolPolicyProfile.RESTRICTED, Duration.ofMillis(25), 1);
        ApprovalResult timedOut = timeoutFixture.handler().requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"never.txt\",\"content\":\"x\"}", "test"));
        assertTrue(timedOut.isRejected());
        assertEquals(ToolApproval.Status.TIMED_OUT,
                timeoutFixture.governance().approvals(timeoutFixture.task().id()).get(0).status());
        assertFalse(Files.exists(root.resolve("never.txt")));

        ApprovalResult denied = timeoutFixture.handler().requestApproval(
                ApprovalRequest.of("web_fetch", "{\"url\":\"https://example.test\"}", "test"));
        assertTrue(denied.isRejected());
        assertEquals(1, timeoutFixture.governance().approvals(timeoutFixture.task().id()).size());
    }

    @Test
    void commandGuardCannotBeBypassedAndDenialIsPersisted() throws Exception {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.STANDARD, Duration.ofSeconds(2), 1);
        GovernedToolRegistry registry = new GovernedToolRegistry(coordinator, fixture.task(), root);
        registry.setProjectPath(root.toString());
        bind(registry.approvalHandler(), fixture.task());

        CommandExecutionResult result = registry.executeCommandForVerification("sudo echo unsafe");

        assertEquals(CommandExecutionResult.Status.POLICY_DENIED, result.status());
        assertTrue(result.reason().contains("CommandGuard"));
        assertEquals(ToolApproval.Status.REJECTED,
                fixture.governance().approvals(fixture.task().id()).get(0).status());
    }

    @Test
    void isolatedRegistryRoutesCommandsToContainerAndAppliesSecondNetworkGate() {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.STANDARD, Duration.ofSeconds(2), 1);
        RecordingIsolation isolation = new RecordingIsolation();
        GovernedToolRegistry registry = new GovernedToolRegistry(coordinator, fixture.task(), root, isolation);
        registry.setProjectPath(root.toString());
        bind(registry.approvalHandler(), fixture.task());

        CommandExecutionResult verifier = registry.executeCommandForVerification("printf verified");
        String tool = registry.executeTool("execute_command", "{\"command\":\"printf tool\"}");
        String network = registry.executeTool("web_fetch", "{\"url\":\"https://example.test\"}");

        assertEquals(CommandExecutionResult.Status.COMPLETED, verifier.status());
        assertEquals(List.of("printf verified", "printf tool"), isolation.commands);
        assertEquals(2, isolation.healthChecks);
        assertTrue(tool.contains("container"));
        assertTrue(network.contains("M6a 默认拒绝"));
    }

    @Test
    void pathGuardStillRejectsAnApprovedExactCall() throws Exception {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.RESTRICTED, Duration.ofSeconds(2), 1);
        GovernedToolRegistry registry = new GovernedToolRegistry(coordinator, fixture.task(), root);
        registry.setProjectPath(root.toString());
        bind(registry.approvalHandler(), fixture.task());
        Path outside = root.resolveSibling("outside-" + System.nanoTime() + ".txt");
        CompletableFuture<String> execution = CompletableFuture.supplyAsync(() -> registry.executeTool(
                "write_file", ChangeJson.MAPPER.createObjectNode()
                        .put("path", outside.toString()).put("content", "must-not-write").toString()));
        ToolApproval pending = awaitPending(fixture.governance());

        coordinator.decide(fixture.task().id(), pending.id(), ToolApproval.Status.APPROVED,
                pending.policyVersion(), pending.argumentsDigest(), pending.callId(), pending.runId(),
                pending.specDigest(), "reviewed", fixture.approver());

        assertTrue(execution.get(2, TimeUnit.SECONDS).contains("策略拒绝"));
        assertFalse(Files.exists(outside));
    }

    @Test
    void startupRecoveryInterruptsPendingWithoutAWaiter() {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.RESTRICTED, Duration.ofSeconds(2), 1);
        Instant now = Instant.now();
        ToolApproval pending = new ToolApproval("tool_approval_crash", fixture.task().id(),
                ChangeProject.id(fixture.task().repository()), "run-crash", "call-crash", "write_file",
                "digest", "{\"path\":\"x\"}", root.toString(), fixture.task().spec().specId(),
                fixture.task().spec().revision(), fixture.task().spec().digest(), fixture.task().route().toolPolicy(),
                1, "profile-default", ToolApproval.Status.PENDING, "", "", "", false,
                now, null, now.plusSeconds(30));
        fixture.governance().createApproval(pending);

        coordinator.recoverPending();

        assertEquals(ToolApproval.Status.INTERRUPTED,
                fixture.governance().findApproval(pending.id()).orElseThrow().status());
        assertTrue(coordinator.hadInterruptedApproval(fixture.task().id()));

        RecordingExecutionControl control = new RecordingExecutionControl();
        ChangeWorkerJobHandler jobHandler = new ChangeWorkerJobHandler(control,
                ignored -> { throw new AssertionError("interrupted execution must not run"); }, coordinator);
        jobHandler.recovered(new com.paicli.runtime.task.WorkerJob("job-1", ChangeWorkerJobHandler.JOB_TYPE,
                fixture.task().id().value(), com.paicli.runtime.task.WorkerJobStatus.ENQUEUED, 1));
        assertEquals(1, control.canceled);
        assertEquals(0, control.recovered);
    }

    @Test
    void isolatedStartupNeverReplaysAWorkerWhoseResultIsUnknown() {
        Fixture fixture = fixture(ExecutionRoute.ToolPolicyProfile.STANDARD, Duration.ofSeconds(2), 1);
        RecordingExecutionControl control = new RecordingExecutionControl();
        ChangeWorkerJobHandler jobHandler = new ChangeWorkerJobHandler(control,
                ignored -> { throw new AssertionError("recovered isolated execution must not run"); },
                coordinator, true);

        jobHandler.recovered(new com.paicli.runtime.task.WorkerJob("job-isolated", ChangeWorkerJobHandler.JOB_TYPE,
                fixture.task().id().value(), com.paicli.runtime.task.WorkerJobStatus.ENQUEUED, 1));

        assertEquals(1, control.canceled);
        assertEquals(0, control.recovered);
    }

    private Fixture fixture(ExecutionRoute.ToolPolicyProfile profile, Duration timeout, int maxWaiting) {
        InMemoryChangeStore tasks = new InMemoryChangeStore();
        InMemoryToolGovernanceStore governance = new InMemoryToolGovernanceStore();
        InMemoryProjectMemberships memberships = new InMemoryProjectMemberships();
        ChangeAuthorizer authorizer = new ChangeAuthorizer(memberships);
        ChangeTask task = runningTask(profile);
        tasks.create(task, new ChangeEvent(0, task.id(), "execution.started", "WORKER", "claim",
                ChangeState.QUEUED, ChangeState.RUNNING, "{}", Instant.now()));
        String project = ChangeProject.id(task.repository());
        memberships.put(new ProjectMembership(project, "approver", Set.of(ProjectRole.APPROVER)));
        coordinator = new ToolApprovalCoordinator(governance, tasks, authorizer, Clock.systemUTC(), timeout, maxWaiting);
        PersistentToolApprovalHandler handler = coordinator.handler(task, root);
        bind(handler, task);
        return new Fixture(task, governance, memberships, handler,
                new Principal("approver", "Approver", PrincipalType.HUMAN, "test", null, false));
    }

    private ChangeTask runningTask(ExecutionRoute.ToolPolicyProfile profile) {
        Instant now = Instant.now();
        ChangeTaskId id = new ChangeTaskId("change_abcdef123456");
        SpecRef spec = new SpecRef("SPEC-1", 1, "spec-digest", root.resolve("draft.md"), root.resolve("locked.md"));
        RiskLevel level = profile == ExecutionRoute.ToolPolicyProfile.STANDARD ? RiskLevel.LOW
                : profile == ExecutionRoute.ToolPolicyProfile.RESTRICTED ? RiskLevel.MEDIUM : RiskLevel.HIGH;
        ExecutionRoute route = new ExecutionRoute(level, "test", "test", ExecutionRoute.ExecutionMode.REACT,
                true, true, level == RiskLevel.HIGH, profile);
        return new ChangeTask(id, "key-" + profile, 1, ChangeState.RUNNING, new WorkItemRef("mock", "1", ""),
                new RepositoryRef(root.toString(), "main"), "title", "requirement", "requester", "", "",
                spec, new RiskAssessment(level, 1, List.of("test")), route,
                new ApprovalRecord("approval", ApprovalRecord.Stage.SPEC, ApprovalRecord.Decision.APPROVED,
                        "lead", "ok", spec.digest(), "", now), null,
                new WorkerClaimRef("claim", now), null, null, null, now, now);
    }

    private static void bind(PersistentToolApprovalHandler handler, ChangeTask task) {
        handler.bindRunIdentity(new SpecRunResult.RunIdentity("run-1", task.spec().specId(), task.spec().revision(),
                task.spec().digest(), task.spec().lockedPath()));
    }

    private static ToolApproval awaitPending(InMemoryToolGovernanceStore store) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<ToolApproval> pending = store.pendingApprovals();
            if (!pending.isEmpty()) return pending.get(pending.size() - 1);
            Thread.sleep(10);
        }
        throw new AssertionError("pending approval not created");
    }

    private record Fixture(ChangeTask task, InMemoryToolGovernanceStore governance,
                           InMemoryProjectMemberships memberships, PersistentToolApprovalHandler handler,
                           Principal approver) { }

    private static final class RecordingExecutionControl implements ChangeExecutionControl {
        int recovered;
        int canceled;
        @Override public ChangeTask queueForExecution(ChangeTaskId id, long version) { throw new UnsupportedOperationException(); }
        @Override public ExecutionLease claimExecution(ChangeTaskId id) { throw new UnsupportedOperationException(); }
        @Override public void recoverExecution(ChangeTaskId id, String reason) { recovered++; }
        @Override public void cancelExecution(ChangeTaskId id, String reason) { canceled++; }
    }

    private static final class RecordingIsolation implements WorkerIsolation.Session {
        private final java.util.ArrayList<String> commands = new java.util.ArrayList<>();
        private int healthChecks;
        @Override public boolean isolated() { return true; }
        @Override public Duration taskTimeout() { return Duration.ofSeconds(60); }
        @Override public CommandExecutionResult executeCommand(String command, Duration timeout) {
            commands.add(command);
            return CommandExecutionResult.completed(command, 0, "container");
        }
        @Override public WorkerIsolation.NetworkDecision networkDecision(String toolName, String argumentsJson) {
            return toolName.startsWith("web_") ? WorkerIsolation.NetworkDecision.deny("M6a 默认拒绝网络")
                    : WorkerIsolation.NetworkDecision.allow();
        }
        @Override public void ensureHealthy() { healthChecks++; }
        @Override public void abort(String reason) { }
        @Override public void close() { }
    }
}
