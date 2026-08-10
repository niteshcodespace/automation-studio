package com.automationstudio.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "execution_artifact")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionArtifact {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private Execution execution;

    @Column(nullable = false, length = 128, updatable = false)
    private String category;

    @Column(name = "logical_name", nullable = false, length = 255, updatable = false)
    private String logicalName;

    @Column(name = "media_type", nullable = false, length = 255, updatable = false)
    private String mediaType;

    @Column(name = "storage_reference", length = 64, unique = true, updatable = false)
    private String storageReference;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "checksum_algorithm", length = 16, updatable = false)
    private String checksumAlgorithm;

    @Column(length = 64, updatable = false)
    private String checksum;

    @Column(name = "retention_reference", length = 128, updatable = false)
    private String retentionReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    @Getter(AccessLevel.NONE)
    private Map<String, String> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public ExecutionArtifact(
            UUID id, Execution execution, String category, String logicalName, String mediaType,
            String storageReference, long sizeBytes, String checksumAlgorithm, String checksum,
            String retentionReference, Map<String, String> metadata, OffsetDateTime createdAt) {
        this.id = Objects.requireNonNull(id, "Artifact ID must not be null");
        this.execution = Objects.requireNonNull(execution, "Artifact execution must not be null");
        this.category = requireText(category, "Artifact category");
        this.logicalName = requireText(logicalName, "Artifact logical name");
        this.mediaType = requireText(mediaType, "Artifact media type");
        this.storageReference = requireText(storageReference, "Artifact storage reference");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Artifact size must not be negative");
        }
        this.sizeBytes = sizeBytes;
        this.checksumAlgorithm = requireText(checksumAlgorithm, "Artifact checksum algorithm");
        this.checksum = requireText(checksum, "Artifact checksum");
        this.retentionReference = requireText(retentionReference, "Artifact retention reference");
        this.metadata = Map.copyOf(Objects.requireNonNull(metadata, "Artifact metadata must not be null"));
        this.createdAt = Objects.requireNonNull(createdAt, "Artifact creation time must not be null");
    }

    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
