package com.automationstudio.api.execution.artifact.metadata;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionArtifact;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorage;
import com.automationstudio.api.repository.ExecutionArtifactRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactMetadataServiceImpl implements ArtifactMetadataService {

    private final ExecutionRepository executionRepository;
    private final ExecutionArtifactRepository artifactRepository;
    private final Optional<ArtifactStorage> storage;

    public ArtifactMetadataServiceImpl(
            ExecutionRepository executionRepository,
            ExecutionArtifactRepository artifactRepository,
            Optional<ArtifactStorage> storage) {
        this.executionRepository = executionRepository;
        this.artifactRepository = artifactRepository;
        this.storage = storage;
    }

    @Override
    @Transactional
    public ArtifactMetadata register(
            UUID workspaceId, UUID projectId, UUID executionId, ArtifactRegistration registration) {
        requireIds(workspaceId, projectId, executionId);
        if (registration == null) {
            throw new IllegalArgumentException("Artifact registration must not be null");
        }
        Execution execution = executionRepository.findByIdAndProjectIdAndProjectWorkspaceId(
                executionId, projectId, workspaceId).orElse(null);
        if (execution == null) {
            compensate(registration.storedArtifact().storageReference());
            throw notFound();
        }
        if (artifactRepository.existsById(registration.artifactId())) {
            compensate(registration.storedArtifact().storageReference());
            throw duplicate();
        }
        if (artifactRepository.existsByStorageReference(
                registration.storedArtifact().storageReference().value())) {
            throw duplicate();
        }
        var stored = registration.storedArtifact();
        var entity = new ExecutionArtifact(registration.artifactId(), execution,
                registration.category().value(), registration.logicalName(), registration.mediaType(),
                stored.storageReference().value(), stored.sizeBytes(), stored.checksumAlgorithm(),
                stored.checksum(), registration.retentionReference(), registration.metadata(),
                stored.finalizedAt().atOffset(ZoneOffset.UTC));
        try {
            return map(artifactRepository.saveAndFlush(entity));
        } catch (RuntimeException failure) {
            compensate(stored.storageReference());
            throw new ArtifactMetadataException(
                    "ARTIFACT_METADATA_PERSISTENCE_FAILED",
                    "Artifact metadata registration failed safely");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArtifactMetadata> list(UUID workspaceId, UUID projectId, UUID executionId) {
        requireScopedExecution(workspaceId, projectId, executionId);
        return artifactRepository.findByExecutionIdOrderByCreatedAtAscIdAsc(executionId).stream()
                .map(ArtifactMetadataServiceImpl::map).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArtifactMetadata> find(
            UUID workspaceId, UUID projectId, UUID executionId, UUID artifactId) {
        requireIds(workspaceId, projectId, executionId);
        if (artifactId == null) {
            throw new IllegalArgumentException("Artifact ID must not be null");
        }
        return artifactRepository.findScoped(workspaceId, projectId, executionId, artifactId)
                .map(ArtifactMetadataServiceImpl::map);
    }

    private void requireScopedExecution(UUID workspaceId, UUID projectId, UUID executionId) {
        requireIds(workspaceId, projectId, executionId);
        if (executionRepository.findByIdAndProjectIdAndProjectWorkspaceId(
                executionId, projectId, workspaceId).isEmpty()) {
            throw notFound();
        }
    }

    private void compensate(com.automationstudio.api.execution.artifact.storage.ArtifactStorageReference reference) {
        storage.ifPresent(value -> {
            try {
                value.delete(reference);
            } catch (RuntimeException ignored) {
                // Persistence failure remains primary; AS-080 owns operational orphan reconciliation.
            }
        });
    }

    private static ArtifactMetadata map(ExecutionArtifact artifact) {
        return new ArtifactMetadata(artifact.getId(), artifact.getExecution().getId(),
                artifact.getCategory(), artifact.getLogicalName(), artifact.getMediaType(),
                artifact.getSizeBytes(), artifact.getChecksumAlgorithm(), artifact.getChecksum(),
                artifact.getStorageReference(), artifact.getCreatedAt(),
                artifact.getRetentionReference(), artifact.getMetadata());
    }

    private static void requireIds(UUID workspaceId, UUID projectId, UUID executionId) {
        if (workspaceId == null || projectId == null || executionId == null) {
            throw new IllegalArgumentException("Artifact scope IDs must not be null");
        }
    }

    private static ArtifactMetadataException notFound() {
        return new ArtifactMetadataException(
                "ARTIFACT_SCOPE_NOT_FOUND", "Artifact scope was not found");
    }

    private static ArtifactMetadataException duplicate() {
        return new ArtifactMetadataException(
                "ARTIFACT_METADATA_DUPLICATE", "Artifact metadata is already registered");
    }
}
