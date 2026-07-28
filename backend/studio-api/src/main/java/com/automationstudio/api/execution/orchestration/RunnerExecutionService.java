package com.automationstudio.api.execution.orchestration;

public interface RunnerExecutionService {

    ExecutionStartResult start(RunnerExecutionRequest request);

    ExecutionCompletionResult prepareCompletion(RunnerExecutionRequest request);
}
