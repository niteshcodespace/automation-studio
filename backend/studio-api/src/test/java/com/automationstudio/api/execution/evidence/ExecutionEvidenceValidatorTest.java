package com.automationstudio.api.execution.evidence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionEvidenceValidatorTest {

    private final UUID executionId = UUID.randomUUID();
    private final UUID runnerId = UUID.randomUUID();
    private final ExecutionEvidenceValidator validator = new ExecutionEvidenceValidator();
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = mock(ExecutionContext.class);
        ExecutionRunnerContext runner = mock(ExecutionRunnerContext.class);
        when(context.executionId()).thenReturn(executionId);
        when(context.runner()).thenReturn(runner);
        when(runner.runnerId()).thenReturn(runnerId);
    }

    @Test
    void acceptsValidProviderNeutralEvidence() {
        assertThatCode(() -> validator.validate(context, evidence(
                List.of(ExecutionArtifactTest.artifact(1, Map.of())), 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateIdsInvalidReferencesIdentityAndSummaryMismatch() {
        ExecutionArtifact artifact = ExecutionArtifactTest.artifact(1, Map.of());
        assertThatThrownBy(() -> validator.validate(
                context, evidence(List.of(artifact, artifact), 2)))
                .isInstanceOf(ExecutionEvidenceException.class)
                .hasMessageContaining("Duplicate");

        ExecutionArtifact invalidReference = new ExecutionArtifact(
                UUID.randomUUID(),
                ExecutionArtifactType.ATTACHMENT,
                "relative",
                "text/plain",
                0,
                new ExecutionArtifactReference(
                        URI.create("relative/path"), "runner", null, null),
                Map.of());
        assertThatThrownBy(() -> validator.validate(
                context, evidence(List.of(invalidReference), 1)))
                .isInstanceOf(ExecutionEvidenceException.class)
                .hasMessageContaining("absolute");

        ExecutionEvidence wrongRunner = new ExecutionEvidence(
                executionId,
                UUID.randomUUID(),
                OffsetDateTime.now(),
                List.of(),
                Map.of(),
                new ExecutionEvidenceSummary(0, 0, 0, Duration.ZERO));
        assertThatThrownBy(() -> validator.validate(context, wrongRunner))
                .isInstanceOf(ExecutionEvidenceException.class)
                .hasMessageContaining("identity");

        assertThatThrownBy(() -> validator.validate(
                context, evidence(List.of(artifact), 0)))
                .isInstanceOf(ExecutionEvidenceException.class)
                .hasMessageContaining("count");
    }

    private ExecutionEvidence evidence(List<ExecutionArtifact> artifacts, int artifactCount) {
        return new ExecutionEvidence(
                executionId,
                runnerId,
                OffsetDateTime.parse("2026-07-30T10:00:00Z"),
                artifacts,
                Map.of(),
                new ExecutionEvidenceSummary(
                        artifactCount, 0, 0, Duration.ofSeconds(1)));
    }
}
