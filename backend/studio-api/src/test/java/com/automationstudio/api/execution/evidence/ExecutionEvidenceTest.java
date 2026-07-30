package com.automationstudio.api.execution.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionEvidenceTest {

    @Test
    void defensivelyCopiesAllCollections() {
        List<ExecutionArtifact> artifacts = new ArrayList<>();
        artifacts.add(ExecutionArtifactTest.artifact(1, Map.of()));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("engine", "fake");

        ExecutionEvidence evidence = new ExecutionEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.parse("2026-07-30T10:00:00Z"),
                artifacts,
                metadata,
                new ExecutionEvidenceSummary(1, 0, 0, Duration.ofSeconds(1)));
        artifacts.clear();
        metadata.clear();

        assertThat(evidence.artifacts()).hasSize(1);
        assertThat(evidence.metadata()).containsEntry("engine", "fake");
        assertThatThrownBy(() -> evidence.artifacts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullArtifacts() {
        List<ExecutionArtifact> artifacts = new ArrayList<>();
        artifacts.add(null);

        assertThatThrownBy(() -> new ExecutionEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.now(),
                artifacts,
                Map.of(),
                new ExecutionEvidenceSummary(1, 0, 0, Duration.ZERO)))
                .isInstanceOf(ExecutionEvidenceException.class);
    }
}
