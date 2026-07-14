package com.automationstudio.api.dto;

import com.automationstudio.api.domain.ExecutionStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ExecutionSummaryResponse(
        UUID id,
        UUID projectId,
        UUID environmentId,
        UUID testSuiteId,
        ExecutionStatus status,
        String requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime finishedAt,
        Integer totalTests,
        Integer passedTests,
        Integer failedTests) {
}
