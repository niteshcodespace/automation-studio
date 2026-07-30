package com.automationstudio.api.execution.lifecycle;

import com.automationstudio.api.execution.evidence.ExecutionEvidence;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionResult(
        UUID executionId,
        UUID runnerId,
        ExecutionStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Duration duration,
        ExecutionTerminationReason terminationReason,
        ExecutionFailureReason failureReason,
        Map<String, String> metadata,
        ExecutionEvidence evidence) {

    public ExecutionResult(
            UUID executionId,
            UUID runnerId,
            ExecutionStatus status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Duration duration,
            ExecutionTerminationReason terminationReason,
            ExecutionFailureReason failureReason,
            Map<String, String> metadata) {
        this(
                executionId,
                runnerId,
                status,
                startedAt,
                finishedAt,
                duration,
                terminationReason,
                failureReason,
                metadata,
                ExecutionEvidence.empty(executionId, runnerId, finishedAt, duration));
    }

    public ExecutionResult {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        runnerId = Objects.requireNonNull(runnerId, "Runner ID must not be null");
        status = Objects.requireNonNull(status, "Execution status must not be null");
        startedAt = Objects.requireNonNull(startedAt, "Execution start time must not be null");
        finishedAt = Objects.requireNonNull(finishedAt, "Execution finish time must not be null");
        duration = Objects.requireNonNull(duration, "Execution duration must not be null");
        terminationReason = Objects.requireNonNull(
                terminationReason, "Execution termination reason must not be null");
        failureReason = Objects.requireNonNull(
                failureReason, "Execution failure reason must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(
                metadata, "Execution metadata must not be null"));
        evidence = Objects.requireNonNull(evidence, "Execution evidence must not be null");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "Execution finish time must not precede start time");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Execution duration must not be negative");
        }
        if (status == ExecutionStatus.SUCCEEDED
                && (terminationReason != ExecutionTerminationReason.COMPLETED
                || failureReason != ExecutionFailureReason.NONE)) {
            throw new IllegalArgumentException(
                    "Successful execution must have completed without failure");
        }
        if (status == ExecutionStatus.FAILED
                && failureReason == ExecutionFailureReason.NONE) {
            throw new IllegalArgumentException(
                    "Failed execution must have a failure reason");
        }
    }

    public ExecutionResult withEvidence(ExecutionEvidence normalizedEvidence) {
        return new ExecutionResult(
                executionId,
                runnerId,
                status,
                startedAt,
                finishedAt,
                duration,
                terminationReason,
                failureReason,
                metadata,
                normalizedEvidence);
    }
}
