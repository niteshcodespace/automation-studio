package com.automationstudio.api.dto.runner;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RunnerResponse(
        UUID id,
        String runnerKey,
        String name,
        String description,
        String agentVersion,
        String hostname,
        String operatingSystem,
        String architecture,
        int maxConcurrency,
        Map<String, Object> capabilities,
        Map<String, Object> labels,
        RunnerStatus status,
        RunnerHealth health,
        boolean availableForDispatch,
        OffsetDateTime registeredAt,
        OffsetDateTime lastRegisteredAt,
        OffsetDateTime lastSeenAt,
        long version,
        long heartbeatVersion,
        long heartbeatCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
