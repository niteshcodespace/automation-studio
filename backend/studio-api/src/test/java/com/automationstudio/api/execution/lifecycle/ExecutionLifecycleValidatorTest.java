package com.automationstudio.api.execution.lifecycle;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionLifecycleValidatorTest {

    private final UUID executionId = UUID.randomUUID();
    private final UUID runnerId = UUID.randomUUID();
    private final OffsetDateTime startedAt =
            OffsetDateTime.parse("2026-07-30T10:00:00Z");
    private ExecutionContext context;
    private final ExecutionLifecycleValidator validator = new ExecutionLifecycleValidator();

    @BeforeEach
    void setUp() {
        context = mock(ExecutionContext.class);
        ExecutionRunnerContext runner = mock(ExecutionRunnerContext.class);
        when(context.executionId()).thenReturn(executionId);
        when(context.runner()).thenReturn(runner);
        when(runner.runnerId()).thenReturn(runnerId);
    }

    @Test
    void acceptsMatchingTerminalResult() {
        assertThatCode(() -> validator.validateEngineResult(context, result(executionId, runnerId)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullIdentityMismatchRunningStatusAndDurationMismatch() {
        assertThatThrownBy(() -> validator.validateEngineResult(context, null))
                .isInstanceOf(ExecutionLifecycleException.class);
        assertThatThrownBy(() -> validator.validateEngineResult(
                context, result(UUID.randomUUID(), runnerId)))
                .isInstanceOf(ExecutionLifecycleException.class);
        ExecutionResult running = new ExecutionResult(
                executionId, runnerId, ExecutionStatus.RUNNING,
                startedAt, startedAt, Duration.ZERO,
                ExecutionTerminationReason.COMPLETED,
                ExecutionFailureReason.NONE, Map.of());
        assertThatThrownBy(() -> validator.validateEngineResult(context, running))
                .isInstanceOf(ExecutionLifecycleException.class);
        ExecutionResult inconsistent = new ExecutionResult(
                executionId, runnerId, ExecutionStatus.SUCCEEDED,
                startedAt, startedAt.plusSeconds(1), Duration.ZERO,
                ExecutionTerminationReason.COMPLETED,
                ExecutionFailureReason.NONE, Map.of());
        assertThatThrownBy(() -> validator.validateEngineResult(context, inconsistent))
                .isInstanceOf(ExecutionLifecycleException.class);
    }

    private ExecutionResult result(UUID resultExecutionId, UUID resultRunnerId) {
        return new ExecutionResult(
                resultExecutionId, resultRunnerId, ExecutionStatus.SUCCEEDED,
                startedAt, startedAt.plusSeconds(1), Duration.ofSeconds(1),
                ExecutionTerminationReason.COMPLETED,
                ExecutionFailureReason.NONE, Map.of());
    }
}
