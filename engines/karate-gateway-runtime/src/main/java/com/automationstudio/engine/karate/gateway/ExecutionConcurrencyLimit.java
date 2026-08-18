package com.automationstudio.engine.karate.gateway;

import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Execution-scoped guard sharing the provider's absolute execution deadline. */
final class ExecutionConcurrencyLimit {
    private final Semaphore permits;

    ExecutionConcurrencyLimit(int parallelism) {
        if (parallelism < 1 || parallelism > 8) throw new IllegalArgumentException("Invalid parallelism");
        permits = new Semaphore(parallelism, true);
    }

    Permit acquire(long deadline) {
        long remaining = deadline - Instant.now().toEpochMilli();
        if (remaining <= 0) throw failure();
        try {
            if (!permits.tryAcquire(remaining, TimeUnit.MILLISECONDS)) throw failure();
            return new Permit(permits);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure();
        }
    }

    private static GatewayTargetPolicy.GatewayFailure failure() {
        return new GatewayTargetPolicy.GatewayFailure("GATEWAY_TIMEOUT");
    }

    static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private boolean closed;
        private Permit(Semaphore permits) { this.permits = permits; }
        @Override public void close() { if (!closed) { closed = true; permits.release(); } }
    }
}
