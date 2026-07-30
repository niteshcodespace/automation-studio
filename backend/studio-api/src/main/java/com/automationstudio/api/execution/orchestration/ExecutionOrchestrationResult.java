package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.execution.engine.EngineExecutionResult;
import java.time.OffsetDateTime;
import java.util.Objects;

public record ExecutionOrchestrationResult(
        EngineExecutionResult engineResult,
        OffsetDateTime completedAt) {

    public ExecutionOrchestrationResult {
        engineResult = Objects.requireNonNull(
                engineResult, "Engine execution result must not be null");
        completedAt = Objects.requireNonNull(
                completedAt, "Orchestration completion time must not be null");
    }
}
