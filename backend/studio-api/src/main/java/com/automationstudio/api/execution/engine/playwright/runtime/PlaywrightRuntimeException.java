package com.automationstudio.api.execution.engine.playwright.runtime;

public final class PlaywrightRuntimeException extends RuntimeException {

    private final String code;

    public PlaywrightRuntimeException(String code, String message) {
        super(message);
        this.code = code;
    }

    PlaywrightRuntimeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
