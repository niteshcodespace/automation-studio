package com.automationstudio.api.execution.artifact.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtifactMetadataService {

    ArtifactMetadata register(
            UUID workspaceId, UUID projectId, UUID executionId, ArtifactRegistration registration);

    List<ArtifactMetadata> list(UUID workspaceId, UUID projectId, UUID executionId);

    Optional<ArtifactMetadata> find(
            UUID workspaceId, UUID projectId, UUID executionId, UUID artifactId);
}
