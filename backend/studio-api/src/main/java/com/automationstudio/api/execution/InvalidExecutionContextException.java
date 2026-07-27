package com.automationstudio.api.execution;

public class InvalidExecutionContextException extends RuntimeException {

    public InvalidExecutionContextException(String message) {
        super(message);
    }

    public InvalidExecutionContextException(String message, Throwable cause) {
        super(message, cause);
    }
}
