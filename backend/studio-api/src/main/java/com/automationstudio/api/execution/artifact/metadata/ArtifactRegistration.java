package com.automationstudio.api.execution.artifact.metadata;

import com.automationstudio.api.execution.artifact.storage.StoredArtifact;
import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublication;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ArtifactRegistration(
        UUID artifactId,
        ArtifactCategory category,
        String logicalName,
        String mediaType,
        Map<String, String> metadata,
        StoredArtifact storedArtifact,
        String retentionReference) {

    public static final int MAX_RETENTION_REFERENCE_LENGTH = 128;

    public ArtifactRegistration {
        artifactId = Objects.requireNonNull(artifactId, "Artifact ID must not be null");
        storedArtifact = Objects.requireNonNull(storedArtifact, "Stored artifact must not be null");
        var validated = new ArtifactPublication(category, logicalName, mediaType, metadata, output -> { });
        category = validated.category();
        logicalName = validated.logicalName();
        mediaType = validated.mediaType();
        metadata = validated.metadata();
        if (retentionReference == null || retentionReference.isBlank()
                || retentionReference.length() > MAX_RETENTION_REFERENCE_LENGTH
                || retentionReference.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Artifact retention reference is invalid");
        }
    }
}
