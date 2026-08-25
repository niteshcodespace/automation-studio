package com.automationstudio.engine.selenium;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

final class ContainmentDeadline {
    private final long deadlineNanos;
    private final LongSupplier ticker;
    private ContainmentDeadline(long deadlineNanos, LongSupplier ticker) {
        this.deadlineNanos = deadlineNanos; this.ticker = Objects.requireNonNull(ticker);
    }
    static ContainmentDeadline after(Duration budget) { return after(budget, System::nanoTime); }
    static ContainmentDeadline after(Duration budget, LongSupplier ticker) {
        if (budget == null || budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Invalid deadline");
        long now=ticker.getAsLong(); return new ContainmentDeadline(Math.addExact(now,budget.toNanos()),ticker);
    }
    long remainingNanos() { return Math.max(0,deadlineNanos-ticker.getAsLong()); }
    long remainingNanos(Duration reserve) { return Math.max(0,remainingNanos()-reserve.toNanos()); }
    Duration remaining() { return Duration.ofNanos(remainingNanos()); }
    boolean expired() { return remainingNanos()==0; }
    ContainmentDeadline cappedAfter(Duration budget) {
        long now=ticker.getAsLong();
        long cap=Math.addExact(now,Math.min(remainingNanos(),budget.toNanos()));
        return new ContainmentDeadline(Math.min(deadlineNanos,cap),ticker);
    }
    long tick() { return deadlineNanos; }
}
