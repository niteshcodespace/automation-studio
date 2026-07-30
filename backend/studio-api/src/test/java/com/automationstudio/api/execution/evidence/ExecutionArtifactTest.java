package com.automationstudio.api.execution.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionArtifactTest {

    @Test
    void isImmutableAndContainsNoPayload() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("suite", "smoke");
        ExecutionArtifact artifact = artifact(1, metadata);
        metadata.put("late", "change");

        assertThat(artifact.metadata()).containsExactly(Map.entry("suite", "smoke"));
        assertThatThrownBy(() -> artifact.metadata().put("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(ExecutionArtifact.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("content", "bytes", "payload", "path");
    }

    @Test
    void rejectsNegativeSize() {
        assertThatThrownBy(() -> new ExecutionArtifact(
                UUID.randomUUID(),
                ExecutionArtifactType.LOG,
                "engine.log",
                "text/plain",
                -1,
                reference(),
                Map.of()))
                .isInstanceOf(ExecutionEvidenceException.class);
    }

    static ExecutionArtifact artifact(int id, Map<String, String> metadata) {
        return new ExecutionArtifact(
                new UUID(0, id),
                ExecutionArtifactType.REPORT,
                "result-" + id,
                "application/xml",
                100,
                reference(),
                metadata);
    }

    static ExecutionArtifactReference reference() {
        return new ExecutionArtifactReference(
                URI.create("artifact://runner/result"), "runner-spool", null, null);
    }
}
