package com.automationstudio.engine.restassured.manifest;

/** Sanitized configuration failure with a stable provider-local category. */
public final class RestAssuredManifestException extends RuntimeException {

    private final String code;

    public RestAssuredManifestException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
