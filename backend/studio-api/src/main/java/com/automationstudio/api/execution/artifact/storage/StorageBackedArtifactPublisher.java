package com.automationstudio.api.execution.artifact.storage;

import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataService;
import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataException;
import com.automationstudio.api.execution.artifact.metadata.ArtifactRegistration;
import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
import com.automationstudio.engine.sdk.ArtifactPublisher;
import com.automationstudio.engine.sdk.ArtifactReceipt;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Execution-scoped adapter; production orchestration wiring remains AS-028E. */
public final class StorageBackedArtifactPublisher implements ArtifactPublisher {

    private final UUID executionId;
    private final ArtifactStorage storage;
    private final ArtifactStorageLimits limits;
    private final UUID workspaceId;
    private final UUID projectId;
    private final ArtifactMetadataService metadataService;
    private final String retentionReference;
    private final ReentrantLock accountingLock = new ReentrantLock();
    private long finalizedBytes;
    private int finalizedArtifacts;
    private int openPublications;
    private boolean failedPublication;
    private boolean active = true;

    public StorageBackedArtifactPublisher(
            UUID executionId, ArtifactStorage storage, ArtifactStorageLimits limits) {
        this(executionId, storage, limits, null, null, null, null);
    }

    public StorageBackedArtifactPublisher(
            UUID executionId,
            ArtifactStorage storage,
            ArtifactStorageLimits limits,
            UUID workspaceId,
            UUID projectId,
            ArtifactMetadataService metadataService,
            String retentionReference) {
        this.executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        this.storage = Objects.requireNonNull(storage, "Artifact storage must not be null");
        this.limits = Objects.requireNonNull(limits, "Artifact storage limits must not be null");
        this.workspaceId = workspaceId;
        this.projectId = projectId;
        this.metadataService = metadataService;
        this.retentionReference = retentionReference;
        if ((metadataService == null) != (workspaceId == null || projectId == null
                || retentionReference == null)) {
            throw new IllegalArgumentException("Artifact metadata binding is incomplete");
        }
    }

    static StorageBackedArtifactPublisher unavailable(UUID executionId) {
        return new StorageBackedArtifactPublisher(executionId);
    }

    private StorageBackedArtifactPublisher(UUID executionId) {
        this.executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        this.storage = null;
        this.limits = null;
        this.workspaceId = null;
        this.projectId = null;
        this.metadataService = null;
        this.retentionReference = null;
    }

    @Override
    public UUID executionId() {
        return executionId;
    }

    @Override
    public ArtifactReceipt publish(ArtifactPublication publication) {
        ArtifactPublication candidate = Objects.requireNonNull(
                publication, "Artifact publication must not be null");
        long allowedBytes = reservePublication();
        StoredArtifact stored = null;
        boolean accepted = false;
        UUID artifactId = UUID.randomUUID();
        try {
            stored = storage.store(new ArtifactStorageKey(artifactId),
                    candidate.contentWriter()::writeTo, allowedBytes);
            accepted = acceptFinalized(stored);
            if (!accepted) {
                storage.delete(stored.storageReference());
                throw limitExceeded();
            }
            if (metadataService != null) {
                metadataService.register(workspaceId, projectId, executionId,
                        new ArtifactRegistration(artifactId, candidate.category(),
                                candidate.logicalName(), candidate.mediaType(), candidate.metadata(),
                                stored, retentionReference));
            }
            return new ArtifactReceipt(artifactId, executionId, candidate.category(),
                    candidate.logicalName(), candidate.mediaType(), stored.sizeBytes(),
                    stored.checksumAlgorithm(), stored.checksum(), stored.finalizedAt());
        } catch (ArtifactPublicationException failure) {
            recordFailure();
            throw failure;
        } catch (RuntimeException failure) {
            recordFailure();
            if (stored != null && accepted) {
                rollbackFinalized(stored);
            }
            if (stored != null && !(failure instanceof ArtifactMetadataException)) {
                deleteQuietly(stored.storageReference());
            }
            throw new ArtifactPublicationException(
                    "ARTIFACT_PUBLICATION_FAILED", "Artifact publication failed safely");
        } finally {
            releasePublication();
        }
    }

    /** Completes the invocation and prevents all later use. */
    public void complete() {
        accountingLock.lock();
        try {
            active = false;
            if (openPublications != 0 || failedPublication) {
                throw new ArtifactPublicationException(
                        "ARTIFACT_PUBLICATION_FAILED",
                        "Artifact publication failed safely");
            }
        } finally {
            accountingLock.unlock();
        }
    }

    /** Aborts invocation-local use without deleting already durable, registered evidence. */
    public void abort() {
        accountingLock.lock();
        try {
            active = false;
        } finally {
            accountingLock.unlock();
        }
    }

    public long finalizedBytes() {
        accountingLock.lock();
        try {
            return finalizedBytes;
        } finally {
            accountingLock.unlock();
        }
    }

    public int finalizedArtifacts() {
        accountingLock.lock();
        try {
            return finalizedArtifacts;
        } finally {
            accountingLock.unlock();
        }
    }

    private long reservePublication() {
        accountingLock.lock();
        try {
            if (!active || storage == null) {
                throw new ArtifactPublicationException(
                        "ARTIFACT_PUBLICATION_UNAVAILABLE",
                        "Artifact publication is unavailable for this execution");
            }
            if (openPublications >= limits.maximumConcurrentPublicationsPerExecution()
                    || finalizedArtifacts >= limits.maximumArtifactsPerExecution()
                    || finalizedBytes >= limits.maximumBytesPerExecution()) {
                throw limitExceeded();
            }
            openPublications++;
            return Math.min(limits.maximumBytesPerArtifact(),
                    limits.maximumBytesPerExecution() - finalizedBytes);
        } finally {
            accountingLock.unlock();
        }
    }

    private boolean acceptFinalized(StoredArtifact stored) {
        accountingLock.lock();
        try {
            if (finalizedArtifacts >= limits.maximumArtifactsPerExecution()
                    || stored.sizeBytes() > limits.maximumBytesPerExecution() - finalizedBytes) {
                return false;
            }
            finalizedArtifacts++;
            finalizedBytes += stored.sizeBytes();
            return true;
        } finally {
            accountingLock.unlock();
        }
    }

    private void releasePublication() {
        accountingLock.lock();
        try {
            openPublications--;
        } finally {
            accountingLock.unlock();
        }
    }

    private void rollbackFinalized(StoredArtifact stored) {
        accountingLock.lock();
        try {
            finalizedArtifacts--;
            finalizedBytes -= stored.sizeBytes();
        } finally {
            accountingLock.unlock();
        }
    }

    private void recordFailure() {
        accountingLock.lock();
        try {
            failedPublication = true;
        } finally {
            accountingLock.unlock();
        }
    }

    private void deleteQuietly(ArtifactStorageReference reference) {
        try {
            storage.delete(reference);
        } catch (RuntimeException ignored) {
            // Preserve the publication failure; operational orphan handling belongs to AS-080.
        }
    }

    private static ArtifactPublicationException limitExceeded() {
        return new ArtifactPublicationException(
                "ARTIFACT_EXECUTION_LIMIT_EXCEEDED", "Artifact execution limit was exceeded");
    }
}
