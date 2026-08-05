package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;

public interface ExecutionEngine {

    ExecutionEngineDescriptor descriptor();

    void validate(ExecutionContext context);

    /**
     * Legacy context-only invocation retained for source compatibility.
     * Canonical orchestration must invoke {@link #execute(EngineExecutionRequest)}.
     */
    @Deprecated(forRemoval = false)
    default ExecutionResult execute(ExecutionContext context) {
        throw new UnsupportedOperationException(
                "Legacy execution engine invocation is not implemented");
    }

    /**
     * Canonical provider-neutral invocation with verified preparation and scoped secret access.
     */
    default EngineExecutionResult execute(EngineExecutionRequest request) {
        throw new UnsupportedOperationException(
                "Prepared execution engine invocation is not implemented");
    }
}
