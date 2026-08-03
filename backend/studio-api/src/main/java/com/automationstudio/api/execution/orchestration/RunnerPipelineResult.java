package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.execution.ExecutionContext;
import java.time.OffsetDateTime;
import java.util.Objects;

public record RunnerPipelineResult(
        ExecutionCompletionResult completion,
        ExecutionContext context,
        OffsetDateTime startedAt) {

    public RunnerPipelineResult {
        completion = Objects.requireNonNull(completion, "Completion must not be null");
        context = Objects.requireNonNull(context, "Execution context must not be null");
        startedAt = Objects.requireNonNull(startedAt, "Execution start time must not be null");
    }
}
