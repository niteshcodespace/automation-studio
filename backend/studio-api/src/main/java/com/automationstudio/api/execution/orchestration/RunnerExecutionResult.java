package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.domain.ExecutionStatus;
import java.util.UUID;

public sealed interface RunnerExecutionResult
        permits ExecutionStartResult, ExecutionCompletionResult {

    UUID executionId();

    ExecutionStatus status();

    long executionVersion();

    long leaseGeneration();

    long leaseVersion();
}
