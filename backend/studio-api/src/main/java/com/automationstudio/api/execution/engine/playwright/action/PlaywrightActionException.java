package com.automationstudio.api.execution.engine.playwright.action;

public final class PlaywrightActionException extends RuntimeException {
    private final String code;

    public PlaywrightActionException(String code, String message) {
        super(message);
        this.code = code;
    }

    PlaywrightActionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
