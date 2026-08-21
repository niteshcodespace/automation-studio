package com.automationstudio.api.execution.orchestration;

import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

public final class ExecutionSupervisorImpl implements ExecutionSupervisor {
    static final Duration CANCELLATION_POLL_INTERVAL = Duration.ofMillis(10);
    static final Duration TEARDOWN_TIMEOUT = Duration.ofSeconds(2);
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration teardownTimeout;

    public ExecutionSupervisorImpl(Clock clock) {
        this(clock, CANCELLATION_POLL_INTERVAL, TEARDOWN_TIMEOUT);
    }

    ExecutionSupervisorImpl(Clock clock, Duration pollInterval, Duration teardownTimeout) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "Poll interval must not be null");
        this.teardownTimeout = Objects.requireNonNull(teardownTimeout, "Teardown timeout must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()
                || teardownTimeout.isZero() || teardownTimeout.isNegative()) {
            throw new IllegalArgumentException("Supervisor bounds must be positive");
        }
    }

    @Override
    public EngineExecutionResult execute(ExecutionEnginePlugin plugin, EngineExecutionRequest request,
            PlatformExecutionControl control) {
        Objects.requireNonNull(plugin, "Execution engine must not be null");
        Objects.requireNonNull(request, "Engine execution request must not be null");
        if (control == null || !control.isBounded()) {
            throw failure("EXECUTION_CONTROL_REQUIRED", "Bounded execution control is required", null);
        }
        var completion = new CompletableFuture<EngineExecutionResult>();
        Thread provider = Thread.ofVirtual().name("engine-execution-" + request.executionId()).start(() -> {
            try { completion.complete(plugin.execute(request)); }
            catch (Throwable failure) { completion.completeExceptionally(failure); }
        });

        while (true) {
            if (control.isExpired()) {
                return terminate(request, control, provider, "EXECUTION_DEADLINE_EXCEEDED",
                        "Execution deadline was exceeded", EngineExecutionState.SUCCEEDED);
            }
            boolean cancellationRequested;
            try {
                cancellationRequested = control.cancellationRequested();
            } catch (RuntimeException observationFailure) {
                return terminate(request, control, provider,
                        "EXECUTION_CANCELLATION_OBSERVATION_FAILED",
                        "Execution cancellation could not be observed", EngineExecutionState.SUCCEEDED);
            }
            if (cancellationRequested) {
                return terminate(request, control, provider, null, null, EngineExecutionState.CANCELLED);
            }
            if (completion.isDone()) {
                if (control.isExpired()) continue;
                try {
                    if (control.cancellationRequested()) continue;
                } catch (RuntimeException observationFailure) {
                    return terminate(request, control, provider,
                            "EXECUTION_CANCELLATION_OBSERVATION_FAILED",
                            "Execution cancellation could not be observed", EngineExecutionState.SUCCEEDED);
                }
                try {
                    EngineExecutionResult result = completion.join();
                    control.close();
                    return result;
                } catch (java.util.concurrent.CompletionException failure) {
                    control.close();
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw failure("ENGINE_EXECUTION_FAILED", "Execution engine failed", cause);
                }
            }
            long pause = Math.min(pollInterval.toNanos(),
                    Math.max(1, control.remainingTime().toNanos()));
            LockSupport.parkNanos(pause);
        }
    }

    private EngineExecutionResult terminate(EngineExecutionRequest request,
            PlatformExecutionControl control, Thread provider, String code, String message,
            EngineExecutionState state) {
        long teardownStarted = System.nanoTime();
        control.requestTeardown();
        provider.interrupt();
        superviseTeardown(control, teardownStarted);
        if (code != null) throw failure(code, message, null);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new EngineExecutionResult(request.executionId(), request.context().engineIdentity().engineId(),
                request.context().engineIdentity().implementationVersion(),
                request.preparedSource().workspaceId(), request.preparedSource().resolvedRevision(),
                state, now, now, Duration.ZERO);
    }

    private void superviseTeardown(PlatformExecutionControl control, long startedTick) {
        long budgetNanos = teardownTimeout.toNanos();
        try {
            try {
                control.teardownRegistration().get(
                        remainingNanos(startedTick, budgetNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException noRegistration) {
                control.close();
                if (!control.teardownRegistration().isDone()) return;
            }
            control.close();
            control.teardownCompletion().get(
                    remainingNanos(startedTick, budgetNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            control.close();
            throw failure("EXECUTION_TEARDOWN_TIMEOUT", "Execution teardown timed out", null);
        } catch (ExecutionException teardownFailure) {
            control.close();
            throw failure("EXECUTION_TEARDOWN_FAILED", "Execution teardown failed", null);
        } catch (InterruptedException interrupted) {
            control.close();
            Thread.currentThread().interrupt();
            throw failure("EXECUTION_TEARDOWN_INTERRUPTED",
                    "Execution teardown supervision was interrupted", null);
        }
    }

    private static long remainingNanos(long startedTick, long budgetNanos)
            throws TimeoutException {
        long elapsed = Math.max(0, System.nanoTime() - startedTick);
        long remaining = budgetNanos - elapsed;
        if (remaining <= 0) throw new TimeoutException("Teardown supervision bound elapsed");
        return remaining;
    }

    private static ExecutionOrchestrationException failure(String code, String message, Throwable cause) {
        return cause == null ? new ExecutionOrchestrationException(code, message)
                : new ExecutionOrchestrationException(code, message, cause);
    }
}
