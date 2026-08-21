package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import com.automationstudio.engine.sdk.PreparedSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutionSupervisorImplTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void providerCompletionWinsBeforeSignals() {
        Fixture fixture = fixture(Duration.ofSeconds(1), () -> false);
        EngineExecutionResult expected = result(fixture.request);
        assertThat(supervisor().execute(plugin(ignored -> expected), fixture.request, fixture.control))
                .isSameAs(expected);
    }

    @Test
    void deadlineWinsInvokesTeardownAndRejectsBlockedProvider() {
        AtomicLong ticks = new AtomicLong();
        Fixture fixture = fixture(Duration.ofNanos(1), () -> false, ticks);
        AtomicInteger teardown = new AtomicInteger();
        fixture.control.registerTeardown(teardown::incrementAndGet);
        ticks.set(2);
        assertThatThrownBy(() -> supervisor().execute(plugin(ignored -> {
            new CountDownLatch(1).await(); return result(fixture.request);
        }), fixture.request, fixture.control))
                .isInstanceOfSatisfying(ExecutionOrchestrationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXECUTION_DEADLINE_EXCEEDED"));
        assertThat(teardown).hasValue(1);
    }

    @Test
    void cancellationWinsAndDeadlineHasPriorityWhenBothEligible() {
        Fixture cancelled = fixture(Duration.ofSeconds(1), () -> true);
        AtomicInteger teardown = new AtomicInteger();
        cancelled.control.registerTeardown(teardown::incrementAndGet);
        assertThat(supervisor().execute(plugin(ignored -> {
            new CountDownLatch(1).await(); return result(cancelled.request);
        }), cancelled.request, cancelled.control).state()).isEqualTo(EngineExecutionState.CANCELLED);
        assertThat(teardown).hasValue(1);

        AtomicLong ticks = new AtomicLong();
        Fixture both = fixture(Duration.ofNanos(1), () -> true, ticks);
        ticks.set(2);
        assertThatThrownBy(() -> supervisor().execute(plugin(ignored -> result(both.request)),
                both.request, both.control))
                .isInstanceOfSatisfying(ExecutionOrchestrationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXECUTION_DEADLINE_EXCEEDED"));
    }

    @Test
    void providerAndTeardownFailuresBecomeOperationalErrors() {
        Fixture providerFailure = fixture(Duration.ofSeconds(1), () -> false);
        assertThatThrownBy(() -> supervisor().execute(plugin(ignored -> {
            throw new IllegalStateException("private");
        }), providerFailure.request, providerFailure.control)).isInstanceOf(IllegalStateException.class);

        Fixture teardownFailure = fixture(Duration.ofSeconds(1), () -> true);
        teardownFailure.control.registerTeardown(() -> { throw new IllegalStateException("private"); });
        assertThatThrownBy(() -> supervisor().execute(plugin(ignored -> result(teardownFailure.request)),
                teardownFailure.request, teardownFailure.control))
                .isInstanceOfSatisfying(ExecutionOrchestrationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXECUTION_TEARDOWN_FAILED"));
    }

    @Test
    void teardownTimeoutBecomesOperationalError() {
        Fixture fixture = fixture(Duration.ofSeconds(1), () -> true);
        fixture.control.registerTeardown(() -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        var fastSupervisor = new ExecutionSupervisorImpl(
                CLOCK, Duration.ofNanos(1), Duration.ofMillis(1));
        assertThatThrownBy(() -> fastSupervisor.execute(plugin(ignored -> result(fixture.request)),
                fixture.request, fixture.control))
                .isInstanceOfSatisfying(ExecutionOrchestrationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXECUTION_TEARDOWN_TIMEOUT"));
    }

    @Test
    void interruptionIgnoringProviderCannotDelayCancellationAndLateResultIsDiscarded()
            throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture fixture = fixture(Duration.ofSeconds(5), cancelled::get);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        var boundedSupervisor = new ExecutionSupervisorImpl(
                CLOCK, Duration.ofNanos(1), Duration.ofMillis(25));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<EngineExecutionResult> supervised = executor.submit(() -> boundedSupervisor.execute(
                    plugin(ignored -> {
                        started.countDown();
                        while (release.getCount() > 0) {
                            try { release.await(); }
                            catch (InterruptedException ignoredInterrupt) {
                                interrupted.countDown();
                            }
                        }
                        returned.countDown();
                        return result(fixture.request);
                    }), fixture.request, fixture.control));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);

            EngineExecutionResult terminal = supervised.get(250, TimeUnit.MILLISECONDS);
            assertThat(terminal.state()).isEqualTo(EngineExecutionState.CANCELLED);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(returned.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(terminal.state()).isEqualTo(EngineExecutionState.CANCELLED);
        }
    }

    @Test
    void lateProviderExceptionIsDiscardedAfterCancellation() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture fixture = fixture(Duration.ofSeconds(5), cancelled::get);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch threw = new CountDownLatch(1);
        var boundedSupervisor = new ExecutionSupervisorImpl(
                CLOCK, Duration.ofNanos(1), Duration.ofMillis(25));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<EngineExecutionResult> supervised = executor.submit(() -> boundedSupervisor.execute(
                    plugin(ignored -> {
                        started.countDown();
                        while (release.getCount() > 0) {
                            try { release.await(); } catch (InterruptedException ignoredInterrupt) { }
                        }
                        threw.countDown();
                        throw new IllegalStateException("late private failure");
                    }), fixture.request, fixture.control));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            EngineExecutionResult terminal = supervised.get(250, TimeUnit.MILLISECONDS);
            assertThat(terminal.state()).isEqualTo(EngineExecutionState.CANCELLED);
            release.countDown();
            assertThat(threw.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(terminal.state()).isEqualTo(EngineExecutionState.CANCELLED);
        }
    }

    @Test
    void teardownRegisteredDuringTerminationIsSupervisedAndFailureWins() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture success = fixture(Duration.ofSeconds(5), cancelled::get);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var boundedSupervisor = new ExecutionSupervisorImpl(
                CLOCK, Duration.ofNanos(1), Duration.ofMillis(100));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<EngineExecutionResult> supervised = executor.submit(() -> boundedSupervisor.execute(
                    plugin(request -> {
                        started.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException interrupted) {
                            request.executionControl().registerTeardown(calls::incrementAndGet);
                        }
                        return result(request);
                    }), success.request, success.control));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThat(supervised.get(1, TimeUnit.SECONDS).state())
                    .isEqualTo(EngineExecutionState.CANCELLED);
            assertThat(calls).hasValue(1);
        }

        AtomicBoolean cancelledFailure = new AtomicBoolean();
        Fixture failure = fixture(Duration.ofSeconds(5), cancelledFailure::get);
        CountDownLatch failureStarted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<EngineExecutionResult> supervised = executor.submit(() -> boundedSupervisor.execute(
                    plugin(request -> {
                        failureStarted.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException interrupted) {
                            request.executionControl().registerTeardown(
                                    () -> { throw new IllegalStateException("private"); });
                        }
                        return result(request);
                    }), failure.request, failure.control));
            assertThat(failureStarted.await(1, TimeUnit.SECONDS)).isTrue();
            cancelledFailure.set(true);
            assertThatThrownBy(() -> supervised.get(1, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ExecutionOrchestrationException.class)
                    .rootCause()
                    .extracting(f -> ((ExecutionOrchestrationException) f).code())
                    .isEqualTo("EXECUTION_TEARDOWN_FAILED");
        }
    }

    @Test
    void missingRegistrationReturnsBoundedlyAndRegistrationAfterCloseIsRejected() {
        Fixture fixture = fixture(Duration.ofSeconds(1), () -> true);
        var boundedSupervisor = new ExecutionSupervisorImpl(
                CLOCK, Duration.ofNanos(1), Duration.ofMillis(5));

        assertThat(boundedSupervisor.execute(plugin(ignored -> result(fixture.request)),
                fixture.request, fixture.control).state()).isEqualTo(EngineExecutionState.CANCELLED);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> fixture.control.registerTeardown(calls::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(calls).hasValue(0);
    }

    @Test
    void teardownRegisteredDuringTerminationStillTimesOutWithinSharedBound() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture fixture = fixture(Duration.ofSeconds(5), cancelled::get);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch teardownStarted = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        var boundedSupervisor = new ExecutionSupervisorImpl(
                CLOCK, Duration.ofNanos(1), Duration.ofMillis(25));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<EngineExecutionResult> supervised = executor.submit(() -> boundedSupervisor.execute(
                    plugin(request -> {
                        providerStarted.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException interrupted) {
                            request.executionControl().registerTeardown(() -> {
                                teardownStarted.countDown();
                                try { releaseTeardown.await(); }
                                catch (InterruptedException ignoredInterrupt) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                        }
                        return result(request);
                    }), fixture.request, fixture.control));
            assertThat(providerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThatThrownBy(() -> supervised.get(1, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ExecutionOrchestrationException.class)
                    .rootCause()
                    .extracting(f -> ((ExecutionOrchestrationException) f).code())
                    .isEqualTo("EXECUTION_TEARDOWN_TIMEOUT");
            assertThat(teardownStarted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseTeardown.countDown();
        }
    }

    private static ExecutionSupervisorImpl supervisor() {
        return new ExecutionSupervisorImpl(CLOCK, Duration.ofNanos(1), Duration.ofMillis(100));
    }

    private static Fixture fixture(Duration timeout, java.util.function.BooleanSupplier cancellation) {
        return fixture(timeout, cancellation, new AtomicLong());
    }

    private static Fixture fixture(Duration timeout, java.util.function.BooleanSupplier cancellation,
            AtomicLong ticks) {
        UUID id = UUID.randomUUID();
        EngineExecutionContext context = new EngineExecutionContext(id, new EngineIdentity("test", "1"),
                "suite", Map.of(), "https://example.test", Map.of(), Map.of());
        PreparedSource source = new PreparedSource(new com.automationstudio.engine.sdk.WorkspaceId(id), "GIT", "rev");
        var workspace = mock(com.automationstudio.engine.sdk.WorkspaceAccess.class);
        var secrets = mock(com.automationstudio.engine.sdk.ExecutionSecretAccess.class);
        when(workspace.executionId()).thenReturn(id); when(workspace.workspaceId()).thenReturn(source.workspaceId());
        when(secrets.executionId()).thenReturn(id);
        PlatformExecutionControl control = new PlatformExecutionControl(timeout, CLOCK, ticks::get,
                () -> new ExecutionCancellationObservation(cancellation.getAsBoolean(), 7));
        EngineExecutionRequest request = new EngineExecutionRequest(context, source, workspace, secrets,
                com.automationstudio.engine.sdk.ArtifactPublisher.unavailable(id), control);
        return new Fixture(request, control);
    }

    private static EngineExecutionResult result(EngineExecutionRequest request) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new EngineExecutionResult(request.executionId(), "test", "1", request.preparedSource().workspaceId(),
                "rev", EngineExecutionState.SUCCEEDED, now, now, Duration.ZERO);
    }

    private static ExecutionEnginePlugin plugin(ThrowingExecution execution) {
        return new ExecutionEnginePlugin() {
            public ExecutionEngineDescriptor descriptor() { return new ExecutionEngineDescriptor("test", "1", "Test", Set.of(), Set.of()); }
            public void validate(EngineExecutionContext context) { }
            public EngineExecutionResult execute(EngineExecutionRequest request) {
                try { return execution.execute(request); }
                catch (RuntimeException failure) { throw failure; }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }
        };
    }

    @FunctionalInterface private interface ThrowingExecution { EngineExecutionResult execute(EngineExecutionRequest request) throws Exception; }
    private record Fixture(EngineExecutionRequest request, PlatformExecutionControl control) { }
}
