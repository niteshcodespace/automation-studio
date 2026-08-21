package com.automationstudio.api.execution.orchestration;

import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;

public interface ExecutionSupervisor {
    EngineExecutionResult execute(ExecutionEnginePlugin plugin, EngineExecutionRequest request,
            PlatformExecutionControl control);
}
