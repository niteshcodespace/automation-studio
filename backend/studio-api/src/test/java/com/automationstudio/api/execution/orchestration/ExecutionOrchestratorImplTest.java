package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionEnvironmentSnapshot;
import com.automationstudio.api.execution.ExecutionMetadata;
import com.automationstudio.api.execution.ExecutionRetryPolicy;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.EngineExecutionState;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.ExecutionEngineNotFoundException;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.ExecutionEngineSupport;
import com.automationstudio.api.execution.preparation.SourcePreparationException;
import com.automationstudio.api.execution.preparation.SourcePreparationRequest;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationService;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class ExecutionOrchestratorImplTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:05:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime START =
            OffsetDateTime.parse("2026-07-30T12:00:00Z");
    private static final String REVISION = "0123456789012345678901234567890123456789";

    private SourcePreparationService preparationService;
    private ExecutionEngineRegistry registry;
    private WorkspaceManager workspaceManager;
    private ExecutionEngine engine;
    private ExecutionOrchestrator orchestrator;
    private ExecutionOrchestrationRequest request;
    private SourcePreparationResult preparation;
    private ExecutionEngineSupport support;

    @BeforeEach
    void setUp() {
        preparationService = mock(SourcePreparationService.class);
        registry = mock(ExecutionEngineRegistry.class);
        workspaceManager = mock(WorkspaceManager.class);
        engine = mock(ExecutionEngine.class);
        orchestrator = new ExecutionOrchestratorImpl(
                preparationService, registry, workspaceManager, CLOCK);
        request = request();
        preparation = preparation(request);
        ExecutionEngineDescriptor descriptor = new ExecutionEngineDescriptor(
                "dummy", "1.0", "Dummy", Set.of(), Set.of());
        support = new ExecutionEngineSupport(engine, descriptor);
        when(preparationService.prepare(request.preparationRequest())).thenReturn(preparation);
        when(registry.resolve("dummy", "1.0")).thenReturn(support);
        when(workspaceManager.release(preparation.workspace())).thenReturn(released());
    }

    @Test
    void executesInRequiredOrderAndReturnsOnlyAfterCleanup() {
        EngineExecutionResult engineResult = result(EngineExecutionState.SUCCEEDED);
        when(engine.execute(any(EngineExecutionRequest.class))).thenReturn(engineResult);

        ExecutionOrchestrationResult result = orchestrator.execute(request);

        assertThat(result.engineResult()).isEqualTo(engineResult);
        assertThat(result.completedAt().toInstant()).isEqualTo(CLOCK.instant());
        InOrder order = inOrder(preparationService, registry, engine, workspaceManager);
        order.verify(preparationService).prepare(request.preparationRequest());
        order.verify(registry).resolve("dummy", "1.0");
        order.verify(engine).execute(new EngineExecutionRequest(request.context(), preparation));
        order.verify(workspaceManager).release(preparation.workspace());
    }

    @Test
    void preparationFailureStopsWithoutDoubleCleanup() {
        SourcePreparationException cause = new SourcePreparationException(
                "SOURCE_MATERIALIZATION_FAILED", "private repository detail");
        when(preparationService.prepare(any())).thenThrow(cause);

        assertFailure("SOURCE_PREPARATION_FAILED", "Source preparation failed")
                .hasCause(cause)
                .hasMessageNotContaining("repository");
        verify(registry, never()).resolve(any(), any());
        verify(engine, never()).execute(any(EngineExecutionRequest.class));
        verify(workspaceManager, never()).release(any());
    }

    @Test
    void unknownEngineCleansPreparedWorkspace() {
        when(registry.resolve("dummy", "1.0"))
                .thenThrow(new ExecutionEngineNotFoundException("internal class"));

        assertFailure("ENGINE_NOT_FOUND", "Execution engine was not found");
        verify(workspaceManager).release(preparation.workspace());
        verify(engine, never()).execute(any(EngineExecutionRequest.class));
    }

    @Test
    void engineExceptionIsSanitizedAndCleanupRuns() {
        RuntimeException cause = new IllegalStateException("C:/private/path");
        when(engine.execute(any(EngineExecutionRequest.class))).thenThrow(cause);

        assertFailure("ENGINE_EXECUTION_FAILED", "Execution engine failed")
                .hasCause(cause)
                .hasMessageNotContaining("private");
        verify(workspaceManager).release(preparation.workspace());
    }

    @ParameterizedTest
    @EnumSource(value = EngineExecutionState.class, names = {"FAILED", "CANCELLED"})
    void validNonSuccessTerminalResultsAreReturned(EngineExecutionState state) {
        when(engine.execute(any(EngineExecutionRequest.class))).thenReturn(result(state));

        assertThat(orchestrator.execute(request).engineResult().state()).isEqualTo(state);
        verify(workspaceManager).release(preparation.workspace());
    }

    @Test
    void invalidEngineResultIsRejectedAfterCleanup() {
        when(engine.execute(any(EngineExecutionRequest.class))).thenReturn(null);

        assertFailure(
                "ENGINE_RESULT_INVARIANT_VIOLATION",
                "Execution engine returned inconsistent evidence");
        verify(workspaceManager).release(preparation.workspace());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "execution", "engineName", "engineVersion", "workspace", "revision",
            "state", "started", "finished", "order", "negativeDuration", "durationMismatch"
    })
    void rejectsEveryInvalidEngineResultInvariant(String mismatch) {
        EngineExecutionResult invalid = mock(EngineExecutionResult.class);
        when(invalid.executionId()).thenReturn(request.executionId());
        when(invalid.engineName()).thenReturn("dummy");
        when(invalid.engineVersion()).thenReturn("1.0");
        when(invalid.workspaceId()).thenReturn(preparation.workspace().workspaceId());
        when(invalid.resolvedRevision()).thenReturn(REVISION);
        when(invalid.state()).thenReturn(EngineExecutionState.SUCCEEDED);
        when(invalid.startedAt()).thenReturn(START);
        when(invalid.finishedAt()).thenReturn(START.plusSeconds(2));
        when(invalid.duration()).thenReturn(Duration.ofSeconds(2));
        switch (mismatch) {
            case "execution" -> when(invalid.executionId()).thenReturn(UUID.randomUUID());
            case "engineName" -> when(invalid.engineName()).thenReturn("other");
            case "engineVersion" -> when(invalid.engineVersion()).thenReturn("2.0");
            case "workspace" -> when(invalid.workspaceId())
                    .thenReturn(new WorkspaceId(UUID.randomUUID()));
            case "revision" -> when(invalid.resolvedRevision()).thenReturn("other");
            case "state" -> when(invalid.state()).thenReturn(null);
            case "started" -> when(invalid.startedAt()).thenReturn(null);
            case "finished" -> when(invalid.finishedAt()).thenReturn(null);
            case "order" -> when(invalid.finishedAt()).thenReturn(START.minusSeconds(1));
            case "negativeDuration" ->
                    when(invalid.duration()).thenReturn(Duration.ofSeconds(-1));
            case "durationMismatch" -> when(invalid.duration()).thenReturn(Duration.ZERO);
            default -> throw new AssertionError("Unexpected mismatch");
        }
        when(engine.execute(any(EngineExecutionRequest.class))).thenReturn(invalid);

        assertFailure(
                "ENGINE_RESULT_INVARIANT_VIOLATION",
                "Execution engine returned inconsistent evidence");
        verify(workspaceManager).release(preparation.workspace());
    }

    @Test
    void nullPreparationEvidenceFailsClosedWithoutGuessingCleanupIdentity() {
        when(preparationService.prepare(any())).thenReturn(null);

        assertFailure(
                "SOURCE_PREPARATION_INVARIANT_VIOLATION",
                "Source preparation returned inconsistent evidence");
        verify(registry, never()).resolve(any(), any());
        verify(workspaceManager, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "state", "execution", "workspace", "revision", "timestamp"
    })
    void rejectsEveryInvalidPreparationInvariant(String mismatch) {
        SourcePreparationResult invalid = mock(SourcePreparationResult.class);
        when(invalid.state()).thenReturn(SourcePreparationState.PREPARED);
        when(invalid.executionId()).thenReturn(request.executionId());
        when(invalid.workspace()).thenReturn(preparation.workspace());
        when(invalid.source()).thenReturn(preparation.source());
        when(invalid.preparedAt()).thenReturn(START);
        switch (mismatch) {
            case "state" -> when(invalid.state()).thenReturn(null);
            case "execution" -> when(invalid.executionId()).thenReturn(UUID.randomUUID());
            case "workspace" -> when(invalid.workspace()).thenReturn(null);
            case "revision" -> {
                SourceMaterializationResult source =
                        mock(SourceMaterializationResult.class);
                when(source.resolvedRevision()).thenReturn(null);
                when(invalid.source()).thenReturn(source);
            }
            case "timestamp" -> when(invalid.preparedAt()).thenReturn(null);
            default -> throw new AssertionError("Unexpected mismatch");
        }
        when(preparationService.prepare(any())).thenReturn(invalid);

        assertFailure(
                "SOURCE_PREPARATION_INVARIANT_VIOLATION",
                "Source preparation returned inconsistent evidence");
        verify(registry, never()).resolve(any(), any());
        if ("workspace".equals(mismatch)) {
            verify(workspaceManager, never()).release(any());
        } else {
            verify(workspaceManager).release(preparation.workspace());
        }
    }

    @Test
    void cleanupFailureTakesPrecedenceAndSuppressesEngineFailure() {
        RuntimeException engineFailure = new IllegalStateException("engine detail");
        RuntimeException cleanupFailure = new IllegalStateException("cleanup detail");
        when(engine.execute(any(EngineExecutionRequest.class))).thenThrow(engineFailure);
        when(workspaceManager.release(preparation.workspace())).thenThrow(cleanupFailure);

        assertFailure("WORKSPACE_CLEANUP_FAILED", "Workspace cleanup failed")
                .hasCause(cleanupFailure)
                .satisfies(exception -> {
                    assertThat(cleanupFailure.getSuppressed()).hasSize(1);
                    assertThat(cleanupFailure.getSuppressed()[0])
                            .isInstanceOf(ExecutionOrchestrationException.class)
                            .hasCause(engineFailure);
                });
    }

    @ParameterizedTest
    @EnumSource(value = EngineExecutionState.class, names = {"SUCCEEDED", "FAILED"})
    void cleanupFailurePreventsReturningValidEngineResult(EngineExecutionState state) {
        RuntimeException cleanupFailure = new IllegalStateException("cleanup detail");
        when(engine.execute(any(EngineExecutionRequest.class)))
                .thenReturn(result(state));
        when(workspaceManager.release(preparation.workspace())).thenThrow(cleanupFailure);

        assertFailure("WORKSPACE_CLEANUP_FAILED", "Workspace cleanup failed")
                .hasCause(cleanupFailure);
    }

    @Test
    void cleanupFailureTakesPrecedenceOverResultInvariantFailure() {
        RuntimeException cleanupFailure = new IllegalStateException("cleanup detail");
        when(engine.execute(any(EngineExecutionRequest.class))).thenReturn(null);
        when(workspaceManager.release(preparation.workspace())).thenThrow(cleanupFailure);

        assertFailure("WORKSPACE_CLEANUP_FAILED", "Workspace cleanup failed")
                .hasCause(cleanupFailure)
                .satisfies(exception -> assertThat(cleanupFailure.getSuppressed())
                        .singleElement()
                        .isInstanceOf(ExecutionOrchestrationException.class)
                        .satisfies(suppressed -> assertThat(
                                ((ExecutionOrchestrationException) suppressed).code())
                                .isEqualTo("ENGINE_RESULT_INVARIANT_VIOLATION")));
    }

    @Test
    void nullRequestIsRejectedBeforeAnyCollaboratorCall() {
        assertThatThrownBy(() -> orchestrator.execute(null))
                .isInstanceOf(ExecutionOrchestrationException.class)
                .satisfies(exception -> assertThat(
                        ((ExecutionOrchestrationException) exception).code())
                        .isEqualTo("INVALID_EXECUTION_REQUEST"));
        verify(preparationService, never()).prepare(any());
    }

    @Test
    void incompleteOrMismatchedRequestsUseStableInvalidRequestCode() {
        assertInvalidRequest(() -> new ExecutionOrchestrationRequest(
                null, request.preparationRequest()));
        assertInvalidRequest(() -> new ExecutionOrchestrationRequest(
                request.context(), null));
        ExecutionOrchestrationRequest other = request();
        assertInvalidRequest(() -> new ExecutionOrchestrationRequest(
                request.context(), other.preparationRequest()));
    }

    @Test
    void independentExecutionsShareNoMutableOrchestratorState() throws Exception {
        ExecutionOrchestrationRequest first = request();
        ExecutionOrchestrationRequest second = request();
        when(preparationService.prepare(any())).thenAnswer(invocation -> {
            SourcePreparationRequest requested = invocation.getArgument(0);
            return preparation(new ExecutionOrchestrationRequest(
                    requested.executionId().equals(first.executionId())
                            ? first.context()
                            : second.context(),
                    requested));
        });
        when(engine.execute(any(EngineExecutionRequest.class))).thenAnswer(invocation -> {
            EngineExecutionRequest engineRequest = invocation.getArgument(0);
            return new EngineExecutionResult(
                    engineRequest.executionId(),
                    "dummy",
                    "1.0",
                    engineRequest.preparation().workspace().workspaceId(),
                    REVISION,
                    EngineExecutionState.SUCCEEDED,
                    START,
                    START.plusSeconds(1),
                    Duration.ofSeconds(1));
        });
        when(workspaceManager.release(any())).thenAnswer(invocation ->
                ((WorkspaceDescriptor) invocation.getArgument(0))
                        .transitionTo(WorkspaceState.IN_USE, null)
                        .transitionTo(WorkspaceState.RELEASING, null)
                        .transitionTo(WorkspaceState.RELEASED, null));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> orchestrator.execute(first));
            var secondFuture = executor.submit(() -> orchestrator.execute(second));

            assertThat(firstFuture.get(10, TimeUnit.SECONDS).engineResult().executionId())
                    .isEqualTo(first.executionId());
            assertThat(secondFuture.get(10, TimeUnit.SECONDS).engineResult().executionId())
                    .isEqualTo(second.executionId());
        }
        verify(engine, org.mockito.Mockito.times(2))
                .execute(any(EngineExecutionRequest.class));
        verify(workspaceManager, org.mockito.Mockito.times(2)).release(any());
    }

    private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
            assertFailure(String code, String message) {
        return assertThatThrownBy(() -> orchestrator.execute(request))
                .isInstanceOf(ExecutionOrchestrationException.class)
                .hasMessage(message)
                .satisfies(exception -> assertThat(
                        ((ExecutionOrchestrationException) exception).code())
                        .isEqualTo(code));
    }

    private void assertInvalidRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ExecutionOrchestrationException.class)
                .satisfies(exception -> assertThat(
                        ((ExecutionOrchestrationException) exception).code())
                        .isEqualTo("INVALID_EXECUTION_REQUEST"));
    }

    private EngineExecutionResult result(EngineExecutionState state) {
        return new EngineExecutionResult(
                request.executionId(),
                "dummy",
                "1.0",
                preparation.workspace().workspaceId(),
                REVISION,
                state,
                START,
                START.plusSeconds(2),
                Duration.ofSeconds(2));
    }

    private ExecutionOrchestrationRequest request() {
        UUID executionId = UUID.randomUUID();
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://example.invalid/repository.git",
                REVISION,
                null);
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()),
                executionId,
                new WorkspaceProviderId("local"));
        return new ExecutionOrchestrationRequest(
                context(executionId),
                new SourcePreparationRequest(planned, source));
    }

    private SourcePreparationResult preparation(ExecutionOrchestrationRequest request) {
        WorkspaceDescriptor ready = request.preparationRequest().workspace()
                .transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(
                        WorkspaceState.READY,
                        new WorkspaceMetadata(START, request.preparationRequest().sourceReference()));
        SourceMaterializationResult source = new SourceMaterializationResult(
                ready.workspaceId(),
                SourceType.GIT_HTTPS,
                REVISION,
                SourceMaterializationState.MATERIALIZED,
                START);
        return new SourcePreparationResult(
                ready, source, SourcePreparationState.PREPARED, START);
    }

    private WorkspaceDescriptor released() {
        return preparation.workspace()
                .transitionTo(WorkspaceState.IN_USE, null)
                .transitionTo(WorkspaceState.RELEASING, null)
                .transitionTo(WorkspaceState.RELEASED, null);
    }

    private ExecutionContext context(UUID executionId) {
        return new ExecutionContext(
                executionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ExecutionSuiteSnapshot(
                        UUID.randomUUID(), "Suite", "dummy", "1.0", "TEST",
                        null, "tests", Map.of(), Map.of()),
                new ExecutionEnvironmentSnapshot(
                        UUID.randomUUID(), "QA", "TEST", "https://example.invalid",
                        Map.of(), Map.of()),
                List.of(),
                Map.of(),
                new ExecutionRunnerContext(
                        UUID.randomUUID(), "runner", "1", "windows", "amd64",
                        Map.of(), Map.of()),
                new ExecutionMetadata(
                        UUID.randomUUID(), START, START, Duration.ofMinutes(5),
                        ExecutionRetryPolicy.DISABLED));
    }
}
