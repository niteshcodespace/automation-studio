package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;

public interface ExecutionEngine {

    ExecutionEngineDescriptor descriptor();

    void validate(ExecutionContext context);
}
