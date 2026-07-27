package com.automationstudio.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.config.RunnerHealthProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunnerEligibilityEvaluatorTest {

    private static final UUID RUNNER_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-07-27T10:00:00Z");

    private final RunnerEligibilityEvaluator evaluator = new RunnerEligibilityEvaluator(
            new RunnerHealthProperties(Duration.ofMinutes(1), Duration.ofMinutes(5)));

    @Test
    void activeOnlineRunnerWithFreeCapacityIsEligible() {
        RunnerSchedulingEligibility result = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 3, RUNNER_ID, NOW.minusMinutes(1), 1));

        assertThat(result.eligible()).isTrue();
        assertThat(result.health()).isEqualTo(RunnerHealth.ONLINE);
        assertThat(result.capacity()).isEqualTo(new RunnerCapacity(3, 1, 2));
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void disabledRunnerIsIneligibleWithoutChangingHealth() {
        RunnerSchedulingEligibility result = evaluator.evaluate(
                state(RunnerStatus.DISABLED, 2, RUNNER_ID, NOW, 0));

        assertThat(result.eligible()).isFalse();
        assertThat(result.health()).isEqualTo(RunnerHealth.ONLINE);
        assertThat(result.failures())
                .containsExactly(RunnerEligibilityFailure.RUNNER_NOT_ACTIVE);
    }

    @Test
    void missingAndMismatchingRuntimeFailClosed() {
        RunnerSchedulingEligibility missing = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 2, null, null, 0));
        RunnerSchedulingEligibility mismatch = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 2, UUID.randomUUID(), NOW, 0));

        assertThat(missing.health()).isNull();
        assertThat(missing.failures()).contains(RunnerEligibilityFailure.RUNTIME_MISSING);
        assertThat(mismatch.health()).isNull();
        assertThat(mismatch.failures())
                .contains(RunnerEligibilityFailure.RUNTIME_IDENTITY_MISMATCH);
    }

    @Test
    void exactHealthBoundariesMatchAs020Semantics() {
        RunnerSchedulingEligibility fresh = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 1, RUNNER_ID, NOW.minusMinutes(1), 0));
        RunnerSchedulingEligibility stale = evaluator.evaluate(
                state(
                        RunnerStatus.ACTIVE,
                        1,
                        RUNNER_ID,
                        NOW.minusMinutes(1).minusNanos(1),
                        0));
        RunnerSchedulingEligibility offlineBoundary = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 1, RUNNER_ID, NOW.minusMinutes(5), 0));
        RunnerSchedulingEligibility offline = evaluator.evaluate(
                state(
                        RunnerStatus.ACTIVE,
                        1,
                        RUNNER_ID,
                        NOW.minusMinutes(5).minusNanos(1),
                        0));

        assertThat(fresh.health()).isEqualTo(RunnerHealth.ONLINE);
        assertThat(stale.health()).isEqualTo(RunnerHealth.STALE);
        assertThat(stale.failures()).contains(RunnerEligibilityFailure.HEARTBEAT_STALE);
        assertThat(offlineBoundary.health()).isEqualTo(RunnerHealth.STALE);
        assertThat(offline.health()).isEqualTo(RunnerHealth.OFFLINE);
        assertThat(offline.failures()).contains(RunnerEligibilityFailure.HEARTBEAT_OFFLINE);
    }

    @Test
    void invalidConcurrencyAndOverCapacityAreDefensivelyClamped() {
        RunnerSchedulingEligibility invalid = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 0, RUNNER_ID, NOW, 0));
        RunnerSchedulingEligibility overCapacity = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 2, RUNNER_ID, NOW, 5));

        assertThat(invalid.capacity()).isEqualTo(new RunnerCapacity(0, 0, 0));
        assertThat(invalid.failures()).contains(
                RunnerEligibilityFailure.INVALID_MAX_CONCURRENCY,
                RunnerEligibilityFailure.CAPACITY_EXHAUSTED);
        assertThat(overCapacity.capacity()).isEqualTo(new RunnerCapacity(2, 5, 0));
        assertThat(overCapacity.failures())
                .contains(RunnerEligibilityFailure.CAPACITY_EXHAUSTED);
    }

    @Test
    void partialAndFullCapacityUseLeaseDerivedCounts() {
        RunnerSchedulingEligibility zero = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 2, RUNNER_ID, NOW, 0));
        RunnerSchedulingEligibility partial = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 2, RUNNER_ID, NOW, 1));
        RunnerSchedulingEligibility full = evaluator.evaluate(
                state(RunnerStatus.ACTIVE, 2, RUNNER_ID, NOW, 2));

        assertThat(zero.capacity().availableCapacity()).isEqualTo(2);
        assertThat(partial.capacity().availableCapacity()).isEqualTo(1);
        assertThat(full.capacity().availableCapacity()).isZero();
        assertThat(full.failures()).contains(RunnerEligibilityFailure.CAPACITY_EXHAUSTED);
    }

    @Test
    void malformedCapabilitiesAndLabelsFailClosed() {
        RunnerSchedulingState malformed = new RunnerSchedulingState(
                RUNNER_ID,
                "runner",
                RunnerStatus.ACTIVE,
                1,
                Map.of("engines", "not-an-object"),
                Map.of("region", 42),
                RUNNER_ID,
                NOW,
                NOW,
                0);

        RunnerSchedulingEligibility result = evaluator.evaluate(malformed);

        assertThat(result.failures()).contains(
                RunnerEligibilityFailure.MALFORMED_CAPABILITIES,
                RunnerEligibilityFailure.MALFORMED_LABELS);
    }

    @Test
    void missingRunnerHasExplicitFailureReason() {
        RunnerSchedulingEligibility result = evaluator.runnerNotFound("missing", NOW);

        assertThat(result.eligible()).isFalse();
        assertThat(result.runnerId()).isNull();
        assertThat(result.failures())
                .containsExactly(RunnerEligibilityFailure.RUNNER_NOT_FOUND);
    }

    private RunnerSchedulingState state(
            RunnerStatus status,
            int maxConcurrency,
            UUID runtimeId,
            OffsetDateTime lastSeenAt,
            long activeLeases) {
        return new RunnerSchedulingState(
                RUNNER_ID,
                "runner",
                status,
                maxConcurrency,
                Map.of("engines", Map.of("playwright-java", "1.52.0")),
                Map.of("region", "eu"),
                runtimeId,
                lastSeenAt,
                NOW,
                activeLeases);
    }
}
