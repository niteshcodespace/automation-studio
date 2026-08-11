package com.automationstudio.api.execution.artifact.storage.local;

import com.automationstudio.api.execution.artifact.storage.ArtifactStorage;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageLimits;
import com.automationstudio.api.execution.artifact.storage.ArtifactPublisherFactory;
import com.automationstudio.api.execution.artifact.storage.StorageBackedArtifactPublisher;
import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataService;
import com.automationstudio.api.execution.workspace.local.WorkspaceRootProperties;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "automation.runner.artifacts.root")
@EnableConfigurationProperties({ArtifactStorageLimits.class, WorkspaceRootProperties.class})
public class LocalArtifactStorageConfiguration {

    @Bean
    ArtifactStorage artifactStorage(
            ArtifactStorageLimits limits, WorkspaceRootProperties workspace, Clock clock) {
        Path artifactRoot = Path.of(limits.root()).toAbsolutePath().normalize();
        if (workspace.root() != null && !workspace.root().isBlank()) {
            Path workspaceRoot = Path.of(workspace.root()).toAbsolutePath().normalize();
            if (artifactRoot.startsWith(workspaceRoot) || workspaceRoot.startsWith(artifactRoot)) {
                throw new IllegalArgumentException(
                        "Artifact storage root must be separate from workspace root");
            }
        }
        return new LocalArtifactStorage(artifactRoot, clock);
    }

    @Bean
    @ConditionalOnBean(ArtifactMetadataService.class)
    ArtifactPublisherFactory artifactPublisherFactory(
            ArtifactStorage storage,
            ArtifactStorageLimits limits,
            ArtifactMetadataService metadataService) {
        return (workspaceId, projectId, executionId) -> new StorageBackedArtifactPublisher(
                executionId, storage, limits, workspaceId, projectId, metadataService,
                "retain:default");
    }
}
