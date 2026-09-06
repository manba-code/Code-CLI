package com.paicli.runtime.task;

import com.paicli.change.PostgresStorageMigrations;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** PostgreSQL-backed at-least-once reference queue with leased SKIP LOCKED claims. */
public final class PostgresWorkerJobScheduler implements WorkerJobScheduler {
    private final Connection connection;
    private final int workerCount;
    private final long leaseMillis;
    private final long pollMillis;
    private final String owner = "worker-" + UUID.randomUUID();
    private final Map<String, Registration> handlers = new ConcurrentHashMap<>();
    private final Map<String, Thread> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1, r -> daemon(r, "paichange-pg-heartbeat"));
    private ExecutorService workers;
    private volatile boolean running;

    public PostgresWorkerJobScheduler(String jdbcUrl, String user, String password, int workerCount,
                                      long leaseMillis, long pollMillis) throws SQLException {
        if (workerCount < 1 || leaseMillis < 1_000 || pollMillis < 10) throw new IllegalArgumentException("非法 Worker queue 配置");
        this.connection = DriverManager.getConnection(jdbcUrl, user, password);
        this.workerCount = workerCount;
        this.leaseMillis = leaseMillis;
        this.pollMillis = pollMillis;
        PostgresStorageMigrations.migrate(connection);
    }

    @Override public synchronized void registerWorkerJobHandler(String type, WorkerJobRunner runner,
                                                                 WorkerJobLifecycleListener lifecycleListener) {
        if (running) throw new IllegalStateException("Worker Job handler 必须在 scheduler.start() 前注册");
        String normalized = text(type, "type");
        Registration registration = new Registration(java.util.Objects.requireNonNull(runner),
                lifecycleListener == null ? WorkerJobLifecycleListener.NO_OP : lifecycleListener);
        if (handlers.putIfAbsent(normalized, registration) != null) throw new IllegalStateException("Worker Job handler 已注册: " + normalized);
    }

    @Override public synchronized WorkerJob enqueueWorkerJob(String type, String referenceId) {
        String normalizedType = text(type, "type");
        String normalizedReference = text(referenceId, "referenceId");
        Optional<WorkerJob> existing = activeJob(normalizedType, normalizedReference);
        if (existing.isPresent()) return existing.get();
        String id = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO worker_jobs(id, job_type, reference_id, status, recovery_count, created_at, updated_at)
                VALUES (?, ?, ?, 'ENQUEUED', 0, ?, ?)
                """)) {
            statement.setString(1, id); statement.setString(2, normalizedType); statement.setString(3, normalizedReference);
            statement.setString(4, now); statement.setString(5, now); statement.executeUpdate();
            notifyAll();
            return new WorkerJob(id, normalizedType, normalizedReference, WorkerJobStatus.ENQUEUED, 0);
        } catch (SQLException e) {
            Optional<WorkerJob> raced = activeJob(normalizedType, normalizedReference);
            if (raced.isPresent()) return raced.get();
            throw failure("提交 PostgreSQL Worker Job 失败", e);
        }
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        workers = Executors.newFixedThreadPool(workerCount, r -> daemon(r, "paichange-pg-worker"));
        for (int i = 0; i < workerCount; i++) workers.submit(this::workerLoop);
    }

    private void workerLoop() {
        while (running) {
            WorkerJob job = null;
            try {
                job = claimNext();
                if (job == null) {
                    synchronized (this) { wait(pollMillis); }
                    continue;
                }
                Registration registration = handlers.get(job.type());
                if (registration == null) {
                    finish(job, WorkerJobStatus.FAILED, "", "未注册 Worker Job handler: " + job.type());
                    continue;
                }
                active.put(job.id(), Thread.currentThread());
                WorkerJob claimed = job;
                ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                        () -> heartbeat(claimed), leaseMillis / 3, leaseMillis / 3, TimeUnit.MILLISECONDS);
                try {
                    if (job.recoveryCount() > 0) registration.lifecycle().recovered(job);
                    String result = registration.runner().run(job);
                    finish(job, WorkerJobStatus.COMPLETED, result, null);
                } catch (WorkerJobCanceledException e) {
                    finish(job, WorkerJobStatus.CANCELED, "", e.getMessage());
                    registration.lifecycle().canceled(job, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Service shutdown deliberately leaves the lease to expire; a new process owns recovery.
                    if (running) {
                        finish(job, WorkerJobStatus.CANCELED, "", "任务线程被中断");
                        registration.lifecycle().canceled(job, "任务线程被中断");
                    }
                } catch (Exception e) {
                    finish(job, WorkerJobStatus.FAILED, "", safeError(e));
                } finally {
                    heartbeat.cancel(false);
                    active.remove(job.id());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // Persistence failures are retried by the next bounded poll; no in-memory completion is invented.
            }
        }
    }

    private synchronized WorkerJob claimNext() {
        return transaction(() -> {
            String now = Instant.now().toString();
            try (PreparedStatement recover = connection.prepareStatement("""
                    UPDATE worker_jobs SET status='ENQUEUED', recovery_count=recovery_count+1,
                        lease_owner=NULL, lease_expires_at=NULL, updated_at=?
                    WHERE status='RUNNING' AND lease_expires_at < ?
                    """)) {
                recover.setString(1, now); recover.setString(2, now); recover.executeUpdate();
            }
            WorkerJob selected = null;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, job_type, reference_id, recovery_count FROM worker_jobs
                    WHERE status='ENQUEUED' ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                    """)) {
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) selected = new WorkerJob(row.getString(1), row.getString(2), row.getString(3),
                            WorkerJobStatus.RUNNING, row.getInt(4));
                }
            }
            if (selected == null) return null;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE worker_jobs SET status='RUNNING', lease_owner=?, lease_expires_at=?,
                        started_at=COALESCE(started_at, ?), updated_at=? WHERE id=? AND status='ENQUEUED'
                    """)) {
                String expires = Instant.now().plusMillis(leaseMillis).toString();
                update.setString(1, owner); update.setString(2, expires); update.setString(3, now);
                update.setString(4, now); update.setString(5, selected.id());
                if (update.executeUpdate() != 1) return null;
            }
            return selected;
        });
    }

    private synchronized void heartbeat(WorkerJob job) {
        if (!running) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE worker_jobs SET lease_expires_at=?, updated_at=?
                WHERE id=? AND status='RUNNING' AND lease_owner=?
                """)) {
            String now = Instant.now().toString();
            statement.setString(1, Instant.now().plusMillis(leaseMillis).toString());
            statement.setString(2, now); statement.setString(3, job.id()); statement.setString(4, owner);
            statement.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private synchronized void finish(WorkerJob job, WorkerJobStatus status, String result, String error) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE worker_jobs SET status=?, result=?, error=?, finished_at=?, updated_at=?,
                    lease_owner=NULL, lease_expires_at=NULL
                WHERE id=? AND status='RUNNING' AND lease_owner=?
                """)) {
            String now = Instant.now().toString();
            statement.setString(1, status.name()); statement.setString(2, result == null ? "" : result);
            statement.setString(3, error); statement.setString(4, now); statement.setString(5, now);
            statement.setString(6, job.id()); statement.setString(7, owner); statement.executeUpdate();
        } catch (SQLException e) { throw failure("完成 PostgreSQL Worker Job 失败", e); }
    }

    private synchronized Optional<WorkerJob> activeJob(String type, String referenceId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, job_type, reference_id, status, recovery_count FROM worker_jobs
                WHERE job_type=? AND reference_id=? AND status IN ('ENQUEUED','RUNNING')
                ORDER BY created_at, id LIMIT 1
                """)) {
            statement.setString(1, type); statement.setString(2, referenceId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("读取活动 PostgreSQL Worker Job 失败", e); }
    }

    public synchronized Optional<WorkerJob> find(String id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, job_type, reference_id, status, recovery_count FROM worker_jobs WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(read(row)) : Optional.empty(); }
        } catch (SQLException e) { throw failure("读取 PostgreSQL Worker Job 失败", e); }
    }

    private static WorkerJob read(ResultSet row) throws SQLException {
        return new WorkerJob(row.getString(1), row.getString(2), row.getString(3),
                WorkerJobStatus.valueOf(row.getString(4)), row.getInt(5));
    }

    @Override public synchronized void checkHealth() {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM worker_jobs")) {
            if (!row.next()) throw new SQLException("unexpected queue schema probe result");
        } catch (SQLException e) { throw failure("PostgreSQL Worker queue 健康检查失败", e); }
    }

    @Override public synchronized WorkerQueueMetrics metrics() {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT
                       COUNT(*) FILTER (WHERE status='ENQUEUED'),
                       COUNT(*) FILTER (WHERE status='RUNNING'),
                       COUNT(*) FILTER (WHERE status='COMPLETED'),
                       COUNT(*) FILTER (WHERE status='FAILED'),
                       COUNT(*) FILTER (WHERE status='CANCELED'),
                       COALESCE(SUM(recovery_count), 0),
                       COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP -
                           (MIN(created_at::timestamptz) FILTER (WHERE status='ENQUEUED')))), 0)
                     FROM worker_jobs
                     """)) {
            if (!row.next()) throw new SQLException("unexpected queue metrics result");
            return new WorkerQueueMetrics(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4),
                    row.getLong(5), row.getLong(6), Math.max(0, row.getLong(7)));
        } catch (SQLException e) { throw failure("读取 PostgreSQL Worker queue 指标失败", e); }
    }

    @Override public synchronized void close() {
        running = false;
        notifyAll();
        if (workers != null) {
            workers.shutdownNow();
            try { workers.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        heartbeats.shutdownNow();
        try { connection.close(); } catch (SQLException ignored) { }
    }

    private <T> T transaction(SqlOperation<T> operation) {
        try {
            boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
            try { T value = operation.run(); connection.commit(); return value; }
            catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(autoCommit); }
        } catch (SQLException e) { throw failure("PostgreSQL Worker queue 事务失败", e); }
    }

    private static String safeError(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName()
                : value.substring(0, Math.min(value.length(), 512));
    }
    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
    private static IllegalStateException failure(String message, SQLException e) {
        return new IllegalStateException(message + ": " + e.getMessage(), e);
    }
    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }
    @FunctionalInterface private interface SqlOperation<T> { T run() throws SQLException; }
    private record Registration(WorkerJobRunner runner, WorkerJobLifecycleListener lifecycle) { }
}
