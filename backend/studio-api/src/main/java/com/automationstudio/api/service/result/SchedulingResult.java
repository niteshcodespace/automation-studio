package com.automationstudio.api.service.result;

import com.automationstudio.api.domain.RunnerSchedulingEligibility;
import com.automationstudio.api.domain.SchedulingOutcome;
import java.util.Objects;
import java.util.Optional;

public record SchedulingResult(
        SchedulingOutcome outcome,
        RunnerSchedulingEligibility runnerEligibility,
        ClaimedExecution claimedExecution) {

    public SchedulingResult {
        Objects.requireNonNull(outcome, "Scheduling outcome must not be null");
        if ((outcome == SchedulingOutcome.SCHEDULED) != (claimedExecution != null)) {
            throw new IllegalArgumentException(
                    "A claimed execution is required only for a scheduled outcome");
        }
    }

    public Optional<ClaimedExecution> scheduledExecution() {
        return Optional.ofNullable(claimedExecution);
    }
}
