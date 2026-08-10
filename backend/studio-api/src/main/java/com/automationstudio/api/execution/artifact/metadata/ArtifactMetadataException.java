package com.automationstudio.api.execution.artifact.metadata;

public final class ArtifactMetadataException extends RuntimeException {

    private final String code;

    public ArtifactMetadataException(String code, String safeMessage) {
        super(requireText(safeMessage));
        this.code = requireText(code);
    }

    public String code() {
        return code;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Artifact metadata diagnostic must not be blank");
        }
        return value;
    }
}
