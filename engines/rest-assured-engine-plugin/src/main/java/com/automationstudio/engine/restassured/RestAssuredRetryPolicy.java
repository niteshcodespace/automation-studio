package com.automationstudio.engine.restassured;

import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Provider-local bounded backoff sharing one monotonic request deadline. */
final class RestAssuredRetryPolicy {
    static final Duration MAX_REQUEST_DURATION = Duration.ofSeconds(120);

    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    RestAssuredRetryPolicy() { this(System::nanoTime, Thread::sleep); }
    RestAssuredRetryPolicy(LongSupplier nanoTime, Sleeper sleeper) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    long start() { return nanoTime.getAsLong(); }

    void beforeRetry(int baseMillis, int attempt, long startedAtNanos) {
        long delayMillis = Math.min(RestAssuredApiManifest.MAX_BACKOFF_MILLIS,
                (long) baseMillis * (1L << Math.min(attempt - 1, 3)));
        long elapsed = Math.max(0, nanoTime.getAsLong() - startedAtNanos);
        long remaining = MAX_REQUEST_DURATION.toNanos() - elapsed;
        if (remaining <= 0 || Duration.ofMillis(delayMillis).toNanos() >= remaining) {
            throw failure("RETRY_DEADLINE_EXCEEDED");
        }
        try { sleeper.sleep(delayMillis); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("RETRY_INTERRUPTED");
        }
    }

    private RestAssuredEngineException failure(String code) {
        return new RestAssuredEngineException(code, "REST Assured retry failed");
    }
}
