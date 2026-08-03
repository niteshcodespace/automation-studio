package com.automationstudio.api.execution.secret;

import java.util.Objects;

public final class SecretResolutionException extends RuntimeException {

    private final String code;

    public SecretResolutionException(String code, String message) {
        super(Objects.requireNonNull(message, "Secret resolution message must not be null"));
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Secret resolution code must not be blank");
        }
        return code;
    }
}
