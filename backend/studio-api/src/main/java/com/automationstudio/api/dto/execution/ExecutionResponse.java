package com.automationstudio.api.dto.execution;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ExecutionResponse(
        UUID id,
        UUID projectId,
        UUID environmentId,
        UUID automationSuiteId,
        ExecutionSelectionMode selectionMode,
        ExecutionStatus status,
        String requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Integer totalTests,
        Integer passedTests,
        Integer failedTests,
        Integer skippedTests,
        Long durationMs,
        String errorMessage,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
