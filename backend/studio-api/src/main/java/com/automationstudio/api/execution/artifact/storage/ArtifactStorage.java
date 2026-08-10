package com.automationstudio.api.execution.artifact.storage;

public interface ArtifactStorage {

    StoredArtifact store(
            ArtifactStorageKey key, ArtifactStorageContent content, long maximumBytes);

    boolean verify(StoredArtifact artifact);

    void delete(ArtifactStorageReference reference);
}
