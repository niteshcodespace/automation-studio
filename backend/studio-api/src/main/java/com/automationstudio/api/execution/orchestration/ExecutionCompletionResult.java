package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.domain.ExecutionStatus;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ExecutionCompletionResult(
        UUID executionId,
        ExecutionStatus status,
        long executionVersion,
        long leaseGeneration,
        long leaseVersion,
        OffsetDateTime preparedAt)
        implements RunnerExecutionResult {

    public ExecutionCompletionResult {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        status = Objects.requireNonNull(status, "Execution status must not be null");
        preparedAt = Objects.requireNonNull(
                preparedAt, "Completion preparation time must not be null");
    }
}
