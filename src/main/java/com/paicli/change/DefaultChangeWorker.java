package com.paicli.change;

import com.paicli.runtime.task.WorkerJobCanceledException;
import com.paicli.spec.ChangeSpecModule;
import com.paicli.spec.SpecExecutionEngine;
import com.paicli.spec.SpecRunResult;

import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Objects;

/** ChangeTask Worker：领取、隔离执行、封存代码身份并把结果交回 ChangeWorkflow。 */
public final class DefaultChangeWorker implements ChangeWorker {
    private final ChangeExecutionControl executionControl;
    private final WorkspaceProvisioner workspaces;
    private final ChangeWorkerRuntimeFactory runtimes;
    private final ChangeWorkerRuntimeContext runtimeContext;
    private final WorkerIsolation isolation;
    private final TrustedEvidenceStore evidenceStore;
    private final Clock clock;

    public DefaultChangeWorker(
            ChangeExecutionControl executionControl,
            WorkspaceProvisioner workspaces,
            ChangeWorkerRuntimeFactory runtimes
    ) {
        this(executionControl, workspaces, runtimes, Clock.systemUTC(), ChangeWorkerRuntimeContext.none(),
                WorkerIsolation.none(), null);
    }

    public DefaultChangeWorker(ChangeExecutionControl executionControl, WorkspaceProvisioner workspaces,
                               ChangeWorkerRuntimeFactory runtimes, ChangeWorkerRuntimeContext runtimeContext) {
        this(executionControl, workspaces, runtimes, Clock.systemUTC(), runtimeContext,
                WorkerIsolation.none(), null);
    }

    public DefaultChangeWorker(ChangeExecutionControl executionControl, WorkspaceProvisioner workspaces,
                               ChangeWorkerRuntimeFactory runtimes, ChangeWorkerRuntimeContext runtimeContext,
                               WorkerIsolation isolation, TrustedEvidenceStore evidenceStore) {
        this(executionControl, workspaces, runtimes, Clock.systemUTC(), runtimeContext, isolation, evidenceStore);
    }

    DefaultChangeWorker(
            ChangeExecutionControl executionControl,
            WorkspaceProvisioner workspaces,
            ChangeWorkerRuntimeFactory runtimes,
            Clock clock
    ) {
        this(executionControl, workspaces, runtimes, clock, ChangeWorkerRuntimeContext.none(),
                WorkerIsolation.none(), null);
    }

    DefaultChangeWorker(
            ChangeExecutionControl executionControl,
            WorkspaceProvisioner workspaces,
            ChangeWorkerRuntimeFactory runtimes,
            Clock clock,
            ChangeWorkerRuntimeContext runtimeContext
    ) {
        this(executionControl, workspaces, runtimes, clock, runtimeContext, WorkerIsolation.none(), null);
    }

    DefaultChangeWorker(
            ChangeExecutionControl executionControl,
            WorkspaceProvisioner workspaces,
            ChangeWorkerRuntimeFactory runtimes,
            Clock clock,
            ChangeWorkerRuntimeContext runtimeContext,
            WorkerIsolation isolation,
            TrustedEvidenceStore evidenceStore
    ) {
        this.executionControl = Objects.requireNonNull(executionControl, "executionControl");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeContext = runtimeContext == null ? ChangeWorkerRuntimeContext.none() : runtimeContext;
        this.isolation = isolation == null ? WorkerIsolation.none() : isolation;
        this.evidenceStore = evidenceStore;
    }

    @Override
    public void run(ChangeTaskId changeId) {
        ChangeExecutionControl.ExecutionLease execution = executionControl.claimExecution(
                Objects.requireNonNull(changeId, "changeId"));
        WorkspaceProvisioner.WorkspaceLease workspace = null;
        WorkerIsolation.Session isolationSession = null;
        boolean released = false;
        try {
            ChangeTask task = execution.task();
            SpecRef spec = Objects.requireNonNull(task.spec(), "ChangeTask.spec");
            if (!spec.locked() || spec.lockedPath() == null) {
                throw new IllegalStateException("ChangeTask 没有已锁定的 ChangeSpec");
            }

            workspace = workspaces.prepare(task);
            isolationSession = isolation.open(task, workspace);
            execution.started(workspace.workspaceId());
            SpecExecutionEngine engine = Objects.requireNonNull(
                    runtimes.create(task, workspace, runtimeContext.withIsolation(isolationSession)),
                    "worker execution engine");
            SpecExecutionEngine.ExecutionContext engineContext = new SpecExecutionEngine.ExecutionContext(
                    task.requirement(),
                    new ChangeSpecModule.LockedSpec(
                            spec.lockedPath(), spec.specId(), spec.revision(), spec.digest()),
                    0L,
                    0L,
                    SpecRunResult.LlmUsage.empty(),
                    execution::verificationStarted);
            SpecRunResult result = execute(engine, engineContext, isolationSession);
            isolationSession.ensureHealthy();
            isolationSession.close();
            isolationSession = null;

            if (result.status() == SpecRunResult.Status.REACT_CANCELED
                    || result.status() == SpecRunResult.Status.REPAIR_CANCELED) {
                workspaces.release(workspace);
                released = true;
                executionControl.cancelExecution(changeId, "Spec Run 已取消");
                throw new WorkerJobCanceledException("ChangeTask Worker 已取消");
            }
            requirePersistedResult(result);
            WorkspaceProvisioner.WorkspaceSnapshot snapshot = workspaces.seal(workspace);
            java.nio.file.Path evidencePath = result.artifacts().runDirectory();
            if (evidenceStore != null) {
                TrustedEvidenceStore.Capture archived = evidenceStore.capture(
                        task.id(), result.identity().runId(), result.artifacts().runDirectory());
                evidencePath = archived.path();
                evidenceStore.discardSource(result.artifacts().runDirectory());
            }
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
                    evidencePath,
                    clock.instant());
            execution.complete(run);
        } catch (WorkerJobCanceledException canceled) {
            if (isolationSession != null) isolationSession.abort("Worker Job 已取消");
            if (workspace != null && !released) {
                try { workspaces.release(workspace); }
                catch (Exception cleanupError) { canceled.addSuppressed(cleanupError); }
            }
            throw canceled;
        } catch (Exception error) {
            String detail = messageOf(error);
            if (isolationSession != null) {
                try { isolationSession.abort(detail); }
                catch (Exception cleanupError) { detail = detail + "；清理隔离容器失败: " + messageOf(cleanupError); }
            }
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

    private static SpecRunResult execute(SpecExecutionEngine engine,
                                         SpecExecutionEngine.ExecutionContext context,
                                         WorkerIsolation.Session isolation) throws Exception {
        if (isolation == null || !isolation.isolated()) return engine.execute(context);
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paichange-isolated-worker");
            thread.setDaemon(true);
            return thread;
        });
        var future = executor.submit(() -> engine.execute(context));
        try {
            return future.get(isolation.taskTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            isolation.abort("任务总时限到期");
            future.cancel(true);
            throw new IllegalStateException("M6a Worker 任务总超时，隔离容器已清理", timeout);
        } catch (InterruptedException interrupted) {
            isolation.abort("Worker 被取消");
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new WorkerJobCanceledException("M6a Worker 被取消，隔离容器已清理");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("隔离 Worker 执行失败", cause);
        } finally {
            executor.shutdownNow();
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
