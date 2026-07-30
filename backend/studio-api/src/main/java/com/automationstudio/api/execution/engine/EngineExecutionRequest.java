package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import java.util.Objects;
import java.util.UUID;

public record EngineExecutionRequest(
        ExecutionContext context,
        SourcePreparationResult preparation) {

    public EngineExecutionRequest {
        context = Objects.requireNonNull(context, "Execution context must not be null");
        preparation = Objects.requireNonNull(
                preparation, "Source preparation result must not be null");
        if (preparation.state() != SourcePreparationState.PREPARED) {
            throw new IllegalArgumentException("Source preparation must be complete");
        }
        if (!context.executionId().equals(preparation.executionId())) {
            throw new IllegalArgumentException(
                    "Execution context and preparation must identify the same execution");
        }
    }

    public UUID executionId() {
        return context.executionId();
    }

    public String engineName() {
        return context.suite().engineId();
    }

    public String engineVersion() {
        return context.suite().engineVersion();
    }
}
