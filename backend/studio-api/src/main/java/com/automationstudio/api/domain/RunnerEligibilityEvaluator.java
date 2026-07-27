package com.automationstudio.api.domain;

import com.automationstudio.api.config.RunnerHealthProperties;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class RunnerEligibilityEvaluator {

    private final RunnerHealthProperties healthProperties;

    public RunnerEligibilityEvaluator(RunnerHealthProperties healthProperties) {
        this.healthProperties = healthProperties;
    }

    public RunnerSchedulingEligibility evaluate(RunnerSchedulingState state) {
        EnumSet<RunnerEligibilityFailure> failures =
                EnumSet.noneOf(RunnerEligibilityFailure.class);
        RunnerCapacity capacity =
                RunnerCapacity.from(state.maxConcurrency(), state.activeLeaseCount());

        if (state.status() != RunnerStatus.ACTIVE) {
            failures.add(RunnerEligibilityFailure.RUNNER_NOT_ACTIVE);
        }
        if (state.maxConcurrency() <= 0) {
            failures.add(RunnerEligibilityFailure.INVALID_MAX_CONCURRENCY);
        }
        if (capacity.availableCapacity() == 0) {
            failures.add(RunnerEligibilityFailure.CAPACITY_EXHAUSTED);
        }
        validateCapabilities(state.capabilities(), failures);
        validateLabels(state.labels(), failures);

        RunnerHealth health = evaluateRuntime(state, failures);
        return new RunnerSchedulingEligibility(
                state.runnerId(),
                state.runnerKey(),
                health,
                capacity,
                state.evaluatedAt(),
                failures);
    }

    public RunnerSchedulingEligibility runnerNotFound(
            String runnerKey, java.time.OffsetDateTime evaluatedAt) {
        return new RunnerSchedulingEligibility(
                null,
                runnerKey,
                null,
                RunnerCapacity.from(0, 0),
                evaluatedAt,
                SetHolder.RUNNER_NOT_FOUND);
    }

    private RunnerHealth evaluateRuntime(
            RunnerSchedulingState state,
            EnumSet<RunnerEligibilityFailure> failures) {
        if (state.runtimeRunnerId() == null || state.lastSeenAt() == null) {
            failures.add(RunnerEligibilityFailure.RUNTIME_MISSING);
            return null;
        }
        if (!state.runtimeRunnerId().equals(state.runnerId())) {
            failures.add(RunnerEligibilityFailure.RUNTIME_IDENTITY_MISMATCH);
            return null;
        }
        Duration age = state.lastSeenAt().isAfter(state.evaluatedAt())
                ? Duration.ZERO
                : Duration.between(state.lastSeenAt(), state.evaluatedAt());
        if (age.compareTo(healthProperties.onlineThreshold()) <= 0) {
            return RunnerHealth.ONLINE;
        }
        if (age.compareTo(healthProperties.offlineThreshold()) <= 0) {
            failures.add(RunnerEligibilityFailure.HEARTBEAT_STALE);
            return RunnerHealth.STALE;
        }
        failures.add(RunnerEligibilityFailure.HEARTBEAT_OFFLINE);
        return RunnerHealth.OFFLINE;
    }

    private void validateCapabilities(
            Map<String, Object> capabilities,
            EnumSet<RunnerEligibilityFailure> failures) {
        try {
            new RunnerCapabilities(capabilities, Map.of());
        } catch (RuntimeException exception) {
            failures.add(RunnerEligibilityFailure.MALFORMED_CAPABILITIES);
        }
    }

    private void validateLabels(
            Map<String, Object> labels,
            EnumSet<RunnerEligibilityFailure> failures) {
        if (labels == null) {
            failures.add(RunnerEligibilityFailure.MALFORMED_LABELS);
            return;
        }
        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : labels.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().isBlank()
                    || !(entry.getValue() instanceof String stringValue)
                    || stringValue.isBlank()) {
                failures.add(RunnerEligibilityFailure.MALFORMED_LABELS);
                return;
            }
            validated.put(entry.getKey(), stringValue);
        }
        try {
            new RunnerCapabilities(Map.of("engines", Map.of()), validated);
        } catch (RuntimeException exception) {
            failures.add(RunnerEligibilityFailure.MALFORMED_LABELS);
        }
    }

    private static final class SetHolder {
        private static final java.util.Set<RunnerEligibilityFailure> RUNNER_NOT_FOUND =
                java.util.Set.of(RunnerEligibilityFailure.RUNNER_NOT_FOUND);

        private SetHolder() {
        }
    }
}
