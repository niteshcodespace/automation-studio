package com.automationstudio.api.execution.secret;

import java.util.Objects;

public final class SecretResolutionException
        extends com.automationstudio.engine.sdk.SecretResolutionException {

    public SecretResolutionException(String code, String message) {
        super(requireCode(code), Objects.requireNonNull(
                message, "Secret resolution message must not be null"));
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Secret resolution code must not be blank");
        }
        return code;
    }
}
