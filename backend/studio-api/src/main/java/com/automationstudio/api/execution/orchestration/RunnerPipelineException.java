package com.automationstudio.api.execution.orchestration;

public final class RunnerPipelineException extends RuntimeException {

    private final String code;

    public RunnerPipelineException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
