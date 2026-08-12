package com.automationstudio.engine.restassured;

/** Sanitized plugin-boundary failure. */
public final class RestAssuredEngineException extends RuntimeException {

    private final String code;

    public RestAssuredEngineException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
