package com.automationstudio.api.execution;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ExecutionMetadata(
        UUID correlationId,
        OffsetDateTime createdAt,
        OffsetDateTime claimedAt,
        Duration timeout,
        ExecutionRetryPolicy retryPolicy) {

    public ExecutionMetadata {
        correlationId = Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        createdAt = Objects.requireNonNull(createdAt, "Execution creation time must not be null");
        claimedAt = Objects.requireNonNull(claimedAt, "Execution claim time must not be null");
        timeout = Objects.requireNonNull(timeout, "Execution timeout must not be null");
        retryPolicy = Objects.requireNonNull(retryPolicy, "Retry policy must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new InvalidExecutionContextException("Execution timeout must be positive");
        }
    }
}
