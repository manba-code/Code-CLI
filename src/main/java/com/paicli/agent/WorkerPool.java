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

        // 预热最小容量，避免第一个并行批次把 Worker 创建成本放到任务执行关键路径上。
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

                // 从空闲队列移除即代表独占租出；归还前该 Worker 不会再次出现在队列中。
                SubAgent available = availableWorkers.pollFirst();
                if (available != null) {
                    return new Lease(available);
                }

                // 池未达到上限时直接扩容；达到上限后只能等待其他 Lease 归还。
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
            // 即使当前没有空闲 Worker，也保留缩容意图，让仍在执行的 Worker 在归还时完成缩容。
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

        // close() 会清空 Agent 私有上下文，放在锁外执行以缩短池锁持有时间。
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

            // 先发布关闭状态并唤醒等待者；等待者会在 ensureOpen() 处得到明确失败。
            closed = true;
            idleWorkers = new ArrayList<>(availableWorkers);
            availableWorkers.clear();

            // 已租出的 Worker 仍保留在 allWorkers，等对应 Lease 归还后由 release() 关闭。
            allWorkers.removeAll(idleWorkers);
            workerReturned.signalAll();
        } finally {
            lock.unlock();
        }

        // 不在池锁内执行 SubAgent.close()，避免清理逻辑阻塞租借/归还状态更新。
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
        // 先清除上一个步骤的消息、工具结果等任务上下文；重置失败的实例不能安全复用。
        boolean reusable = resetSafely(worker);
        boolean shouldClose;
        lock.lock();
        try {
            if (!allWorkers.contains(worker)) {
                return;
            }

            // 关闭池、重置失败或待缩容三种情况都不再让 Worker 回到空闲队列。
            shouldClose = closed || !reusable || (trimRequested && allWorkers.size() > minWorkers);
            if (shouldClose) {
                allWorkers.remove(worker);
            } else {
                availableWorkers.addLast(worker);
            }
            if (trimRequested && allWorkers.size() <= minWorkers) {
                trimRequested = false;
            }

            // 无论本次是复用还是关闭，都可能让等待 acquire() 的线程重新判断池状态。
            workerReturned.signalAll();
        } finally {
            lock.unlock();
        }

        // 与缩容路径一致，真正关闭实例放到锁外执行。
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
            // try-with-resources 和异常兜底可能重复调用 close；CAS 保证只归还一次。
            if (released.compareAndSet(false, true)) {
                release(worker);
            }
        }
    }
}
