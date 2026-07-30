package com.automationstudio.api.execution.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionResultTest {

    @Test
    void isImmutableAndDefensivelyCopiesMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("attempt", "1");
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-07-30T10:00:00Z");

        ExecutionResult result = new ExecutionResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ExecutionStatus.SUCCEEDED,
                startedAt,
                startedAt.plusSeconds(2),
                Duration.ofSeconds(2),
                ExecutionTerminationReason.COMPLETED,
                ExecutionFailureReason.NONE,
                metadata);
        metadata.put("late", "change");

        assertThat(result.metadata()).containsExactly(Map.entry("attempt", "1"));
        assertThatThrownBy(() -> result.metadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInconsistentSuccessfulAndFailedResults() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-30T10:00:00Z");

        assertThatThrownBy(() -> new ExecutionResult(
                UUID.randomUUID(), UUID.randomUUID(), ExecutionStatus.SUCCEEDED,
                now, now, Duration.ZERO, ExecutionTerminationReason.ENGINE_FAILURE,
                ExecutionFailureReason.ENGINE_EXCEPTION, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionResult(
                UUID.randomUUID(), UUID.randomUUID(), ExecutionStatus.FAILED,
                now, now, Duration.ZERO, ExecutionTerminationReason.ENGINE_FAILURE,
                ExecutionFailureReason.NONE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
