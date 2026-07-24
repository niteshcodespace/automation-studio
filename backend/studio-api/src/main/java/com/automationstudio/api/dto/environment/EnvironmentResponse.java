package com.automationstudio.api.dto.environment;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record EnvironmentResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        String baseUrl,
        EnvironmentType type,
        Map<String, Object> configuration,
        Map<String, Object> secretReferences,
        EnvironmentStatus status,
        boolean isDefault,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
