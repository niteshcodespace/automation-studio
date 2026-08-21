package com.automationstudio.api.execution.orchestration;

import com.automationstudio.engine.sdk.ExecutionControl;
import com.automationstudio.engine.sdk.ExecutionTeardown;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class PlatformExecutionControl implements ExecutionControl {
    private final Instant deadline;
    private final long startedTick;
    private final long budgetNanos;
    private final LongSupplier ticker;
    private final Supplier<ExecutionCancellationObservation> cancellation;
    private final AtomicReference<ExecutionCancellationObservation> lastCancellationObservation =
            new AtomicReference<>();
    private final AtomicReference<ExecutionTeardown> teardown = new AtomicReference<>();
    private final AtomicReference<Duration> lastRemaining;
    private final AtomicBoolean teardownDemanded = new AtomicBoolean();
    private final AtomicBoolean teardownStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> teardownCompletion = new CompletableFuture<>();

    PlatformExecutionControl(Duration timeout, Clock clock, LongSupplier ticker,
            Supplier<ExecutionCancellationObservation> cancellation) {
        Objects.requireNonNull(timeout, "Execution timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        this.ticker = Objects.requireNonNull(ticker, "Monotonic ticker must not be null");
        this.cancellation = Objects.requireNonNull(cancellation, "Cancellation probe must not be null");
        this.deadline = Objects.requireNonNull(clock, "Clock must not be null").instant().plus(timeout);
        this.startedTick = ticker.getAsLong();
        this.budgetNanos = timeout.toNanos();
        this.lastRemaining = new AtomicReference<>(timeout);
    }

    @Override public Instant deadline() { return deadline; }

    @Override
    public Duration remainingTime() {
        long elapsed = Math.max(0, ticker.getAsLong() - startedTick);
        Duration candidate = Duration.ofNanos(Math.max(0, budgetNanos - elapsed));
        return lastRemaining.updateAndGet(previous -> candidate.compareTo(previous) < 0 ? candidate : previous);
    }

    @Override public boolean isExpired() { return remainingTime().isZero(); }
    @Override public boolean cancellationRequested() { return observeCancellation().requested(); }
    @Override public boolean isBounded() { return true; }

    @Override
    public synchronized void registerTeardown(ExecutionTeardown candidate) {
        Objects.requireNonNull(candidate, "Execution teardown must not be null");
        if (closed.get()) throw new IllegalStateException("Execution control is closed");
        if (!teardown.compareAndSet(null, candidate)) {
            throw new IllegalStateException("Execution teardown is already registered");
        }
        teardownRegistration.complete(candidate);
        if (teardownDemanded.get()) startTeardown(candidate);
    }

    synchronized boolean requestTeardown() {
        teardownDemanded.set(true);
        ExecutionTeardown registered = teardown.get();
        if (registered != null) startTeardown(registered);
        return registered != null;
    }

    CompletableFuture<Void> teardownCompletion() { return teardownCompletion; }
    CompletableFuture<ExecutionTeardown> teardownRegistration() { return teardownRegistration; }
    synchronized void close() { closed.set(true); }

    ExecutionCancellationObservation observeCancellation() {
        ExecutionCancellationObservation observation = Objects.requireNonNull(
                cancellation.get(), "Cancellation observation must not be null");
        return lastCancellationObservation.updateAndGet(previous ->
                previous != null && previous.requested() ? previous : observation);
    }

    ExecutionCancellationObservation lastCancellationObservation() {
        return lastCancellationObservation.get();
    }

    private void startTeardown(ExecutionTeardown registered) {
        if (!teardownStarted.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("execution-teardown").start(() -> {
            try {
                registered.teardown();
                teardownCompletion.complete(null);
            } catch (Throwable failure) {
                teardownCompletion.completeExceptionally(failure);
            }
        });
    }

    private final CompletableFuture<ExecutionTeardown> teardownRegistration =
            new CompletableFuture<>();
}
