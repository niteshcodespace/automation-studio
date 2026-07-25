package com.automationstudio.api.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class InvalidExecutionTransitionException extends RuntimeException {

    private final UUID executionId;
    private final ExecutionStatus currentStatus;
    private final ExecutionStatus requestedStatus;

    public InvalidExecutionTransitionException(
            UUID executionId,
            ExecutionStatus currentStatus,
            ExecutionStatus requestedStatus) {
        super("Execution " + executionId + " cannot transition from "
                + currentStatus + " to " + requestedStatus);
        this.executionId = executionId;
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }
}
