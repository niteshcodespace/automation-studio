package com.automationstudio.api.execution.evidence;

public class ExecutionEvidenceException extends RuntimeException {

    public ExecutionEvidenceException(String message) {
        super(message);
    }

    public ExecutionEvidenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
