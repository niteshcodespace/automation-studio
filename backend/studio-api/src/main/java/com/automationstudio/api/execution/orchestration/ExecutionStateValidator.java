package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Execution;
import org.springframework.stereotype.Component;

@Component
public class ExecutionStateValidator {

    public void validateStart(Execution execution) {
        validate(execution, ExecutionStatus.CLAIMED, "start");
    }

    public void validateCompletionPreparation(Execution execution) {
        validate(execution, ExecutionStatus.RUNNING, "prepare completion");
    }

    public void validateCompletion(
            Execution execution, ExecutionStatus terminalStatus) {
        if (terminalStatus == ExecutionStatus.ERROR) {
            validateErrorCompletion(execution);
            return;
        }
        validate(execution, ExecutionStatus.RUNNING, "complete");
        if (terminalStatus != ExecutionStatus.PASSED && terminalStatus != ExecutionStatus.FAILED) {
            throw new RunnerExecutionException(
                    "Execution terminal status must be PASSED, FAILED, or ERROR");
        }
    }

    private static void validateErrorCompletion(Execution execution) {
        if (execution == null) {
            throw new RunnerExecutionException("Execution must not be null");
        }
        if (execution.getStatus() != ExecutionStatus.CLAIMED
                && execution.getStatus() != ExecutionStatus.RUNNING
                && execution.getStatus() != ExecutionStatus.CANCEL_REQUESTED) {
            throw new RunnerExecutionException(
                    "Execution must be CLAIMED, RUNNING, or CANCEL_REQUESTED to complete as ERROR");
        }
    }

    private static void validate(
            Execution execution, ExecutionStatus requiredStatus, String operation) {
        if (execution == null) {
            throw new RunnerExecutionException("Execution must not be null");
        }
        if (execution.getStatus() != requiredStatus) {
            throw new RunnerExecutionException(
                    "Execution must be " + requiredStatus + " to " + operation);
        }
    }
}
