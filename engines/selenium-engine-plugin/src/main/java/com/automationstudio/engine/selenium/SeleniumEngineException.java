package com.automationstudio.engine.selenium;

public final class SeleniumEngineException extends RuntimeException {
    private final String code;

    public SeleniumEngineException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Code must not be blank");
        this.code = code;
    }

    public String code() { return code; }
}
