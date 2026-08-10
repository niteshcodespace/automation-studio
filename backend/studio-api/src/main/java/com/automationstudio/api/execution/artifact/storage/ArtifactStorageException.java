package com.automationstudio.api.execution.artifact.storage;

public final class ArtifactStorageException extends RuntimeException {

    private final String code;

    public ArtifactStorageException(String code, String safeMessage) {
        super(requireText(safeMessage));
        this.code = requireText(code);
    }

    public String code() {
        return code;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Artifact storage diagnostic must not be blank");
        }
        return value;
    }
}
