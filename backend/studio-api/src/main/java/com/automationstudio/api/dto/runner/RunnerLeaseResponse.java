package com.automationstudio.api.dto.runner;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RunnerLeaseResponse(
        UUID executionId,
        UUID projectId,
        UUID environmentId,
        UUID automationSuiteId,
        ExecutionSelectionMode selectionMode,
        ExecutionStatus status,
        long executionVersion,
        String runnerId,
        UUID claimToken,
        long leaseGeneration,
        long leaseVersion,
        OffsetDateTime claimedAt,
        OffsetDateTime leaseExpiresAt,
        Map<String, Object> environmentSnapshot,
        Map<String, Object> suiteSnapshot,
        Map<String, Object> requestSnapshot) {

    @Override
    public String toString() {
        return "RunnerLeaseResponse[executionId="
                + executionId
                + ", runnerId="
                + runnerId
                + ", leaseGeneration="
                + leaseGeneration
                + ", leaseVersion="
                + leaseVersion
                + "]";
    }
}
