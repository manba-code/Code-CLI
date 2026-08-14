package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkerPoolTest {

    @Test
    void shouldPrewarmAndExpandToMaximum() throws Exception {
        AtomicInteger created = new AtomicInteger();
        WorkerPool pool = new WorkerPool(2, 4, number -> worker(number, created));
        List<WorkerPool.Lease> leases = new ArrayList<>();

        try {
            assertEquals(2, pool.stats().total());
            assertEquals(2, pool.stats().idle());

            Set<String> names = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                WorkerPool.Lease lease = pool.acquire();
                leases.add(lease);
                names.add(lease.worker().getName());
            }

            assertEquals(4, created.get());
            assertEquals(4, names.size());
            assertEquals(4, pool.stats().busy());
        } finally {
            leases.forEach(WorkerPool.Lease::close);
            pool.close();
        }
    }

    @Test
    void shouldTrimExpandedWorkersAtBatchBoundary() throws Exception {
        List<SubAgent> created = new ArrayList<>();
        WorkerPool pool = new WorkerPool(2, 4, number -> {
            SubAgent worker = worker(number, new AtomicInteger());
            created.add(worker);
            return worker;
        });
        List<WorkerPool.Lease> leases = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            leases.add(pool.acquire());
        }
        leases.forEach(WorkerPool.Lease::close);
        pool.trimToMinimum();

        assertEquals(2, pool.stats().total());
        assertEquals(2, pool.stats().idle());
        assertEquals(2, created.stream().filter(SubAgent::isClosed).count());
        pool.close();
    }

    @Test
    void shouldFinishPendingTrimWhenBusyWorkersReturn() throws Exception {
        WorkerPool pool = new WorkerPool(2, 4, number -> worker(number, new AtomicInteger()));
        List<WorkerPool.Lease> leases = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            leases.add(pool.acquire());
        }

        pool.trimToMinimum();
        assertEquals(4, pool.stats().total(), "busy workers must not be closed by trim");

        leases.forEach(WorkerPool.Lease::close);

        assertEquals(2, pool.stats().total());
        assertEquals(2, pool.stats().idle());
        pool.close();
    }

    @Test
    void shouldWaitAtMaximumUntilWorkerIsReturned() throws Exception {
        WorkerPool pool = new WorkerPool(1, 2, number -> worker(number, new AtomicInteger()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        WorkerPool.Lease first = pool.acquire();
        WorkerPool.Lease second = pool.acquire();
        CountDownLatch waitingStarted = new CountDownLatch(1);

        try {
            Future<WorkerPool.Lease> waiting = executor.submit(() -> {
                waitingStarted.countDown();
                return pool.acquire();
            });
            assertTrue(waitingStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(waiting.isDone());

            String releasedWorker = first.worker().getName();
            first.close();
            try (WorkerPool.Lease returned = waiting.get(2, TimeUnit.SECONDS)) {
                assertEquals(releasedWorker, returned.worker().getName());
            }
        } finally {
            first.close();
            second.close();
            pool.close();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnLeaseOnlyOnceAndClearHistory() throws Exception {
        AtomicInteger clearCalls = new AtomicInteger();
        WorkerPool pool = new WorkerPool(1, 1,
                number -> new CountingSubAgent("worker-" + number, clearCalls));

        WorkerPool.Lease lease = pool.acquire();
        lease.close();
        lease.close();

        assertEquals(1, clearCalls.get());
        assertEquals(1, pool.stats().idle());
        assertThrows(IllegalStateException.class, lease::worker);
        pool.close();
    }

    @Test
    void shouldRejectAcquireAfterCloseAndCloseActiveWorkerOnReturn() throws Exception {
        WorkerPool pool = new WorkerPool(1, 1, number -> worker(number, new AtomicInteger()));
        WorkerPool.Lease active = pool.acquire();
        SubAgent worker = active.worker();

        pool.close();

        assertTrue(pool.stats().closed());
        assertEquals(1, pool.stats().busy());
        assertThrows(IllegalStateException.class, pool::acquire);

        active.close();
        assertEquals(0, pool.stats().total());
        assertTrue(worker.isClosed());
    }

    @Test
    void shouldRemoveWorkerWhenHistoryCleanupFailsAndCreateReplacement() throws Exception {
        WorkerPool pool = new WorkerPool(1, 1, number -> number == 1
                ? new FailingClearSubAgent("worker-" + number)
                : worker(number, new AtomicInteger()));

        WorkerPool.Lease broken = pool.acquire();
        SubAgent brokenWorker = broken.worker();
        broken.close();
        assertEquals(0, pool.stats().total());
        assertTrue(brokenWorker.isClosed());

        try (WorkerPool.Lease replacement = pool.acquire()) {
            assertEquals("worker-2", replacement.worker().getName());
        } finally {
            pool.close();
        }
    }

    @Test
    void shouldWakeBlockedAcquireWhenPoolCloses() throws Exception {
        WorkerPool pool = new WorkerPool(1, 1, number -> worker(number, new AtomicInteger()));
        WorkerPool.Lease active = pool.acquire();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> waiting = executor.submit(() -> assertThrows(IllegalStateException.class, pool::acquire));
            Thread.sleep(100);
            pool.close();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> waiting.get(2, TimeUnit.SECONDS));
        } finally {
            active.close();
            executor.shutdownNow();
        }
    }

    private static SubAgent worker(int number, AtomicInteger created) {
        created.incrementAndGet();
        return new SubAgent("worker-" + number, AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
    }

    private static final class CountingSubAgent extends SubAgent {
        private final AtomicInteger clearCalls;

        private CountingSubAgent(String name, AtomicInteger clearCalls) {
            super(name, AgentRole.WORKER, new GLMClient("test-key"), new ToolRegistry());
            this.clearCalls = clearCalls;
        }

        @Override
        public void clearHistory() {
            super.clearHistory();
            clearCalls.incrementAndGet();
        }
    }

    private static final class FailingClearSubAgent extends SubAgent {
        private FailingClearSubAgent(String name) {
            super(name, AgentRole.WORKER, new GLMClient("test-key"), new ToolRegistry());
        }

        @Override
        public void clearHistory() {
            throw new IllegalStateException("simulated cleanup failure");
        }
    }
}
