package com.automationstudio.api.execution.lifecycle;

public class ExecutionLifecycleException extends RuntimeException {

    public ExecutionLifecycleException(String message) {
        super(message);
    }

    public ExecutionLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
