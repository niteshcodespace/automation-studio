package com.automationstudio.engine.selenium.manifest;

public final class SeleniumManifestException extends RuntimeException {
    private final String code;

    public SeleniumManifestException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Code must not be blank");
        this.code = code;
    }

    public String code() { return code; }
}
