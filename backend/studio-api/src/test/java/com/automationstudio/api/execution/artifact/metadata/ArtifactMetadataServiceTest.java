package com.automationstudio.api.execution.artifact.metadata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorage;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageKey;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageReference;
import com.automationstudio.api.execution.artifact.storage.StoredArtifact;
import com.automationstudio.api.repository.ExecutionArtifactRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.engine.sdk.ArtifactCategory;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ArtifactMetadataServiceTest {

    @Test
    void compensatesNewDurableBytesWhenPersistenceFailsAndSanitizesFailure() {
        Fixture fixture = fixture();
        when(fixture.executionRepository().findByIdAndProjectIdAndProjectWorkspaceId(
                fixture.executionId(), fixture.projectId(), fixture.workspaceId()))
                .thenReturn(Optional.of(mock(Execution.class)));
        when(fixture.artifactRepository().saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("provider/table detail"));
        assertThatThrownBy(() -> fixture.service().register(fixture.workspaceId(), fixture.projectId(),
                fixture.executionId(), fixture.registration()))
                .isInstanceOf(ArtifactMetadataException.class)
                .hasMessage("Artifact metadata registration failed safely")
                .hasNoCause();
        verify(fixture.storage()).delete(fixture.registration().storedArtifact().storageReference());
    }

    @Test
    void duplicateStorageReferenceNeverDeletesPreviouslyOwnedBytes() {
        Fixture fixture = fixture();
        when(fixture.executionRepository().findByIdAndProjectIdAndProjectWorkspaceId(
                fixture.executionId(), fixture.projectId(), fixture.workspaceId()))
                .thenReturn(Optional.of(mock(Execution.class)));
        when(fixture.artifactRepository().existsByStorageReference(
                fixture.registration().storedArtifact().storageReference().value())).thenReturn(true);
        assertThatThrownBy(() -> fixture.service().register(fixture.workspaceId(), fixture.projectId(),
                fixture.executionId(), fixture.registration()))
                .isInstanceOf(ArtifactMetadataException.class)
                .hasMessage("Artifact metadata is already registered");
        verify(fixture.storage(), never()).delete(
                fixture.registration().storedArtifact().storageReference());
    }

    @Test
    void compensationFailureDoesNotReplaceSanitizedPersistenceFailure() {
        Fixture fixture = fixture();
        when(fixture.executionRepository().findByIdAndProjectIdAndProjectWorkspaceId(
                fixture.executionId(), fixture.projectId(), fixture.workspaceId()))
                .thenReturn(Optional.of(mock(Execution.class)));
        when(fixture.artifactRepository().saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database/provider detail"));
        org.mockito.Mockito.doThrow(new IllegalStateException("storage/provider detail"))
                .when(fixture.storage())
                .delete(fixture.registration().storedArtifact().storageReference());

        assertThatThrownBy(() -> fixture.service().register(fixture.workspaceId(), fixture.projectId(),
                fixture.executionId(), fixture.registration()))
                .isInstanceOf(ArtifactMetadataException.class)
                .hasMessage("Artifact metadata registration failed safely")
                .hasNoCause();
        verify(fixture.storage()).delete(fixture.registration().storedArtifact().storageReference());
    }

    private Fixture fixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        var reference = ArtifactStorageReference.from(new ArtifactStorageKey(artifactId));
        var registration = new ArtifactRegistration(artifactId, ArtifactCategory.LOG,
                "artifact.log", "text/plain", Map.of(),
                new StoredArtifact(reference, 1, "SHA-256", "0".repeat(64), Instant.EPOCH),
                "retain:default");
        var executionRepository = mock(ExecutionRepository.class);
        var artifactRepository = mock(ExecutionArtifactRepository.class);
        var storage = mock(ArtifactStorage.class);
        var service = new ArtifactMetadataServiceImpl(
                executionRepository, artifactRepository, Optional.of(storage));
        return new Fixture(workspaceId, projectId, executionId, registration,
                executionRepository, artifactRepository, storage, service);
    }

    private record Fixture(
            UUID workspaceId, UUID projectId, UUID executionId, ArtifactRegistration registration,
            ExecutionRepository executionRepository, ExecutionArtifactRepository artifactRepository,
            ArtifactStorage storage, ArtifactMetadataService service) { }
}
