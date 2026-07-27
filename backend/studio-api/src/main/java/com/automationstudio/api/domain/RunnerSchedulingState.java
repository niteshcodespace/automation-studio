package com.automationstudio.api.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RunnerSchedulingState(
        UUID runnerId,
        String runnerKey,
        RunnerStatus status,
        int maxConcurrency,
        Map<String, Object> capabilities,
        Map<String, Object> labels,
        UUID runtimeRunnerId,
        OffsetDateTime lastSeenAt,
        OffsetDateTime evaluatedAt,
        long activeLeaseCount) {
}
