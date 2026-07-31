package com.automationstudio.api.execution.engine.playwright.manifest;

public final class PlaywrightManifestException extends RuntimeException {

    private final String code;

    public PlaywrightManifestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
