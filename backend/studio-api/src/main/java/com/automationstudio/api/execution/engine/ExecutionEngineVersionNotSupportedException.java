package com.automationstudio.api.execution.engine;

public class ExecutionEngineVersionNotSupportedException
        extends ExecutionEngineCompatibilityException {

    public ExecutionEngineVersionNotSupportedException(String message) {
        super(message);
    }
}
