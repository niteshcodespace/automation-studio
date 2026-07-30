package com.automationstudio.api.execution.orchestration;

import java.util.UUID;

public record RunnerExecutionRequest(
        UUID executionId,
        String runnerId,
        UUID claimToken,
        long leaseGeneration,
        long expectedLeaseVersion,
        long expectedExecutionVersion) {

    public RunnerExecutionRequest {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (runnerId == null || runnerId.isBlank()) {
            throw new IllegalArgumentException("Runner ID must not be blank");
        }
        runnerId = runnerId.trim();
        if (runnerId.length() > 150) {
            throw new IllegalArgumentException("Runner ID must not exceed 150 characters");
        }
        if (claimToken == null) {
            throw new IllegalArgumentException("Claim token must not be null");
        }
        if (leaseGeneration <= 0) {
            throw new IllegalArgumentException("Lease generation must be positive");
        }
        if (expectedLeaseVersion < 0) {
            throw new IllegalArgumentException("Expected lease version must not be negative");
        }
        if (expectedExecutionVersion < 0) {
            throw new IllegalArgumentException("Expected execution version must not be negative");
        }
    }
}
