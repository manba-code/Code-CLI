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

    // 以下集合和生命周期标记都由同一把锁保护。Worker 一旦从 availableWorkers 取出，
    // 就只能通过对应 Lease 归还，避免同一实例被两个并行步骤同时占用。
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

    /**
     * 独占租借一个 Worker。
     *
     * <p>优先复用空闲实例；无空闲实例且未达到上限时按需扩容；达到上限后等待其他
     * Lease 归还。调用方必须使用 try-with-resources，保证成功、异常和中断路径都会归还。</p>
     */
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
     *
     * <p>仍被 Lease 持有的 Worker 不会被并发关闭；{@code trimRequested} 会把缩容请求
     * 延迟到这些 Worker 归还时处理，直到总数回落到最小容量。</p>
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

    /**
     * 对当前池成员的快照执行配置动作。
     *
     * <p>配置动作在锁外执行，避免外部回调长时间占用池锁。之后动态创建的 Worker
     * 由编排器的工厂方法注入同一份最新配置。</p>
     */
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

    /**
     * 关闭池并唤醒等待租借的线程。
     *
     * <p>空闲 Worker 立即关闭；已租出的 Worker 保持运行，待 Lease 归还后再关闭，
     * 避免清理动作与正在进行的 LLM/工具调用并发修改同一实例。</p>
     */
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

    /**
     * 归还 Worker 时先重置任务历史，再决定复用或关闭。
     *
     * <p>历史清理失败的实例不能重新入池；池已关闭或存在缩容请求时，多余实例也会
     * 从成员集合移除并关闭。</p>
     */
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

    /**
     * Worker 的独占使用凭证。关闭操作幂等，保证重复 close 不会把同一 Worker
     * 重复放回空闲队列。
     */
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
