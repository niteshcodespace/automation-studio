package com.automationstudio.api.execution.artifact.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "automation.runner.artifacts")
public record ArtifactStorageLimits(
        String root,
        @DefaultValue("104857600") long maximumBytesPerArtifact,
        @DefaultValue("1073741824") long maximumBytesPerExecution,
        @DefaultValue("100") int maximumArtifactsPerExecution,
        @DefaultValue("4") int maximumConcurrentPublicationsPerExecution) {

    public ArtifactStorageLimits {
        if (root == null || root.isBlank() || root.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Artifact storage root must not be blank");
        }
        if (maximumBytesPerArtifact <= 0 || maximumBytesPerExecution <= 0
                || maximumBytesPerExecution < maximumBytesPerArtifact
                || maximumArtifactsPerExecution <= 0
                || maximumConcurrentPublicationsPerExecution <= 0
                || maximumConcurrentPublicationsPerExecution > maximumArtifactsPerExecution) {
            throw new IllegalArgumentException("Artifact storage limits are invalid");
        }
    }
}
