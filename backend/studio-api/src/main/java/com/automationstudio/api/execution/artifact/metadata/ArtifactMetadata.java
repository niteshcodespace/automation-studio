package com.automationstudio.api.execution.artifact.metadata;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ArtifactMetadata(
        UUID artifactId,
        UUID executionId,
        String category,
        String logicalName,
        String mediaType,
        long sizeBytes,
        String checksumAlgorithm,
        String checksum,
        String storageReference,
        OffsetDateTime createdAt,
        String retentionReference,
        Map<String, String> metadata) {

    public ArtifactMetadata {
        artifactId = Objects.requireNonNull(artifactId, "Artifact ID must not be null");
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        category = Objects.requireNonNull(category, "Artifact category must not be null");
        logicalName = Objects.requireNonNull(logicalName, "Artifact logical name must not be null");
        mediaType = Objects.requireNonNull(mediaType, "Artifact media type must not be null");
        checksumAlgorithm = Objects.requireNonNull(checksumAlgorithm, "Checksum algorithm must not be null");
        checksum = Objects.requireNonNull(checksum, "Checksum must not be null");
        storageReference = Objects.requireNonNull(storageReference, "Storage reference must not be null");
        createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        retentionReference = Objects.requireNonNull(retentionReference, "Retention reference must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "Artifact metadata must not be null"));
    }
}
