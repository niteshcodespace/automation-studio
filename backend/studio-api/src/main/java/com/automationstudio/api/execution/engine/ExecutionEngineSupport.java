package com.automationstudio.api.execution.engine;

import java.util.Objects;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;

public record ExecutionEngineSupport(
        ExecutionEnginePlugin engine,
        ExecutionEngineDescriptor descriptor) {

    public ExecutionEngineSupport {
        engine = Objects.requireNonNull(engine, "Execution engine must not be null");
        descriptor = Objects.requireNonNull(
                descriptor, "Execution engine descriptor must not be null");
    }
}
