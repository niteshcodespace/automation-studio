package com.automationstudio.api.dto.execution;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ExecutionResponse(
        UUID id,
        UUID projectId,
        UUID environmentId,
        UUID automationSuiteId,
        Map<String, Object> sourceSnapshot,
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
        OffsetDateTime cancelRequestedAt,
        OffsetDateTime cancelledAt,
        String cancelledBy,
        String cancellationReason,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ExecutionResponse(
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
            OffsetDateTime cancelRequestedAt,
            OffsetDateTime cancelledAt,
            String cancelledBy,
            String cancellationReason,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this(id, projectId, environmentId, automationSuiteId, null, selectionMode, status,
                requestedBy, requestedAt, startedAt, finishedAt, totalTests, passedTests,
                failedTests, skippedTests, durationMs, errorMessage, cancelRequestedAt,
                cancelledAt, cancelledBy, cancellationReason, version, createdAt, updatedAt);
    }
}
