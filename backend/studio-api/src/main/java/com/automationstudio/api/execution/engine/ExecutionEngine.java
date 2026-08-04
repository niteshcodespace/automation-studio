package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;
import com.automationstudio.api.execution.lifecycle.ExecutionStatus;

public interface ExecutionEngine {

    ExecutionEngineDescriptor descriptor();

    void validate(ExecutionContext context);

    default ExecutionResult execute(ExecutionContext context) {
        throw new UnsupportedOperationException("Execution engine invocation is not implemented");
    }

    default EngineExecutionResult execute(EngineExecutionRequest request) {
        ExecutionResult result = execute(request.context());
        return new EngineExecutionResult(
                result.executionId(),
                descriptor().engineId(),
                descriptor().implementationVersion(),
                request.preparation().workspace().workspaceId(),
                request.preparation().source().resolvedRevision(),
                result.status() == ExecutionStatus.SUCCEEDED
                        ? EngineExecutionState.SUCCEEDED
                        : EngineExecutionState.FAILED,
                result.startedAt(),
                result.finishedAt(),
                result.duration());
    }
}
