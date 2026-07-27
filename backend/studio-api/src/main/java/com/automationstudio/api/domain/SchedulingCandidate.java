package com.automationstudio.api.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SchedulingCandidate(
        UUID executionId,
        OffsetDateTime requestedAt,
        SchedulingRequirements requirements) {

    public SchedulingCandidate {
        if (executionId == null || requestedAt == null || requirements == null) {
            throw new IllegalArgumentException("Scheduling candidate fields must not be null");
        }
    }
}
