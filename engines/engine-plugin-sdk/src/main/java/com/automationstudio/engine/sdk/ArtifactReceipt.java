package com.automationstudio.engine.sdk;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Provider-neutral confirmation of a completed artifact publication. */
public record ArtifactReceipt(
        UUID artifactId,
        UUID executionId,
        ArtifactCategory category,
        String logicalName,
        String mediaType,
        long sizeBytes,
        String checksumAlgorithm,
        String checksum,
        Instant createdAt) {

    public ArtifactReceipt {
        artifactId = Objects.requireNonNull(artifactId, "Artifact ID must not be null");
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        category = Objects.requireNonNull(category, "Artifact category must not be null");
        logicalName = requireText(logicalName, "Artifact logical name");
        mediaType = requireText(mediaType, "Artifact media type");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Artifact size must not be negative");
        }
        checksumAlgorithm = requireText(checksumAlgorithm, "Artifact checksum algorithm");
        checksum = requireText(checksum, "Artifact checksum");
        createdAt = Objects.requireNonNull(createdAt, "Artifact creation time must not be null");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
