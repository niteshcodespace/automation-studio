package com.automationstudio.api.execution.preparation;

public final class SourcePreparationException extends RuntimeException {

    private final String code;

    public SourcePreparationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SourcePreparationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
