package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;

public interface ExecutionEngine {

    ExecutionEngineDescriptor descriptor();

    void validate(ExecutionContext context);

    default ExecutionResult execute(ExecutionContext context) {
        throw new UnsupportedOperationException("Execution engine invocation is not implemented");
    }
}
