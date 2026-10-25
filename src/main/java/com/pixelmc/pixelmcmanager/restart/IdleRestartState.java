package com.pixelmc.pixelmcmanager.restart;

/** Main-thread state machine. Durations use monotonic time, never server ticks. */
public final class IdleRestartState {
    public static final long EMPTY_NANOS = 15_000_000_000L;
    private boolean manual;
    private boolean dispatched;
    private long emptySince = -1;
    private String failure = "";

    public void arm() {
        if (dispatched) throw new IllegalStateException("重启请求已经提交，不能重复预约。");
        manual = true;
        resetFailure();
    }

    public boolean cancel() {
        if (dispatched) throw new IllegalStateException("重启请求已经提交，不能在模组中取消。");
        boolean existed = manual;
        manual = false;
        emptySince = -1;
        return existed;
    }

    public void playerJoined() { emptySince = -1; }
    public void resetTimer() { emptySince = -1; }
    public void resetFailure() { failure = ""; emptySince = -1; }
    public void fail(String message) { failure = message; dispatched = false; emptySince = -1; }
    public boolean manual() { return manual; }
    public boolean dispatched() { return dispatched; }
    public String failure() { return failure; }
    public long emptyMillis(long now) { return emptySince < 0 ? 0 : Math.max(0, (now - emptySince) / 1_000_000); }

    public boolean ready(long now, int players, boolean autoPending, boolean stopPlan, boolean backendReady) {
        if (players > 0 || stopPlan || (!manual && !autoPending) || !backendReady || !failure.isEmpty()) {
            emptySince = -1;
            return false;
        }
        if (dispatched) return false;
        if (emptySince < 0) emptySince = now;
        return now - emptySince >= EMPTY_NANOS;
    }

    public void markDispatched() { dispatched = true; }
}
