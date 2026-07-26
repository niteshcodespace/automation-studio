package com.automationstudio.api.dto.runner;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RunnerHeartbeatResponse(
        UUID executionId,
        long leaseGeneration,
        long leaseVersion,
        OffsetDateTime lastHeartbeatAt,
        OffsetDateTime leaseExpiresAt) {
}
