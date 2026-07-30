package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.domain.ExecutionStatus;

public interface RunnerExecutionService {

    ExecutionStartResult start(RunnerExecutionRequest request);

    ExecutionCompletionResult prepareCompletion(RunnerExecutionRequest request);

    ExecutionCompletionResult complete(
            RunnerExecutionRequest request, ExecutionStatus terminalStatus);
}
