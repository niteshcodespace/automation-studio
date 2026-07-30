package com.automationstudio.api.execution.lifecycle;

import com.automationstudio.api.execution.ExecutionContext;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ExecutionLifecycleValidator {

    public void validateEngineResult(
            ExecutionContext context, ExecutionResult result) {
        if (result == null) {
            throw new ExecutionLifecycleException("Execution engine returned no result");
        }
        if (!context.executionId().equals(result.executionId())
                || !context.runner().runnerId().equals(result.runnerId())) {
            throw new ExecutionLifecycleException(
                    "Execution engine result identity does not match execution context");
        }
        if (result.status() != ExecutionStatus.SUCCEEDED
                && result.status() != ExecutionStatus.FAILED) {
            throw new ExecutionLifecycleException(
                    "Execution engine result must be terminal");
        }
        Duration elapsed = Duration.between(result.startedAt(), result.finishedAt());
        if (!elapsed.equals(result.duration())) {
            throw new ExecutionLifecycleException(
                    "Execution engine result duration is inconsistent");
        }
    }
}
