package com.automationstudio.api.execution.engine;

import java.util.Objects;

public record ExecutionEngineSupport(
        ExecutionEngine engine,
        ExecutionEngineDescriptor descriptor) {

    public ExecutionEngineSupport {
        engine = Objects.requireNonNull(engine, "Execution engine must not be null");
        descriptor = Objects.requireNonNull(
                descriptor, "Execution engine descriptor must not be null");
    }
}
