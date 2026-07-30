package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.preparation.SourcePreparationRequest;
import java.util.UUID;

public record ExecutionOrchestrationRequest(
        ExecutionContext context,
        SourcePreparationRequest preparationRequest) {

    public ExecutionOrchestrationRequest {
        if (context == null || preparationRequest == null) {
            throw new ExecutionOrchestrationException(
                    "INVALID_EXECUTION_REQUEST",
                    "Execution request is incomplete");
        }
        if (!context.executionId().equals(preparationRequest.executionId())) {
            throw new ExecutionOrchestrationException(
                    "INVALID_EXECUTION_REQUEST",
                    "Execution request contains inconsistent identity");
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
