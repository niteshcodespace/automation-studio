package com.automationstudio.api.service.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RenewedExecutionLease(
        UUID executionId,
        String runnerId,
        long leaseGeneration,
        long leaseVersion,
        OffsetDateTime lastHeartbeatAt,
        OffsetDateTime leaseExpiresAt) {
}
