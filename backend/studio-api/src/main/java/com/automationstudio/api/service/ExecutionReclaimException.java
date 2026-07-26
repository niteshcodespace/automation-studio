package com.automationstudio.api.service;

import lombok.Getter;

@Getter
public class ExecutionReclaimException extends RuntimeException {

    private final ReclaimFailure failure;

    public ExecutionReclaimException(ReclaimFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ExecutionReclaimException(
            ReclaimFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }
}
