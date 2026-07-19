package com.automationstudio.api.dto.automation.testcase;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AutomationTestCaseResponse(
        UUID id,
        UUID automationSuiteId,
        String name,
        String description,
        String caseReference,
        Map<String, Object> configuration,
        AutomationTestCaseStatus status,
        Integer position,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
