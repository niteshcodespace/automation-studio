package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;

public interface ExecutionEngine extends ExecutionEnginePlugin {

    ExecutionEngineDescriptor descriptor();

    @Override
    default void validate(EngineExecutionContext context) {
        throw new UnsupportedOperationException("SDK engine validation is not implemented");
    }

    @Override
    default com.automationstudio.engine.sdk.EngineExecutionResult execute(
            com.automationstudio.engine.sdk.EngineExecutionRequest request) {
        throw new UnsupportedOperationException("SDK engine invocation is not implemented");
    }

    /** Platform-context compatibility bridge. */
    @Deprecated(forRemoval = false)
    default void validate(ExecutionContext context) {
        validate(EngineExecutionContextProjection.from(context));
    }

    /**
     * Legacy context-only invocation retained for source compatibility.
     * Canonical orchestration must invoke {@link #execute(EngineExecutionRequest)}.
     */
    @Deprecated(forRemoval = false)
    default ExecutionResult execute(ExecutionContext context) {
        throw new UnsupportedOperationException(
                "Legacy execution engine invocation is not implemented");
    }

    /** Prepared-request compatibility overload retained while repository callers migrate. */
    @Deprecated(forRemoval = false)
    default EngineExecutionResult execute(EngineExecutionRequest request) {
        throw new UnsupportedOperationException(
                "Prepared execution engine invocation is not implemented");
    }

}
