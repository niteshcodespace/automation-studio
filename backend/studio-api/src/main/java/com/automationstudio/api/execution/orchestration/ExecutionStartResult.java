package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionStartResult(
        UUID executionId,
        ExecutionStatus status,
        long executionVersion,
        long leaseGeneration,
        long leaseVersion,
        OffsetDateTime startedAt,
        ExecutionContext context,
        ExecutionEngineDescriptor engineDescriptor,
        Map<String, Object> sourceSnapshot)
        implements RunnerExecutionResult {

    public ExecutionStartResult {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        status = Objects.requireNonNull(status, "Execution status must not be null");
        startedAt = Objects.requireNonNull(startedAt, "Execution start time must not be null");
        context = Objects.requireNonNull(context, "Execution context must not be null");
        engineDescriptor = Objects.requireNonNull(
                engineDescriptor, "Execution engine descriptor must not be null");
        sourceSnapshot = sourceSnapshot == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceSnapshot));
    }

    public ExecutionStartResult(
            UUID executionId,
            ExecutionStatus status,
            long executionVersion,
            long leaseGeneration,
            long leaseVersion,
            OffsetDateTime startedAt,
            ExecutionContext context,
            ExecutionEngineDescriptor engineDescriptor) {
        this(
                executionId,
                status,
                executionVersion,
                leaseGeneration,
                leaseVersion,
                startedAt,
                context,
                engineDescriptor,
                null);
    }
}
