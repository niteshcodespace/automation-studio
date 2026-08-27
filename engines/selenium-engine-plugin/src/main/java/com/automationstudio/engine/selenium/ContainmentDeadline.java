package com.automationstudio.engine.selenium;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

final class ContainmentDeadline {
    private final long deadlineNanos;
    private final LongSupplier ticker;
    private long lastRemainingNanos;
    private ContainmentDeadline(long deadlineNanos, long budgetNanos, LongSupplier ticker) {
        this.deadlineNanos = deadlineNanos; this.lastRemainingNanos = budgetNanos;
        this.ticker = Objects.requireNonNull(ticker);
    }
    static ContainmentDeadline after(Duration budget) { return after(budget, System::nanoTime); }
    static ContainmentDeadline after(Duration budget, LongSupplier ticker) {
        if (budget == null || budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Invalid deadline");
        long nanos=budget.toNanos();long now=ticker.getAsLong();
        return new ContainmentDeadline(Math.addExact(now,nanos),nanos,ticker);
    }
    synchronized long remainingNanos() {
        long candidate=Math.max(0,deadlineNanos-ticker.getAsLong());
        lastRemainingNanos=Math.min(lastRemainingNanos,candidate);return lastRemainingNanos;
    }
    long remainingNanos(Duration reserve) { return Math.max(0,remainingNanos()-reserve.toNanos()); }
    Duration remaining() { return Duration.ofNanos(remainingNanos()); }
    boolean expired() { return remainingNanos()==0; }
    ContainmentDeadline cappedAfter(Duration budget) {
        long now=ticker.getAsLong();
        long cap=Math.addExact(now,Math.min(remainingNanos(),budget.toNanos()));
        long cappedDeadline=Math.min(deadlineNanos,cap);
        return new ContainmentDeadline(cappedDeadline,Math.max(0,cappedDeadline-now),ticker);
    }
    long tick() { return deadlineNanos; }
}
