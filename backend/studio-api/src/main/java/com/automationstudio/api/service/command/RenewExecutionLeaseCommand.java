package com.automationstudio.api.service.command;

import java.time.Duration;
import java.util.UUID;

public record RenewExecutionLeaseCommand(
        UUID executionId,
        String runnerId,
        UUID claimToken,
        long leaseGeneration,
        long expectedLeaseVersion,
        Duration leaseDuration) {
}
