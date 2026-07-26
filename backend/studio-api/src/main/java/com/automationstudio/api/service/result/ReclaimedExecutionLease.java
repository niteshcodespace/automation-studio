package com.automationstudio.api.service.result;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ReclaimedExecutionLease(
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
        OffsetDateTime lastHeartbeatAt,
        OffsetDateTime leaseExpiresAt,
        Map<String, Object> environmentSnapshot,
        Map<String, Object> suiteSnapshot,
        Map<String, Object> requestSnapshot) {

    public ReclaimedExecutionLease {
        environmentSnapshot = immutableCopy(environmentSnapshot);
        suiteSnapshot = immutableCopy(suiteSnapshot);
        requestSnapshot = immutableCopy(requestSnapshot);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> snapshot) {
        return snapshot == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }
}
