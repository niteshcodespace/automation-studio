package com.automationstudio.api.source.materialization;

public class SourceMaterializationException extends RuntimeException {

    private final String code;

    public SourceMaterializationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SourceMaterializationException(
            String code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
