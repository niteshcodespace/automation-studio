package com.automationstudio.engine.sdk;

public interface ExecutionEnginePlugin {

    ExecutionEngineDescriptor descriptor();

    void validate(EngineExecutionContext context);

    EngineExecutionResult execute(EngineExecutionRequest request);
}
