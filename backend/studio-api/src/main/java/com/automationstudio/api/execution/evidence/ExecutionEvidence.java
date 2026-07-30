package com.automationstudio.api.execution.evidence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionEvidence(
        UUID executionId,
        UUID runnerId,
        OffsetDateTime capturedAt,
        List<ExecutionArtifact> artifacts,
        Map<String, String> metadata,
        ExecutionEvidenceSummary summary) {

    public ExecutionEvidence {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        runnerId = Objects.requireNonNull(runnerId, "Runner ID must not be null");
        capturedAt = Objects.requireNonNull(
                capturedAt, "Evidence capture time must not be null");
        Objects.requireNonNull(artifacts, "Evidence artifacts must not be null");
        if (artifacts.stream().anyMatch(Objects::isNull)) {
            throw new ExecutionEvidenceException(
                    "Execution evidence must not contain null artifacts");
        }
        artifacts = List.copyOf(artifacts);
        metadata = Map.copyOf(Objects.requireNonNull(
                metadata, "Evidence metadata must not be null"));
        summary = Objects.requireNonNull(summary, "Evidence summary must not be null");
        if (metadata.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null)) {
            throw new ExecutionEvidenceException(
                    "Evidence metadata must contain nonblank keys and non-null values");
        }
    }

    public static ExecutionEvidence empty(
            UUID executionId,
            UUID runnerId,
            OffsetDateTime capturedAt,
            Duration duration) {
        return new ExecutionEvidence(
                executionId,
                runnerId,
                capturedAt,
                List.of(),
                Map.of(),
                new ExecutionEvidenceSummary(0, 0, 0, duration));
    }
}
