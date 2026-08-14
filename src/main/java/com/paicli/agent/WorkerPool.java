package com.paicli.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * 单个 {@link AgentOrchestrator} 私有的 Worker 生命周期池。
 *
 * <p>池按压力从最小容量扩展到最大容量；批次结束后由编排器触发缩容。
 * Worker 只能位于空闲队列、一个有效 Lease 或关闭状态之一。</p>
 */
final class WorkerPool implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final int minWorkers;
    private final int maxWorkers;
    private final IntFunction<SubAgent> workerFactory;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workerReturned = lock.newCondition();
    private final Deque<SubAgent> availableWorkers = new ArrayDeque<>();
    private final Set<SubAgent> allWorkers = new HashSet<>();
    private boolean closed;
    private boolean trimRequested;
    private int nextWorkerNumber = 1;

    WorkerPool(int minWorkers, int maxWorkers, IntFunction<SubAgent> workerFactory) {
        if (minWorkers < 1) {
            throw new IllegalArgumentException("minWorkers 必须至少为 1");
        }
        if (maxWorkers < minWorkers) {
            throw new IllegalArgumentException("maxWorkers 不能小于 minWorkers");
        }
        if (workerFactory == null) {
            throw new IllegalArgumentException("workerFactory 不能为空");
        }
        this.minWorkers = minWorkers;
        this.maxWorkers = maxWorkers;
        this.workerFactory = workerFactory;
        for (int i = 0; i < minWorkers; i++) {
            availableWorkers.addLast(createWorker());
        }
    }

    Lease acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                ensureOpen();
                SubAgent available = availableWorkers.pollFirst();
                if (available != null) {
                    return new Lease(available);
                }
                if (allWorkers.size() < maxWorkers) {
                    return new Lease(createWorker());
                }
                workerReturned.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在批次边界关闭多余的空闲 Worker，避免步骤完成顺序导致池容量抖动。
     */
    void trimToMinimum() {
        List<SubAgent> removed = new ArrayList<>();
        lock.lock();
        try {
            trimRequested = true;
            while (!closed && allWorkers.size() > minWorkers && !availableWorkers.isEmpty()) {
                SubAgent worker = availableWorkers.removeLast();
                allWorkers.remove(worker);
                removed.add(worker);
            }
            if (allWorkers.size() <= minWorkers) {
                trimRequested = false;
            }
        } finally {
            lock.unlock();
        }
        removed.forEach(this::closeSafely);
    }

    int maxWorkers() {
        return maxWorkers;
    }

    Stats stats() {
        lock.lock();
        try {
            int total = allWorkers.size();
            int idle = availableWorkers.size();
            return new Stats(total, idle, total - idle, minWorkers, maxWorkers, closed);
        } finally {
            lock.unlock();
        }
    }

    void forEachWorker(Consumer<SubAgent> action) {
        if (action == null) {
            return;
        }
        List<SubAgent> snapshot;
        lock.lock();
        try {
            snapshot = new ArrayList<>(allWorkers);
        } finally {
            lock.unlock();
        }
        snapshot.forEach(action);
    }

    @Override
    public void close() {
        List<SubAgent> idleWorkers;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            idleWorkers = new ArrayList<>(availableWorkers);
            availableWorkers.clear();
            allWorkers.removeAll(idleWorkers);
            workerReturned.signalAll();
        } finally {
            lock.unlock();
        }
        idleWorkers.forEach(this::closeSafely);
    }

    private SubAgent createWorker() {
        SubAgent worker = workerFactory.apply(nextWorkerNumber++);
        if (worker == null) {
            throw new IllegalStateException("workerFactory 返回了 null");
        }
        allWorkers.add(worker);
        return worker;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WorkerPool 已关闭");
        }
    }

    private void release(SubAgent worker) {
        boolean reusable = resetSafely(worker);
        boolean shouldClose;
        lock.lock();
        try {
            if (!allWorkers.contains(worker)) {
                return;
            }
            shouldClose = closed || !reusable || (trimRequested && allWorkers.size() > minWorkers);
            if (shouldClose) {
                allWorkers.remove(worker);
            } else {
                availableWorkers.addLast(worker);
            }
            if (trimRequested && allWorkers.size() <= minWorkers) {
                trimRequested = false;
            }
            workerReturned.signalAll();
        } finally {
            lock.unlock();
        }
        if (shouldClose) {
            closeSafely(worker);
        }
    }

    private boolean resetSafely(SubAgent worker) {
        try {
            worker.clearHistory();
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to reset worker {}, removing it from the pool", worker.getName(), e);
            return false;
        }
    }

    private void closeSafely(SubAgent worker) {
        try {
            worker.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close worker {}", worker.getName(), e);
        }
    }

    record Stats(int total, int idle, int busy, int min, int max, boolean closed) {
    }

    final class Lease implements AutoCloseable {
        private final SubAgent worker;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(SubAgent worker) {
            this.worker = worker;
        }

        SubAgent worker() {
            if (released.get()) {
                throw new IllegalStateException("Worker Lease 已归还");
            }
            return worker;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(worker);
            }
        }
    }
}
