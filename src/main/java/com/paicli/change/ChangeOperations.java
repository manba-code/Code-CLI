package com.paicli.change;

import com.paicli.runtime.auth.PrincipalAdapter;
import com.paicli.runtime.task.WorkerJobScheduler;
import com.paicli.runtime.task.WorkerQueueMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Deep operational module: dependency health and aggregate metrics without workflow or credential access. */
public final class ChangeOperations {
    private final ChangePersistence persistence;
    private final WorkerJobScheduler jobs;
    private final EvidenceStore evidence;
    private final ScmAdapter scm;
    private final Instant startedAt = Instant.now();
    private final AtomicLong readinessChecks = new AtomicLong();
    private final AtomicLong readinessFailures = new AtomicLong();
    private final long declaredRpoSeconds;
    private final long declaredRtoSeconds;
    private volatile boolean closed;

    public ChangeOperations(ChangePersistence persistence, WorkerJobScheduler jobs,
                            EvidenceStore evidence, ScmAdapter scm) {
        this(persistence, jobs, evidence, scm, null);
    }

    public ChangeOperations(ChangePersistence persistence, WorkerJobScheduler jobs,
                            EvidenceStore evidence, ScmAdapter scm, ProductionOperationsSettings settings) {
        this.persistence = Objects.requireNonNull(persistence);
        this.jobs = Objects.requireNonNull(jobs);
        this.evidence = Objects.requireNonNull(evidence);
        this.scm = Objects.requireNonNull(scm);
        this.declaredRpoSeconds = settings == null ? 0 : settings.rpoSeconds();
        this.declaredRtoSeconds = settings == null ? 0 : settings.rtoSeconds();
    }

    public HealthSnapshot liveness() {
        return new HealthSnapshot(closed ? "DOWN" : "UP", Instant.now(), uptimeSeconds(),
                Map.of("process", new ComponentHealth(closed ? "DOWN" : "UP", closed ? "closed" : "")),
                WorkerQueueMetrics.empty(), ChangeTaskMetrics.empty());
    }

    public HealthSnapshot readiness(PrincipalAdapter identities) {
        Objects.requireNonNull(identities);
        readinessChecks.incrementAndGet();
        Map<String, ComponentHealth> components = new LinkedHashMap<>();
        probe(components, "database", persistence::checkHealth);
        probe(components, "worker_queue", jobs::checkHealth);
        probe(components, "evidence_store", evidence::checkHealth);
        probe(components, "scm", scm::checkHealth);
        probe(components, "identity_jwks", identities::checkHealth);
        WorkerQueueMetrics queue;
        try { queue = jobs.metrics(); }
        catch (RuntimeException error) {
            queue = WorkerQueueMetrics.empty();
            components.put("worker_queue", unavailable(error));
        }
        ChangeTaskMetrics tasks;
        try { tasks = persistence.metrics(); }
        catch (RuntimeException error) {
            tasks = ChangeTaskMetrics.empty();
            components.put("database", unavailable(error));
        }
        boolean up = !closed && components.values().stream().allMatch(value -> value.status().equals("UP"));
        if (!up) readinessFailures.incrementAndGet();
        return new HealthSnapshot(up ? "UP" : "DOWN", Instant.now(), uptimeSeconds(),
                Map.copyOf(components), queue, tasks);
    }

    public String prometheus(PrincipalAdapter identities) {
        HealthSnapshot snapshot = readiness(identities);
        StringBuilder out = new StringBuilder();
        metric(out, "paichange_liveness", closed ? 0 : 1);
        metric(out, "paichange_readiness", snapshot.status().equals("UP") ? 1 : 0);
        metric(out, "paichange_uptime_seconds", snapshot.uptimeSeconds());
        metric(out, "paichange_readiness_checks_total", readinessChecks.get());
        metric(out, "paichange_readiness_failures_total", readinessFailures.get());
        metric(out, "paichange_declared_rpo_seconds", declaredRpoSeconds);
        metric(out, "paichange_declared_rto_seconds", declaredRtoSeconds);
        snapshot.components().forEach((name, value) ->
                out.append("paichange_dependency_up{name=\"").append(name).append("\"} ")
                        .append(value.status().equals("UP") ? 1 : 0).append('\n'));
        WorkerQueueMetrics queue = snapshot.queue();
        metric(out, "paichange_worker_jobs_enqueued", queue.enqueued());
        metric(out, "paichange_worker_jobs_running", queue.running());
        metric(out, "paichange_worker_jobs_completed_total", queue.completed());
        metric(out, "paichange_worker_jobs_failed_total", queue.failed());
        metric(out, "paichange_worker_jobs_canceled_total", queue.canceled());
        metric(out, "paichange_worker_job_recoveries_total", queue.recoveries());
        metric(out, "paichange_worker_oldest_enqueued_age_seconds", queue.oldestEnqueuedAgeSeconds());
        ChangeTaskMetrics tasks = snapshot.tasks();
        metric(out, "paichange_change_tasks", tasks.total());
        metric(out, "paichange_change_tasks_active", tasks.active());
        metric(out, "paichange_change_tasks_failed", tasks.failed());
        metric(out, "paichange_change_tasks_completed", tasks.completed());
        metric(out, "paichange_change_tasks_delivery_review", tasks.deliveryReview());
        metric(out, "paichange_dispatch_failures_total", tasks.dispatchFailures());
        return out.toString();
    }

    public void markClosed() { closed = true; }

    private void probe(Map<String, ComponentHealth> components, String name, Runnable check) {
        try {
            check.run();
            components.put(name, new ComponentHealth("UP", ""));
        } catch (RuntimeException error) {
            components.put(name, unavailable(error));
            System.getLogger(ChangeOperations.class.getName()).log(System.Logger.Level.WARNING,
                    "PaiChange readiness dependency unavailable: " + name + " "
                            + SensitiveValueRedactor.redact(error.getMessage()));
        }
    }

    private static ComponentHealth unavailable(RuntimeException error) {
        String code = error instanceof ChangeConflictException ? "integrity_check_failed" : "dependency_unavailable";
        return new ComponentHealth("DOWN", code);
    }

    private long uptimeSeconds() {
        return Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
    }

    private static void metric(StringBuilder out, String name, long value) {
        out.append(name).append(' ').append(value).append('\n');
    }

    public record ComponentHealth(String status, String code) { }
    public record HealthSnapshot(String status, Instant checkedAt, long uptimeSeconds,
                                 Map<String, ComponentHealth> components, WorkerQueueMetrics queue,
                                 ChangeTaskMetrics tasks) { }
}
