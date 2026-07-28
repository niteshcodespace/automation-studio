package com.automationstudio.api.execution.orchestration;

public class RunnerExecutionException extends RuntimeException {

    public RunnerExecutionException(String message) {
        super(message);
    }

    public RunnerExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
