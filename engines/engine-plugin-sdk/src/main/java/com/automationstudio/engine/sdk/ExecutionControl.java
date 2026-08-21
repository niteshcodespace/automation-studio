package com.automationstudio.engine.sdk;

import java.time.Duration;
import java.time.Instant;

/** Provider observation of platform-owned deadline, cancellation, and teardown authority. */
public interface ExecutionControl {

    Instant deadline();

    Duration remainingTime();

    boolean isExpired();

    boolean cancellationRequested();

    void registerTeardown(ExecutionTeardown teardown);

    boolean isBounded();

    static ExecutionControl unavailable() {
        return UnavailableExecutionControl.INSTANCE;
    }

    final class UnavailableExecutionControl implements ExecutionControl {
        private static final UnavailableExecutionControl INSTANCE = new UnavailableExecutionControl();

        private UnavailableExecutionControl() {
        }

        @Override public Instant deadline() {
            throw new IllegalStateException("Execution control is unavailable");
        }
        @Override public Duration remainingTime() { return Duration.ZERO; }
        @Override public boolean isExpired() { return false; }
        @Override public boolean cancellationRequested() { return false; }
        @Override public void registerTeardown(ExecutionTeardown teardown) {
            throw new IllegalStateException("Execution control is unavailable");
        }
        @Override public boolean isBounded() { return false; }
        @Override public String toString() { return "ExecutionControl[UNAVAILABLE]"; }
    }
}
