package com.paicli.change;

import com.paicli.config.PaiCliConfig;
import com.paicli.runtime.api.ChangeApiHandler;
import com.paicli.runtime.task.DurableTaskManager;
import com.paicli.runtime.task.PostgresWorkerJobScheduler;
import com.paicli.runtime.task.WorkerJobScheduler;
import com.paicli.runtime.auth.OidcSettings;
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
    private ChangePersistence store;
    private ScmAdapter scm;
    private WorkerJobScheduler jobs;
    private com.paicli.runtime.task.DraftJobRunner drafts;
    private DefaultChangeWorkflow workflow;
    private ToolApprovalCoordinator toolApprovals;
    private EvidenceStore evidenceStore;
    private ProjectMembershipDirectory membershipDirectory;
    private String storageBackend;
    private WorkerIsolation isolation;
    private ChangeApiHandler handler;
    private ChangeOperations operations;
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
        this(root, fixtures, specs, config, workspaces, runtimes, heads, offlineDemo, null,
                EphemeralSecretProvider.none());
    }

    public ChangePlatform(Path root, Path fixtures, ChangeSpecModule specs, PaiCliConfig config,
                          WorkspaceProvisioner workspaces, ChangeWorkerRuntimeFactory runtimes,
                          DeliveryHeadReader heads, boolean offlineDemo, ChangeAuthorizer authorizer) throws Exception {
        this(root, fixtures, specs, config, workspaces, runtimes, heads, offlineDemo, authorizer,
                EphemeralSecretProvider.none());
    }

    public ChangePlatform(Path root, Path fixtures, ChangeSpecModule specs, PaiCliConfig config,
                          WorkspaceProvisioner workspaces, ChangeWorkerRuntimeFactory runtimes,
                          DeliveryHeadReader heads, boolean offlineDemo, ChangeAuthorizer authorizer,
                          EphemeralSecretProvider secretProvider) throws Exception {
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
            ProductionStorageSettings production = !offlineDemo && ProductionStorageSettings.enabled()
                    ? ProductionStorageSettings.fromProcess() : null;
            ProductionOperationsSettings productionOperations = null;
            OidcSettings productionIdentities = null;
            ConfiguredScm.Configuration scmConfiguration = ConfiguredScm.load(offlineDemo, production != null);
            if (production != null) {
                productionOperations = ProductionOperationsSettings.fromProcess();
                productionIdentities = OidcSettings.fromProcess();
                ProductionStartupValidator.validate(production, productionOperations, productionIdentities,
                        scmConfiguration.remote());
            }
            if (production == null) {
                store = new SqliteChangeStore(database);
                evidenceStore = new TrustedEvidenceStore(database, root.resolve("evidence-archive"));
                jobs = new DurableTaskManager(database, prompt -> {
                    throw new IllegalStateException("PaiChange 队列只接受 reference-only Worker Job");
                }, 2);
            } else {
                store = new PostgresChangeStore(production.jdbcUrl(), production.user(), production.password());
                ObjectStorage objects = new S3ObjectStorage(production.objectEndpoint(), production.objectBucket(),
                        production.objectRegion(), production.objectAccessKey(), production.objectSecretKey());
                evidenceStore = new PostgresEvidenceStore(production.jdbcUrl(), production.user(), production.password(),
                        objects, root.resolve("evidence-cache"));
                jobs = new PostgresWorkerJobScheduler(production.jdbcUrl(), production.user(), production.password(),
                        production.workerCount(), production.queueLeaseMillis(), production.queuePollMillis());
            }
            ChangeAuthorizer effectiveAuthorizer = authorizer;
            ProjectMemberService memberService = null;
            if (effectiveAuthorizer == null && production != null) {
                membershipDirectory = new PostgresProjectMembershipDirectory(
                        production.jdbcUrl(), production.user(), production.password());
                effectiveAuthorizer = new ChangeAuthorizer(membershipDirectory);
                memberService = new ProjectMemberService(membershipDirectory, effectiveAuthorizer,
                        productionIdentities.bootstrapAdminSubject());
            } else if (effectiveAuthorizer == null) {
                effectiveAuthorizer = new ChangeAuthorizer(ProjectMembershipProvider.none());
            }
            storageBackend = store.backend();
            isolation = DockerWorkerIsolation.fromConfig(root, config, secretProvider);
            workflow = new DefaultChangeWorkflow(store, store, specs, config, Clock.systemUTC());
            ConfiguredScm.Adapters scmAdapters = ConfiguredScm.assemble(scmConfiguration, database, fixtures,
                    workflow, production);
            scm = scmAdapters.scm();
            WorkItemAdapter workItems = scmAdapters.workItems();
            toolApprovals = new ToolApprovalCoordinator(store, store, effectiveAuthorizer);
            drafts = new com.paicli.runtime.task.DraftJobRunner(workflow, store);
            DefaultChangeWorker worker = new DefaultChangeWorker(workflow, workspaces, runtimes,
                    new ChangeWorkerRuntimeContext(toolApprovals), isolation, evidenceStore);
            new ChangeWorkerJobHandler(workflow, id -> {
                // A recovered technical job may remain after its business result committed.
                if (workflow.get(id).task().state() == ChangeState.QUEUED) worker.run(id);
            }, toolApprovals, isolation.capabilities().enabled()).register(jobs);
            workflow.connect(id -> jobs.enqueueWorkerJob(ChangeWorkerJobHandler.JOB_TYPE, id.value()), scm, heads);
            handler = new ChangeApiHandler(workflow, store, workItems,
                    new ChangeArtifactReader(evidenceStore, root, evidenceStore.root()), offlineDemo, effectiveAuthorizer,
                    toolApprovals, isolation.capabilities(), true, memberService);
            operations = new ChangeOperations(store, jobs, evidenceStore, scm, productionOperations);
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

    public ChangeOperations operations() { return operations; }

    public StorageHealth storageHealth() {
        store.checkHealth(); jobs.checkHealth(); evidenceStore.checkHealth();
        return new StorageHealth(storageBackend, store.schemaVersion(), "UP");
    }

    public record StorageHealth(String backend, int schemaVersion, String status) { }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("ChangePlatform 已关闭");
        if (started) return;
        started = true;
        try { storageHealth(); }
        catch (RuntimeException e) {
            started = false;
            throw new IllegalStateException("PaiChange 存储或持久化队列启动检查失败", e);
        }
        try { isolation.recoverOrphans(); }
        catch (Exception e) {
            started = false;
            throw new IllegalStateException("M6a Docker 隔离启动检查或遗留容器清理失败", e);
        }
        toolApprovals.recoverPending();
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
        if (operations != null) operations.markClosed();
        scheduler.shutdownNow();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (drafts != null) drafts.close();
        if (toolApprovals != null) toolApprovals.close();
        if (jobs != null) jobs.close();
        if (isolation != null) isolation.close();
        if (scm != null) {
            try { scm.close(); } catch (Exception e) { /* Already closing, cannot publish. */ }
        }
        if (evidenceStore != null) evidenceStore.close();
        if (membershipDirectory != null) membershipDirectory.close();
        if (store != null) store.close();
        try { lock.release(); } catch (Exception e) { /* Channel close releases the lock too. */ }
        try { lockChannel.close(); } catch (Exception e) { /* Best effort shutdown. */ }
    }
}
