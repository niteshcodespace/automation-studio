package com.automationstudio.api.dto.automation.suite;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.SuiteType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AutomationSuiteResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        String engineType,
        String suiteReference,
        String sourceLocation,
        String engineId,
        SuiteType suiteType,
        Map<String, Object> configuration,
        AutomationSuiteStatus status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public AutomationSuiteResponse(
            UUID id,
            UUID projectId,
            String name,
            String description,
            String engineType,
            String suiteReference,
            String engineId,
            SuiteType suiteType,
            Map<String, Object> configuration,
            AutomationSuiteStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this(id, projectId, name, description, engineType, suiteReference, null,
                engineId, suiteType, configuration, status, version, createdAt, updatedAt);
    }
}
