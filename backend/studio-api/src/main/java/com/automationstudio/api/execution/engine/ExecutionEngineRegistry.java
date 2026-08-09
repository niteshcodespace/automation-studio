package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import java.util.List;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;

public interface ExecutionEngineRegistry {

    ExecutionEngineSupport resolve(String engineName);

    ExecutionEngineSupport resolve(String engineName, String engineVersion);

    ExecutionEngineSupport validateCompatibility(ExecutionContext context);

    List<ExecutionEngineDescriptor> supportedEngines();
}
