package com.paicli.runtime.task;

import com.paicli.change.*;
import com.paicli.llm.LlmCallCancellation;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/** Independent Draft queue consumer; the task's persisted job is the queue itself.
 * No in-memory submission is needed for durability. No provider call holds the workflow lock. */
public final class DraftJobRunner implements AutoCloseable {
    private final DefaultChangeWorkflow workflow;
    private final ChangeStore store;
    private final long timeoutMs;
    private final int concurrency;
    private final ScheduledExecutorService scanner;
    private final ExecutorService workers;
    private final Map<ChangeTaskId, Active> active = new HashMap<>();
    private boolean closed;
    private boolean started;

    private static final class Active {
        final DraftJob claim;
        final LlmCallCancellation cancellation = new LlmCallCancellation();
        FutureTask<Void> future;
        volatile boolean exited;
        Active(DraftJob claim) { this.claim = claim; }
        void cancel() { cancellation.cancel(); future.cancel(true); }
    }

    public DraftJobRunner(DefaultChangeWorkflow workflow, ChangeStore store) {
        this(workflow, store, 2, DraftJob.TIMEOUT_MS);
    }

    public DraftJobRunner(DefaultChangeWorkflow workflow, ChangeStore store, int concurrency, long timeoutMs) {
        if (concurrency < 1 || timeoutMs < 1) throw new IllegalArgumentException("Draft limits must be positive");
        this.workflow = workflow;
        this.store = store;
        this.concurrency = concurrency;
        this.timeoutMs = timeoutMs;
        this.scanner = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "paichange-draft-scan"));
        this.workers = Executors.newFixedThreadPool(concurrency, r -> daemon(r, "paichange-draft"));
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Draft runner closed");
        if (started) return;
        workflow.recoverDrafts();
        started = true;
        scanner.scheduleWithFixedDelay(this::scanSafely, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void scanSafely() {
        try { scan(); }
        catch (RuntimeException e) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Draft 调度未完成；保留持久化状态等待重试", e);
        }
    }

    private synchronized void scan() {
        if (closed) return;
        for (var entry : active.entrySet()) {
            Active running = entry.getValue();
            if (!workflow.draftClaimActive(entry.getKey(), running.claim)) {
                // Expiry first fences the lease in storage, then terminates any cancelable provider call.
                workflow.failDraft(entry.getKey(), running.claim, true, "Draft 生成超时，租约已失效");
                running.cancel();
            }
        }
        // Future.cancel may finish the Future before an uncooperative provider exits. Keep its slot bounded.
        active.entrySet().removeIf(e -> e.getValue().exited);
        for (ChangeTask task : store.list()) {
            if (active.size() >= concurrency) break;
            if (active.containsKey(task.id())) continue;
            DraftJob persisted = task.draftJob();
            if (persisted != null && persisted.status() == DraftJob.Status.RUNNING
                    && !workflow.draftClaimActive(task.id(), persisted)) {
                workflow.failDraft(task.id(), persisted, true, "Draft 租约过期，恢复未确认的生成");
            }
            DraftJob claim = workflow.claimDraft(task.id(), timeoutMs);
            if (claim == null) continue;
            Active running = new Active(claim);
            running.future = new FutureTask<>(() -> {
                running.cancellation.enter();
                try {
                    if (workflow.draftClaimActive(task.id(), claim)) {
                        var draft = workflow.generateDraft(claim);
                        workflow.completeDraft(task.id(), claim, draft);
                    }
                } catch (IOException error) {
                    boolean retryable = !(error instanceof com.paicli.llm.LlmHttpException http) || http.retryable();
                    if (error instanceof java.nio.file.FileSystemException) retryable = false;
                    String reason = error instanceof com.paicli.llm.LlmHttpException http
                            ? "Draft 模型请求失败（HTTP " + http.status() + "）"
                            : "Draft I/O 故障";
                    workflow.failDraft(task.id(), claim, retryable, reason);
                } catch (RuntimeException error) {
                    // Do not expose provider bodies, credentials or raw prompt content in public errors.
                    workflow.failDraft(task.id(), claim, false,
                            error instanceof com.paicli.spec.ChangeSpecValidationException
                                    ? "Draft 内容资格校验失败；请检查需求后重试" : "Draft 生成或持久化失败");
                } finally {
                    running.cancellation.close();
                }
                return null;
            });
            active.put(task.id(), running);
            workers.execute(() -> {
                try { running.future.run(); }
                finally { running.exited = true; }
            });
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        scanner.shutdownNow();
        for (var entry : active.entrySet()) {
            try { workflow.failDraft(entry.getKey(), entry.getValue().claim, true, "服务关闭，生成中断"); }
            finally { entry.getValue().cancel(); }
        }
        workers.shutdownNow();
        // Late callbacks are fenced; daemon threads cannot keep the local service alive.
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
