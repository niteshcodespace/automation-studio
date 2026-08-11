package com.automationstudio.api.execution.artifact.storage;

import java.time.Instant;
import java.util.Objects;

public record StoredArtifact(
        ArtifactStorageReference storageReference,
        long sizeBytes,
        String checksumAlgorithm,
        String checksum,
        Instant finalizedAt) {

    public StoredArtifact {
        storageReference = Objects.requireNonNull(
                storageReference, "Artifact storage reference must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Stored artifact size must not be negative");
        }
        if (!"SHA-256".equals(checksumAlgorithm)) {
            throw new IllegalArgumentException("Stored artifact checksum algorithm is invalid");
        }
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Stored artifact checksum is invalid");
        }
        finalizedAt = Objects.requireNonNull(finalizedAt, "Artifact finalization time must not be null");
    }
}
