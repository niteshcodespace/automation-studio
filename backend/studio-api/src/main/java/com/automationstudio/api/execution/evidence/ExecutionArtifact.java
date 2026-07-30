package com.automationstudio.api.execution.evidence;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionArtifact(
        UUID artifactId,
        ExecutionArtifactType type,
        String displayName,
        String contentType,
        long size,
        ExecutionArtifactReference reference,
        Map<String, String> metadata) {

    public ExecutionArtifact {
        artifactId = Objects.requireNonNull(artifactId, "Artifact ID must not be null");
        type = Objects.requireNonNull(type, "Artifact type must not be null");
        displayName = requireText(displayName, "Artifact display name");
        contentType = requireText(contentType, "Artifact content type");
        if (size < 0) {
            throw new ExecutionEvidenceException("Artifact size must not be negative");
        }
        reference = Objects.requireNonNull(reference, "Artifact reference must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(
                metadata, "Artifact metadata must not be null"));
        if (metadata.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null)) {
            throw new ExecutionEvidenceException(
                    "Artifact metadata must contain nonblank keys and non-null values");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ExecutionEvidenceException(name + " must not be blank");
        }
        return value.trim();
    }
}
