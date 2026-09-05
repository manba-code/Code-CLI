package com.paicli.change;

import com.paicli.runtime.task.WorkerJobCanceledException;
import com.paicli.spec.ChangeSpecModule;
import com.paicli.spec.SpecExecutionEngine;
import com.paicli.spec.SpecRunResult;

import java.nio.file.Files;
import java.time.Clock;
import java.util.Objects;

/** ChangeTask Worker：领取、隔离执行、封存代码身份并把结果交回 ChangeWorkflow。 */
public final class DefaultChangeWorker implements ChangeWorker {
    private final ChangeExecutionControl executionControl;
    private final WorkspaceProvisioner workspaces;
    private final ChangeWorkerRuntimeFactory runtimes;
    private final Clock clock;

    public DefaultChangeWorker(
            ChangeExecutionControl executionControl,
            WorkspaceProvisioner workspaces,
            ChangeWorkerRuntimeFactory runtimes
    ) {
        this(executionControl, workspaces, runtimes, Clock.systemUTC());
    }

    DefaultChangeWorker(
            ChangeExecutionControl executionControl,
            WorkspaceProvisioner workspaces,
            ChangeWorkerRuntimeFactory runtimes,
            Clock clock
    ) {
        this.executionControl = Objects.requireNonNull(executionControl, "executionControl");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void run(ChangeTaskId changeId) {
        ChangeExecutionControl.ExecutionLease execution = executionControl.claimExecution(
                Objects.requireNonNull(changeId, "changeId"));
        WorkspaceProvisioner.WorkspaceLease workspace = null;
        boolean released = false;
        try {
            ChangeTask task = execution.task();
            SpecRef spec = Objects.requireNonNull(task.spec(), "ChangeTask.spec");
            if (!spec.locked() || spec.lockedPath() == null) {
                throw new IllegalStateException("ChangeTask 没有已锁定的 ChangeSpec");
            }

            workspace = workspaces.prepare(task);
            execution.started(workspace.workspaceId());
            SpecExecutionEngine engine = Objects.requireNonNull(
                    runtimes.create(task, workspace),
                    "worker execution engine");
            SpecRunResult result = engine.execute(new SpecExecutionEngine.ExecutionContext(
                    task.requirement(),
                    new ChangeSpecModule.LockedSpec(
                            spec.lockedPath(), spec.specId(), spec.revision(), spec.digest()),
                    0L,
                    0L,
                    SpecRunResult.LlmUsage.empty(),
                    execution::verificationStarted));

            if (result.status() == SpecRunResult.Status.REACT_CANCELED
                    || result.status() == SpecRunResult.Status.REPAIR_CANCELED) {
                workspaces.release(workspace);
                released = true;
                executionControl.cancelExecution(changeId, "Spec Run 已取消");
                throw new WorkerJobCanceledException("ChangeTask Worker 已取消");
            }
            requirePersistedResult(result);
            WorkspaceProvisioner.WorkspaceSnapshot snapshot = workspaces.seal(workspace);
            workspaces.release(workspace);
            released = true;

            RunRef run = new RunRef(
                    result.identity().runId(),
                    result.identity().specDigest(),
                    result.status(),
                    result.verdict(),
                    snapshot.workspaceId(),
                    snapshot.branch(),
                    snapshot.headSha(),
                    result.artifacts().runDirectory(),
                    clock.instant());
            execution.complete(run);
        } catch (WorkerJobCanceledException canceled) {
            throw canceled;
        } catch (Exception error) {
            String detail = messageOf(error);
            if (workspace != null && !released) {
                try {
                    workspaces.release(workspace);
                    released = true;
                } catch (Exception cleanupError) {
                    detail = detail + "；释放 workspace 失败: " + messageOf(cleanupError);
                }
            }
            try {
                execution.fail(detail);
            } catch (ChangeConflictException ignored) {
                // 队列取消可能已经原子推进 ChangeTask；过期 Worker 不得覆盖取消结果。
            }
            throw new IllegalStateException("ChangeTask Worker 执行失败: " + detail, error);
        }
    }

    private static void requirePersistedResult(SpecRunResult result) {
        Objects.requireNonNull(result, "Spec Run result");
        if (result.identity() == null || result.verdict() == null) {
            throw new IllegalStateException("Spec Run 缺少 identity 或 Verdict");
        }
        if (result.artifacts().status() != SpecRunResult.PersistenceStatus.SAVED) {
            throw new IllegalStateException("Spec Run Evidence 未成功持久化: " + result.artifacts().detail());
        }
        if (result.artifacts().runDirectory() == null
                || !Files.isDirectory(result.artifacts().runDirectory())) {
            throw new IllegalStateException("Spec Run Evidence 目录不存在");
        }
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
