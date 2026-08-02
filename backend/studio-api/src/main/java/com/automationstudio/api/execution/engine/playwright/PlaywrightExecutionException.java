package com.automationstudio.api.execution.engine.playwright;

public final class PlaywrightExecutionException extends RuntimeException {

    private final String code;

    public PlaywrightExecutionException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public PlaywrightExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Playwright execution failure code is required");
        }
        return code;
    }
}
