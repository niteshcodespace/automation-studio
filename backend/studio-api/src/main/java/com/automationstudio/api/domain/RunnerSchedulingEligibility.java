package com.automationstudio.api.domain;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record RunnerSchedulingEligibility(
        UUID runnerId,
        String runnerKey,
        RunnerHealth health,
        RunnerCapacity capacity,
        OffsetDateTime evaluatedAt,
        Set<RunnerEligibilityFailure> failures) {

    public RunnerSchedulingEligibility {
        if (capacity == null || evaluatedAt == null || failures == null) {
            throw new IllegalArgumentException(
                    "Runner scheduling eligibility fields must not be null");
        }
        failures = Set.copyOf(failures);
    }

    public boolean eligible() {
        return failures.isEmpty();
    }
}
