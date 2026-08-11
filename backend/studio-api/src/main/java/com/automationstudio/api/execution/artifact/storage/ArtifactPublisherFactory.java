package com.automationstudio.api.execution.artifact.storage;

import java.util.UUID;

/** Creates one platform-owned publisher for one controlled execution invocation. */
@FunctionalInterface
public interface ArtifactPublisherFactory {

    StorageBackedArtifactPublisher create(UUID workspaceId, UUID projectId, UUID executionId);

    static ArtifactPublisherFactory unavailable() {
        return (workspaceId, projectId, executionId) ->
                StorageBackedArtifactPublisher.unavailable(executionId);
    }
}
