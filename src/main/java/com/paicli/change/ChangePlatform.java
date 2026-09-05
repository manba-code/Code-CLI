package com.paicli.change;

import com.paicli.config.PaiCliConfig;
import com.paicli.runtime.api.ChangeApiHandler;
import com.paicli.runtime.task.DurableTaskManager;
import com.paicli.spec.ChangeSpecModule;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 本地单进程装配。后台扫描持久化状态，补偿 queue/SCM 与状态事务之间的中断窗口。 */
public final class ChangePlatform implements AutoCloseable {
    private final FileChannel lockChannel;
    private final FileLock lock;
    private SqliteChangeStore store;
    private MockScmAdapter scm;
    private DurableTaskManager jobs;
    private com.paicli.runtime.task.DraftJobRunner drafts;
    private DefaultChangeWorkflow workflow;
    private ChangeApiHandler handler;
    private boolean closed;
    private boolean started;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paichange-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    public ChangePlatform(Path root, Path fixtures, ChangeSpecModule specs, PaiCliConfig config,
                          WorkspaceProvisioner workspaces, ChangeWorkerRuntimeFactory runtimes,
                          DeliveryHeadReader heads) throws Exception {
        this(root, fixtures, specs, config, workspaces, runtimes, heads, false);
    }

    public ChangePlatform(Path root, Path fixtures, ChangeSpecModule specs, PaiCliConfig config,
                          WorkspaceProvisioner workspaces, ChangeWorkerRuntimeFactory runtimes,
                          DeliveryHeadReader heads, boolean offlineDemo) throws Exception {
        Files.createDirectories(root);
        lockChannel = FileChannel.open(root.resolve("platform.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IllegalStateException("PaiChange 数据目录已由另一进程使用");
        } catch (Exception e) {
            lockChannel.close();
            throw e;
        }
        lock = acquired;
        try {
            Path database = root.resolve("changes.db");
            store = new SqliteChangeStore(database);
            scm = new MockScmAdapter(database);
            workflow = new DefaultChangeWorkflow(store, store, specs, config, Clock.systemUTC());
            drafts = new com.paicli.runtime.task.DraftJobRunner(workflow, store);
            jobs = new DurableTaskManager(database, prompt -> {
                throw new IllegalStateException("PaiChange 队列只接受 reference-only Worker Job");
            }, 2);
            DefaultChangeWorker worker = new DefaultChangeWorker(workflow, workspaces, runtimes);
            new ChangeWorkerJobHandler(workflow, id -> {
                // A recovered technical job may remain after its business result committed.
                if (workflow.get(id).task().state() == ChangeState.QUEUED) worker.run(id);
            }).register(jobs);
            workflow.connect(id -> jobs.enqueueWorkerJob(ChangeWorkerJobHandler.JOB_TYPE, id.value()), scm, heads);
            Path artifacts = workspaces instanceof GitWorktreeWorkspaceProvisioner git ? git.artifactRoot() : root;
            handler = new ChangeApiHandler(workflow, store, new MockWorkItemAdapter(fixtures, workflow),
                    new ChangeArtifactReader(root, artifacts), offlineDemo);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public static Path defaultRoot() {
        String configured = System.getProperty("paichange.data.dir");
        if (configured == null || configured.isBlank()) configured = System.getenv("PAICHANGE_DATA_DIR");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".paichange") : Path.of(configured);
    }

    public ChangeApiHandler handler() { return handler; }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("ChangePlatform 已关闭");
        if (started) return;
        started = true;
        drafts.start();
        jobs.start();
        scheduler.scheduleWithFixedDelay(this::resumePending, 0, 250, TimeUnit.MILLISECONDS);
    }

    private void resumePending() {
        try {
            for (ChangeTask task : store.list()) {
                try { workflow.advance(task.id()); }
                catch (RuntimeException e) {
                    // Persist a deduplicated failure event; next scan can retry without changing the Verdict.
                    workflow.recordDispatchFailure(task.id(), e);
                }
            }
        } catch (RuntimeException e) {
            // Persistence unavailable: stop this scan, never advance using an in-memory guess.
            System.getLogger(ChangePlatform.class.getName()).log(System.Logger.Level.WARNING,
                    "PaiChange 后台调度未完成，等待下次扫描", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdownNow();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (drafts != null) drafts.close();
        if (jobs != null) jobs.close();
        if (scm != null) {
            try { scm.close(); } catch (Exception e) { /* Already closing, cannot publish. */ }
        }
        if (store != null) store.close();
        try { lock.release(); } catch (Exception e) { /* Channel close releases the lock too. */ }
        try { lockChannel.close(); } catch (Exception e) { /* Best effort shutdown. */ }
    }
}
