package com.automationstudio.engine.sdk;

public class SecretResolutionException extends RuntimeException {

    private final String code;

    public SecretResolutionException(String code, String message) {
        super(requireText(message, "Secret resolution message"));
        this.code = requireText(code, "Secret resolution code");
    }

    public String code() {
        return code;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
