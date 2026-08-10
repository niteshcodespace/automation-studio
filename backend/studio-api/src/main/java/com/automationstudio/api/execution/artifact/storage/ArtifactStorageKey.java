package com.automationstudio.api.execution.artifact.storage;

import java.util.Objects;
import java.util.UUID;

public record ArtifactStorageKey(UUID value) {

    public ArtifactStorageKey {
        value = Objects.requireNonNull(value, "Artifact storage key must not be null");
    }

    public static ArtifactStorageKey generate() {
        return new ArtifactStorageKey(UUID.randomUUID());
    }
}
