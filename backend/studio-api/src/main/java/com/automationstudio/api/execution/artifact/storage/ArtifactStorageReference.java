package com.automationstudio.api.execution.artifact.storage;

import java.util.Objects;
import java.util.UUID;

public record ArtifactStorageReference(String value) {

    private static final String PREFIX = "artifact:v1:";

    public ArtifactStorageReference {
        Objects.requireNonNull(value, "Artifact storage reference must not be null");
        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Artifact storage reference is invalid");
        }
        try {
            UUID.fromString(value.substring(PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Artifact storage reference is invalid");
        }
    }

    public static ArtifactStorageReference from(ArtifactStorageKey key) {
        return new ArtifactStorageReference(PREFIX
                + Objects.requireNonNull(key, "Artifact storage key must not be null").value());
    }

    public UUID opaqueId() {
        return UUID.fromString(value.substring(PREFIX.length()));
    }

    @Override
    public String toString() {
        return "ArtifactStorageReference[REDACTED]";
    }
}
