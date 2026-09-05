package com.paicli.llm;

import okhttp3.Call;

/** Optional per-thread cancellation scope. Existing CLI calls are unaffected. */
public final class LlmCallCancellation implements AutoCloseable {
    private static final ThreadLocal<LlmCallCancellation> CURRENT = new ThreadLocal<>();
    private Call active;
    private boolean canceled;

    public void enter() { CURRENT.set(this); }
    public static boolean scoped() { return CURRENT.get() != null; }

    public static void register(Call call) {
        LlmCallCancellation scope = CURRENT.get();
        if (scope != null) scope.attach(call);
    }

    private synchronized void attach(Call call) {
        active = call;
        if (canceled) call.cancel();
    }

    public synchronized void cancel() {
        canceled = true;
        if (active != null) active.cancel();
    }

    @Override public void close() {
        synchronized (this) { active = null; }
        CURRENT.remove();
    }
}
