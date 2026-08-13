package com.automationstudio.engine.karate;

/** Stable, sanitized provider failure. */
public final class KarateEngineException extends RuntimeException {
    private final String code;

    KarateEngineException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
