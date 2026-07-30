package com.automationstudio.api.execution.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.ExecutionEngineSupport;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceCollectorImpl;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceValidator;
import com.automationstudio.api.execution.evidence.ExecutionEvidence;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceSummary;
import com.automationstudio.api.execution.orchestration.ExecutionOwnershipException;
import com.automationstudio.api.execution.orchestration.ExecutionStartResult;
import com.automationstudio.api.execution.orchestration.RunnerExecutionRequest;
import com.automationstudio.api.execution.orchestration.RunnerExecutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionLifecycleServiceTest {

    private static final OffsetDateTime STARTED_AT =
            OffsetDateTime.parse("2026-07-30T10:00:00Z");
    private static final OffsetDateTime FINISHED_AT = STARTED_AT.plusSeconds(3);

    private final UUID executionId = UUID.randomUUID();
    private final UUID runnerUuid = UUID.randomUUID();
    private final UUID claimToken = UUID.randomUUID();
    private final RunnerExecutionService runnerService = mock(RunnerExecutionService.class);
    private final ExecutionEngineRegistry registry = mock(ExecutionEngineRegistry.class);
    private final ExecutionEngine engine = mock(ExecutionEngine.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final ExecutionRunnerContext runner = mock(ExecutionRunnerContext.class);
    private final ExecutionEngineDescriptor descriptor = new ExecutionEngineDescriptor(
            "fake", "1", "Fake", Set.of(), Set.of());
    private final RunnerExecutionRequest request = new RunnerExecutionRequest(
            executionId, "runner-key", claimToken, 2, 4, 6);
    private ExecutionLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionLifecycleServiceImpl(
                runnerService,
                registry,
                new ExecutionEngineInvoker(),
                new ExecutionLifecycleValidator(),
                new ExecutionEvidenceCollectorImpl(new ExecutionEvidenceValidator()),
                Clock.fixed(Instant.parse("2026-07-30T10:00:03Z"), ZoneOffset.UTC));
        when(context.executionId()).thenReturn(executionId);
        when(context.runner()).thenReturn(runner);
        when(runner.runnerId()).thenReturn(runnerUuid);
        when(runnerService.start(request)).thenReturn(new ExecutionStartResult(
                executionId,
                com.automationstudio.api.domain.ExecutionStatus.RUNNING,
                7, 2, 4, STARTED_AT, context, descriptor));
        when(registry.resolve("fake", "1"))
                .thenReturn(new ExecutionEngineSupport(engine, descriptor));
    }

    @Test
    void invokesEngineAndCompletesSuccessfulExecution() {
        ExecutionResult engineResult = success();
        when(engine.execute(context)).thenReturn(engineResult);

        ExecutionResult result = service.execute(request);

        assertThat(result).isEqualTo(engineResult);
        verify(runnerService).complete(
                eq(completionRequest()),
                eq(com.automationstudio.api.domain.ExecutionStatus.PASSED));
    }

    @Test
    void normalizesEngineExceptionAndCompletesFailedExecution() {
        when(engine.execute(context))
                .thenThrow(new IllegalStateException("provider-specific secret"));

        ExecutionResult result = service.execute(request);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(ExecutionFailureReason.ENGINE_EXCEPTION);
        assertThat(result.metadata()).doesNotContainValue("provider-specific secret");
        verify(runnerService).complete(
                eq(completionRequest()),
                eq(com.automationstudio.api.domain.ExecutionStatus.FAILED));
    }

    @Test
    void normalizesInvalidEngineResult() {
        when(engine.execute(context)).thenReturn(null);

        ExecutionResult result = service.execute(request);

        assertThat(result.failureReason())
                .isEqualTo(ExecutionFailureReason.INVALID_ENGINE_RESULT);
    }

    @Test
    void normalizesInvalidEvidenceAsInvalidEngineResult() {
        ExecutionEvidence invalidEvidence = new ExecutionEvidence(
                executionId,
                runnerUuid,
                FINISHED_AT,
                List.of(),
                Map.of(),
                new ExecutionEvidenceSummary(0, 0, 0, Duration.ofSeconds(2)));
        when(engine.execute(context)).thenReturn(new ExecutionResult(
                executionId,
                runnerUuid,
                ExecutionStatus.SUCCEEDED,
                STARTED_AT,
                FINISHED_AT,
                Duration.ofSeconds(3),
                ExecutionTerminationReason.COMPLETED,
                ExecutionFailureReason.NONE,
                Map.of(),
                invalidEvidence));

        ExecutionResult result = service.execute(request);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.failureReason())
                .isEqualTo(ExecutionFailureReason.INVALID_ENGINE_RESULT);
    }

    @Test
    void preservesProviderNeutralReportedFailure() {
        ExecutionResult failed = new ExecutionResult(
                executionId, runnerUuid, ExecutionStatus.FAILED,
                STARTED_AT, FINISHED_AT, Duration.ofSeconds(3),
                ExecutionTerminationReason.ENGINE_FAILURE,
                ExecutionFailureReason.ENGINE_REPORTED_FAILURE,
                Map.of("category", "assertion"));
        when(engine.execute(context)).thenReturn(failed);

        ExecutionResult result = service.execute(request);

        assertThat(result).isEqualTo(failed);
        verify(runnerService).complete(
                eq(completionRequest()),
                eq(com.automationstudio.api.domain.ExecutionStatus.FAILED));
    }

    @Test
    void stopsBeforeEngineInvocationWhenOwnershipFails() {
        when(runnerService.start(request))
                .thenThrow(new ExecutionOwnershipException("lease expired"));

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(ExecutionOwnershipException.class);

        verify(engine, never()).execute(context);
    }

    @Test
    void propagatesOwnershipLossAfterEngineInvocationWithoutNormalization() {
        when(engine.execute(context)).thenReturn(success());
        when(runnerService.complete(
                eq(completionRequest()),
                eq(com.automationstudio.api.domain.ExecutionStatus.PASSED)))
                .thenThrow(new ExecutionOwnershipException("lease expired"));

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(ExecutionOwnershipException.class)
                .hasMessage("lease expired");

        verify(engine).execute(context);
    }

    private ExecutionResult success() {
        return new ExecutionResult(
                executionId, runnerUuid, ExecutionStatus.SUCCEEDED,
                STARTED_AT, FINISHED_AT, Duration.ofSeconds(3),
                ExecutionTerminationReason.COMPLETED,
                ExecutionFailureReason.NONE, Map.of("engine", "fake"));
    }

    private RunnerExecutionRequest completionRequest() {
        return new RunnerExecutionRequest(
                executionId, "runner-key", claimToken, 2, 4, 7);
    }
}
