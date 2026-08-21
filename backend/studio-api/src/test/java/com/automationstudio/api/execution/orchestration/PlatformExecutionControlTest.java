package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlatformExecutionControlTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void usesImmutableWallDeadlineAndNonIncreasingMonotonicRemainingTime() {
        AtomicLong ticks = new AtomicLong(100);
        var control = new PlatformExecutionControl(Duration.ofNanos(10), CLOCK, ticks::get,
                () -> new ExecutionCancellationObservation(false, 0));
        assertThat(control.deadline()).isEqualTo(CLOCK.instant().plusNanos(10));
        assertThat(control.remainingTime()).isEqualTo(Duration.ofNanos(10));
        ticks.set(105);
        assertThat(control.remainingTime()).isEqualTo(Duration.ofNanos(5));
        ticks.set(103);
        assertThat(control.remainingTime()).isEqualTo(Duration.ofNanos(5));
        ticks.set(111);
        assertThat(control.remainingTime()).isZero();
        assertThat(control.isExpired()).isTrue();
    }

    @Test
    void observesCancellationAndRunsPendingTeardownExactlyOnce() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        var control = new PlatformExecutionControl(Duration.ofSeconds(1), CLOCK,
                System::nanoTime,
                () -> new ExecutionCancellationObservation(cancelled.get(), 7));
        assertThat(control.cancellationRequested()).isFalse();
        cancelled.set(true);
        assertThat(control.cancellationRequested()).isTrue();
        assertThat(control.requestTeardown()).isFalse();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch invoked = new CountDownLatch(1);
        control.registerTeardown(() -> { calls.incrementAndGet(); invoked.countDown(); });
        assertThat(invoked.await(1, TimeUnit.SECONDS)).isTrue();
        control.requestTeardown();
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> control.registerTeardown(() -> {}))
                .isInstanceOf(IllegalStateException.class);
    }
}
