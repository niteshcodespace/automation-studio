package com.automationstudio.api.execution.lifecycle;

import com.automationstudio.api.execution.orchestration.RunnerExecutionRequest;

public interface ExecutionLifecycleService {

    ExecutionResult execute(RunnerExecutionRequest request);
}
