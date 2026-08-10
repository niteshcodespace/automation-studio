package com.automationstudio.engine.sdk;

/** Sanitized, provider-neutral publication failure. */
public final class ArtifactPublicationException extends RuntimeException {

    private final String code;

    public ArtifactPublicationException(String code, String safeMessage) {
        super(requireText(safeMessage, "Artifact publication message"));
        this.code = requireText(code, "Artifact publication code");
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
