package com.automationstudio.api.execution.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.lifecycle.ExecutionFailureReason;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;
import com.automationstudio.api.execution.lifecycle.ExecutionStatus;
import com.automationstudio.api.execution.lifecycle.ExecutionTerminationReason;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionEvidenceCollectorTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-07-30T10:00:00Z");
    private final UUID executionId = UUID.randomUUID();
    private final UUID runnerId = UUID.randomUUID();
    private final ExecutionEvidenceCollector collector =
            new ExecutionEvidenceCollectorImpl(new ExecutionEvidenceValidator());
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
    void normalizesOrderAndSummaryWithoutStorage() {
        ExecutionEvidence supplied = evidence(List.of(
                ExecutionArtifactTest.artifact(2, Map.of()),
                ExecutionArtifactTest.artifact(1, Map.of())), 99);

        ExecutionEvidence collected = collector.collect(context, result(supplied));

        assertThat(collected.artifacts())
                .extracting(ExecutionArtifact::artifactId)
                .containsExactly(new UUID(0, 1), new UUID(0, 2));
        assertThat(collected.summary().artifactCount()).isEqualTo(2);
        assertThat(collected.summary().warningCount()).isEqualTo(3);
        assertThat(collected.summary().errorCount()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateArtifactsAndDurationMismatch() {
        ExecutionArtifact artifact = ExecutionArtifactTest.artifact(1, Map.of());
        assertThatThrownBy(() -> collector.collect(
                context, result(evidence(List.of(artifact, artifact), 2))))
                .isInstanceOf(ExecutionEvidenceException.class);

        ExecutionEvidence wrongDuration = new ExecutionEvidence(
                executionId, runnerId, NOW, List.of(), Map.of(),
                new ExecutionEvidenceSummary(0, 0, 0, Duration.ofSeconds(2)));
        assertThatThrownBy(() -> collector.collect(context, result(wrongDuration)))
                .isInstanceOf(ExecutionEvidenceException.class)
                .hasMessageContaining("duration");
    }

    @Test
    void isSafeForConcurrentStatelessCollection() throws Exception {
        ExecutionResult result = result(evidence(
                List.of(ExecutionArtifactTest.artifact(1, Map.of())), 1));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(ignored -> (java.util.concurrent.Callable<ExecutionEvidence>)
                            () -> collector.collect(context, result))
                    .toList();
            assertThat(executor.invokeAll(calls))
                    .allSatisfy(future ->
                            assertThat(future.get().summary().artifactCount()).isOne());
        }
    }

    private ExecutionEvidence evidence(List<ExecutionArtifact> artifacts, int artifactCount) {
        return new ExecutionEvidence(
                executionId,
                runnerId,
                NOW,
                artifacts,
                Map.of("source", "fake-engine"),
                new ExecutionEvidenceSummary(
                        artifactCount, 3, 1, Duration.ofSeconds(1)));
    }

    private ExecutionResult result(ExecutionEvidence evidence) {
        return new ExecutionResult(
                executionId,
                runnerId,
                ExecutionStatus.SUCCEEDED,
                NOW,
                NOW.plusSeconds(1),
                Duration.ofSeconds(1),
                ExecutionTerminationReason.COMPLETED,
                ExecutionFailureReason.NONE,
                Map.of(),
                evidence);
    }
}
