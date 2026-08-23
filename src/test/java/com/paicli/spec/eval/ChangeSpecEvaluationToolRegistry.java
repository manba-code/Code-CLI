package com.paicli.spec.eval;

import com.paicli.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 只用于付费评测的 ToolRegistry 包装层。
 *
 * <p>按 Agent 实际等待的工具批次墙钟累计，而不是把并行工具的单项耗时相加。</p>
 */
final class ChangeSpecEvaluationToolRegistry extends ToolRegistry {
    private final LongSupplier nanoTime;
    private final AtomicLong batchDurationNanos = new AtomicLong();

    ChangeSpecEvaluationToolRegistry() {
        this(System::nanoTime);
    }

    ChangeSpecEvaluationToolRegistry(LongSupplier nanoTime) {
        if (nanoTime == null) throw new IllegalArgumentException("nanoTime 不能为空");
        this.nanoTime = nanoTime;
    }

    @Override
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return super.executeTools(invocations);
        }
        long startedAt = nanoTime.getAsLong();
        try {
            return super.executeTools(invocations);
        } finally {
            long elapsed = Math.max(0L, nanoTime.getAsLong() - startedAt);
            batchDurationNanos.updateAndGet(current -> saturatingAdd(current, elapsed));
        }
    }

    long batchDurationMs() {
        return TimeUnit.NANOSECONDS.toMillis(batchDurationNanos.get());
    }

    private static long saturatingAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }
}
