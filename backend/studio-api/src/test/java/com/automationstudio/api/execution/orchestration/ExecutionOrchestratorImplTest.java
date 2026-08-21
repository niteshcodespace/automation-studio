package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionEnvironmentSnapshot;
import com.automationstudio.api.execution.ExecutionMetadata;
import com.automationstudio.api.execution.ExecutionRetryPolicy;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSecretReference;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataService;
import com.automationstudio.api.execution.artifact.metadata.ArtifactRegistration;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageLimits;
import com.automationstudio.api.execution.artifact.storage.StorageBackedArtifactPublisher;
import com.automationstudio.api.execution.artifact.storage.local.LocalArtifactStorage;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
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
import com.automationstudio.api.execution.secret.ExecutionSecretScope;
import com.automationstudio.api.execution.secret.ExecutionSecretScopeFactory;
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
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class ExecutionOrchestratorImplTest {

    @TempDir Path temporaryDirectory;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:05:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime START =
            OffsetDateTime.parse("2026-07-30T12:00:00Z");
    private static final String REVISION = "0123456789012345678901234567890123456789";

    private SourcePreparationService preparationService;
    private ExecutionEngineRegistry registry;
    private WorkspaceManager workspaceManager;
    private ExecutionSecretScopeFactory secretScopeFactory;
    private ExecutionSecretScope secretScope;
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
        secretScopeFactory = mock(ExecutionSecretScopeFactory.class);
        secretScope = mock(ExecutionSecretScope.class);
        engine = mock(ExecutionEngine.class);
        request = request();
        when(secretScopeFactory.create(
                        request.executionId(), request.context().secretReferences()))
                .thenReturn(secretScope);
        when(secretScope.executionId()).thenReturn(request.executionId());
        orchestrator = new ExecutionOrchestratorImpl(
                preparationService, registry, workspaceManager, secretScopeFactory, CLOCK);
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
        InOrder order = inOrder(
                secretScopeFactory,
                preparationService,
                registry,
                engine,
                secretScope,
                workspaceManager);
        order.verify(secretScopeFactory).create(
                request.executionId(), request.context().secretReferences());
        order.verify(preparationService).prepare(request.preparationRequest());
        order.verify(registry).resolve("dummy", "1.0");
        order.verify(engine).execute(any(EngineExecutionRequest.class));
        order.verify(secretScope).close();
        order.verify(workspaceManager).release(preparation.workspace());
    }

    @Test
    void canonicalInvocationAlwaysReceivesBoundedExecutionControl() {
        when(engine.execute(any(EngineExecutionRequest.class))).thenAnswer(invocation -> {
            EngineExecutionRequest sdkRequest = invocation.getArgument(0);
            assertThat(sdkRequest.executionControl().isBounded()).isTrue();
            assertThat(sdkRequest.executionControl().deadline()).isNotNull();
            return result(EngineExecutionState.SUCCEEDED);
        });

        orchestrator.execute(request);
    }

    @Test
    void trustedPlatformOutcomeCarriesExactObservedCancellationVersion() {
        StorageBackedArtifactPublisher publisher = mock(StorageBackedArtifactPublisher.class);
        ExecutionOrchestratorImpl platformOrchestrator = observedCancellationOrchestrator(
                (plugin, sdkRequest, control) -> {
                    assertThat(control.cancellationRequested()).isTrue();
                    return result(EngineExecutionState.CANCELLED);
                }, publisher);

        PlatformExecutionOrchestrationResult outcome =
                platformOrchestrator.executePlatform(request);

        assertThat(outcome.result().engineResult().state())
                .isEqualTo(com.automationstudio.engine.sdk.EngineExecutionState.CANCELLED);
        assertThat(outcome.observedCancellationVersion()).isEqualTo(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXECUTION_TEARDOWN_FAILED", "EXECUTION_TEARDOWN_TIMEOUT"})
    void teardownFailureAfterCancellationPreservesExactObservedVersion(String code) {
        orchestrator = observedCancellationOrchestrator((plugin, sdkRequest, control) -> {
            assertThat(control.cancellationRequested()).isTrue();
            throw new ExecutionOrchestrationException(code, "sanitized");
        }, mock(StorageBackedArtifactPublisher.class));

        assertFailure(code, "sanitized")
                .satisfies(failure -> assertThat(
                        ((ExecutionOrchestrationException) failure)
                                .observedCancellationVersion()).isEqualTo(7L));
    }

    @Test
    void artifactFailureAfterCancellationPreservesExactObservedVersion() {
        StorageBackedArtifactPublisher publisher = mock(StorageBackedArtifactPublisher.class);
        doThrow(new IllegalStateException("private artifact detail")).when(publisher).complete();
        orchestrator = observedCancellationOrchestrator((plugin, sdkRequest, control) -> {
            assertThat(control.cancellationRequested()).isTrue();
            return result(EngineExecutionState.CANCELLED);
        }, publisher);

        assertFailure("ARTIFACT_PUBLICATION_FAILED", "Required artifact publication failed")
                .satisfies(failure -> assertThat(
                        ((ExecutionOrchestrationException) failure)
                                .observedCancellationVersion()).isEqualTo(7L));
    }

    @Test
    void cleanupFailureAfterCancellationPreservesExactObservedVersion() {
        StorageBackedArtifactPublisher publisher = mock(StorageBackedArtifactPublisher.class);
        orchestrator = observedCancellationOrchestrator((plugin, sdkRequest, control) -> {
            assertThat(control.cancellationRequested()).isTrue();
            return result(EngineExecutionState.CANCELLED);
        }, publisher);
        when(workspaceManager.release(preparation.workspace()))
                .thenThrow(new IllegalStateException("private cleanup detail"));

        assertFailure("WORKSPACE_CLEANUP_FAILED", "Workspace cleanup failed")
                .satisfies(failure -> assertThat(
                        ((ExecutionOrchestrationException) failure)
                                .observedCancellationVersion()).isEqualTo(7L));
    }

    @Test
    void admittedReferencesCreateUnresolvedScopePassedNarrowlyToEngine() {
        ExecutionSecretReference reference = new ExecutionSecretReference(
                "login.password",
                Map.of("provider", "test-provider", "key", "opaque-reference"));
        request = request(List.of(reference));
        preparation = preparation(request);
        when(secretScopeFactory.create(
                        request.executionId(), request.context().secretReferences()))
                .thenReturn(secretScope);
        when(secretScope.executionId()).thenReturn(request.executionId());
        when(preparationService.prepare(request.preparationRequest())).thenReturn(preparation);
        when(engine.execute(any(EngineExecutionRequest.class)))
                .thenReturn(result(EngineExecutionState.SUCCEEDED));
        when(workspaceManager.release(preparation.workspace())).thenReturn(released());

        orchestrator.execute(request);

        org.mockito.ArgumentCaptor<EngineExecutionRequest> invocation =
                org.mockito.ArgumentCaptor.forClass(EngineExecutionRequest.class);
        verify(engine).execute(invocation.capture());
        assertThat(invocation.getValue().secretAccess()).isSameAs(secretScope);
        verify(secretScope, never()).resolve(any());
        verify(secretScope).close();
        assertThat(request.context().variables()).isEmpty();
        assertThat(request.context().secretReferences()).containsExactly(reference);
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
        verify(secretScope).close();
    }

    @Test
    void unknownEngineCleansPreparedWorkspace() {
        when(registry.resolve("dummy", "1.0"))
                .thenThrow(new ExecutionEngineNotFoundException("internal class"));

        assertFailure("ENGINE_NOT_FOUND", "Execution engine was not found");
        verify(workspaceManager).release(preparation.workspace());
        verify(engine, never()).execute(any(EngineExecutionRequest.class));
        verify(secretScope).close();
    }

    @Test
    void engineExceptionIsSanitizedAndCleanupRuns() {
        RuntimeException cause = new IllegalStateException("C:/private/path");
        when(engine.execute(any(EngineExecutionRequest.class))).thenThrow(cause);

        assertFailure("ENGINE_EXECUTION_FAILED", "Execution engine failed")
                .hasCause(cause)
                .hasMessageNotContaining("private");
        verify(workspaceManager).release(preparation.workspace());
        verify(secretScope).close();
    }

    @ParameterizedTest
    @EnumSource(value = EngineExecutionState.class, names = {"FAILED", "CANCELLED"})
    void validNonSuccessTerminalResultsAreReturned(EngineExecutionState state) {
        when(engine.execute(any(EngineExecutionRequest.class))).thenReturn(result(state));

        assertThat(orchestrator.execute(request).engineResult().state().name())
                .isEqualTo(state.name());
        verify(workspaceManager).release(preparation.workspace());
        verify(secretScope).close();
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
            "execution", "engineId", "implementationVersion", "workspace", "revision",
            "state", "started", "finished", "order", "negativeDuration", "durationMismatch"
    })
    void rejectsEveryInvalidEngineResultInvariant(String mismatch) {
        EngineExecutionResult invalid = mock(EngineExecutionResult.class);
        when(invalid.executionId()).thenReturn(request.executionId());
        when(invalid.engineId()).thenReturn("dummy");
        when(invalid.implementationVersion()).thenReturn("1.0");
        when(invalid.workspaceId()).thenReturn(preparation.workspace().workspaceId());
        when(invalid.resolvedRevision()).thenReturn(REVISION);
        when(invalid.state()).thenReturn(
                com.automationstudio.engine.sdk.EngineExecutionState.SUCCEEDED);
        when(invalid.startedAt()).thenReturn(START);
        when(invalid.finishedAt()).thenReturn(START.plusSeconds(2));
        when(invalid.duration()).thenReturn(Duration.ofSeconds(2));
        switch (mismatch) {
            case "execution" -> when(invalid.executionId()).thenReturn(UUID.randomUUID());
            case "engineId" -> when(invalid.engineId()).thenReturn("other");
            case "implementationVersion" ->
                    when(invalid.implementationVersion()).thenReturn("2.0");
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

    @Test
    void secretScopeCreationFailureIsSanitizedBeforePreparation() {
        when(secretScopeFactory.create(any(), any()))
                .thenThrow(new IllegalStateException("provider-reference-canary"));

        assertFailure(
                "SECRET_SCOPE_CREATION_FAILED",
                "Execution secret scope could not be created")
                .hasNoCause()
                .hasMessageNotContaining("canary");
        verify(preparationService, never()).prepare(any());
        verify(registry, never()).resolve(any(), any());
    }

    @Test
    void secretScopeCleanupFailureIsSanitizedAndPrecedesEngineFailure() {
        RuntimeException engineFailure = new IllegalStateException("engine detail");
        doThrow(new IllegalStateException("secret-value-canary"))
                .when(secretScope).close();
        when(engine.execute(any(EngineExecutionRequest.class))).thenThrow(engineFailure);

        assertFailure(
                "SECRET_SCOPE_CLEANUP_FAILED",
                "Execution secret scope cleanup failed")
                .hasNoCause()
                .hasMessageNotContaining("canary")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement()
                        .isInstanceOf(ExecutionOrchestrationException.class));
        verify(workspaceManager).release(preparation.workspace());
    }

    @Test
    void workspaceCleanupRetainsExistingFinalPrecedenceAfterScopeCleanup() {
        RuntimeException workspaceFailure = new IllegalStateException("workspace detail");
        doThrow(new IllegalStateException("secret cleanup detail"))
                .when(secretScope).close();
        when(engine.execute(any(EngineExecutionRequest.class)))
                .thenReturn(result(EngineExecutionState.SUCCEEDED));
        when(workspaceManager.release(preparation.workspace())).thenThrow(workspaceFailure);

        assertFailure("WORKSPACE_CLEANUP_FAILED", "Workspace cleanup failed")
                .hasCause(workspaceFailure)
                .satisfies(failure -> assertThat(workspaceFailure.getSuppressed())
                        .singleElement()
                        .isInstanceOf(ExecutionOrchestrationException.class)
                        .satisfies(suppressed -> assertThat(
                                ((ExecutionOrchestrationException) suppressed).code())
                                .isEqualTo("SECRET_SCOPE_CLEANUP_FAILED")));
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
        when(secretScopeFactory.create(any(), any())).thenAnswer(invocation -> {
            UUID executionId = invocation.getArgument(0);
            ExecutionSecretScope isolated = mock(ExecutionSecretScope.class);
            when(isolated.executionId()).thenReturn(executionId);
            return isolated;
        });
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
                    (com.automationstudio.api.execution.workspace.WorkspaceId)
                            engineRequest.preparedSource().workspaceId(),
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

    @Test
    void bindsProductionPublisherToExecutionAndRegistersBeforeCleanup() {
        ArtifactMetadataService metadata = mock(ArtifactMetadataService.class);
        StorageBackedArtifactPublisher publisher = productionPublisher(metadata);
        orchestrator = productionOrchestrator(publisher);
        when(engine.execute(any(EngineExecutionRequest.class))).thenAnswer(invocation -> {
            EngineExecutionRequest sdkRequest = invocation.getArgument(0);
            assertThat(sdkRequest.artifactPublisher().executionId())
                    .isEqualTo(request.executionId());
            sdkRequest.artifactPublisher().publish(publication("durable evidence"));
            return result(EngineExecutionState.SUCCEEDED);
        });

        orchestrator.execute(request);

        InOrder order = inOrder(engine, metadata, secretScope, workspaceManager);
        order.verify(engine).execute(any(EngineExecutionRequest.class));
        order.verify(metadata).register(any(), any(),
                org.mockito.Mockito.eq(request.executionId()), any());
        order.verify(secretScope).close();
        order.verify(workspaceManager).release(preparation.workspace());
        assertThatThrownBy(() -> publisher.publish(publication("late")))
                .isInstanceOf(ArtifactPublicationException.class);
    }

    @Test
    void retainsRegisteredArtifactWhenEngineFailsAfterPublication() {
        ArtifactMetadataService metadata = mock(ArtifactMetadataService.class);
        StorageBackedArtifactPublisher publisher = productionPublisher(metadata);
        orchestrator = productionOrchestrator(publisher);
        when(engine.execute(any(EngineExecutionRequest.class))).thenAnswer(invocation -> {
            ((EngineExecutionRequest) invocation.getArgument(0)).artifactPublisher()
                    .publish(publication("failure evidence"));
            throw new IllegalStateException("engine detail");
        });

        assertFailure("ENGINE_EXECUTION_FAILED", "Execution engine failed");

        verify(metadata).register(any(), any(),
                org.mockito.Mockito.eq(request.executionId()), any());
        assertThat(publisher.finalizedArtifacts()).isEqualTo(1);
        verify(secretScope).close();
        verify(workspaceManager).release(preparation.workspace());
    }

    @Test
    void swallowedPublicationFailureStillFailsRequiredArtifactPolicy() {
        ArtifactMetadataService metadata = mock(ArtifactMetadataService.class);
        when(metadata.register(any(), any(), any(), any())).thenThrow(
                new IllegalStateException("database detail"));
        StorageBackedArtifactPublisher publisher = productionPublisher(metadata);
        orchestrator = productionOrchestrator(publisher);
        when(engine.execute(any(EngineExecutionRequest.class))).thenAnswer(invocation -> {
            try {
                ((EngineExecutionRequest) invocation.getArgument(0)).artifactPublisher()
                        .publish(publication("required"));
            } catch (ArtifactPublicationException ignored) {
                // The orchestrator must still fail closed when an engine swallows the failure.
            }
            return result(EngineExecutionState.SUCCEEDED);
        });

        assertFailure("ARTIFACT_PUBLICATION_FAILED", "Required artifact publication failed")
                .hasNoCause();
        verify(secretScope).close();
        verify(workspaceManager).release(preparation.workspace());
    }

    @Test
    void durableArtifactSurvivesWorkspaceCleanup() throws Exception {
        ArtifactMetadataService metadata = mock(ArtifactMetadataService.class);
        Path workspaceRoot = temporaryDirectory.resolve("workspace");
        Path source = workspaceRoot.resolve("evidence.log");
        Files.createDirectories(workspaceRoot);
        Files.writeString(source, "workspace evidence");
        var storage = new LocalArtifactStorage(
                temporaryDirectory.resolve("durable-artifacts"), CLOCK);
        var publisher = new StorageBackedArtifactPublisher(
                request.executionId(), storage,
                new ArtifactStorageLimits("C:/unused-test-root", 1024, 4096, 4, 2),
                request.context().workspaceId(), request.context().projectId(), metadata,
                "retain:default");
        orchestrator = productionOrchestrator(publisher);
        when(engine.execute(any(EngineExecutionRequest.class))).thenAnswer(invocation -> {
            ((EngineExecutionRequest) invocation.getArgument(0)).artifactPublisher().publish(
                    new ArtifactPublication(ArtifactCategory.LOG, "workspace.log", "text/plain",
                            Map.of(), output -> Files.copy(source, output)));
            return result(EngineExecutionState.SUCCEEDED);
        });
        when(workspaceManager.release(preparation.workspace())).thenAnswer(invocation -> {
            Files.delete(source);
            Files.delete(workspaceRoot);
            return released();
        });

        orchestrator.execute(request);

        org.mockito.ArgumentCaptor<ArtifactRegistration> registration =
                org.mockito.ArgumentCaptor.forClass(ArtifactRegistration.class);
        verify(metadata).register(any(), any(), any(), registration.capture());
        assertThat(Files.exists(workspaceRoot)).isFalse();
        assertThat(storage.verify(registration.getValue().storedArtifact())).isTrue();
    }

    @Test
    void malformedResultDoesNotDeleteAlreadyRegisteredEvidence() {
        ArtifactMetadataService metadata = mock(ArtifactMetadataService.class);
        StorageBackedArtifactPublisher publisher = productionPublisher(metadata);
        orchestrator = productionOrchestrator(publisher);
        when(engine.execute(any(EngineExecutionRequest.class))).thenAnswer(invocation -> {
            ((EngineExecutionRequest) invocation.getArgument(0)).artifactPublisher()
                    .publish(publication("diagnostic"));
            return null;
        });

        assertFailure("ENGINE_RESULT_INVARIANT_VIOLATION",
                "Execution engine returned inconsistent evidence");

        verify(metadata).register(any(), any(), any(), any());
        assertThat(publisher.finalizedArtifacts()).isEqualTo(1);
    }

    private ExecutionOrchestrator productionOrchestrator(
            StorageBackedArtifactPublisher publisher) {
        return new ExecutionOrchestratorImpl(
                preparationService, registry, workspaceManager, secretScopeFactory,
                ignored -> { throw new IllegalStateException("unused"); },
                (workspaceId, projectId, executionId) -> publisher,
                CLOCK);
    }

    private ExecutionOrchestratorImpl observedCancellationOrchestrator(
            ExecutionSupervisor supervisor, StorageBackedArtifactPublisher publisher) {
        when(publisher.executionId()).thenReturn(request.executionId());
        return new ExecutionOrchestratorImpl(
                preparationService, registry, workspaceManager, secretScopeFactory,
                ignored -> { throw new IllegalStateException("unused"); },
                (workspaceId, projectId, executionId) -> publisher,
                ignored -> new ExecutionCancellationObservation(true, 7),
                supervisor, CLOCK);
    }

    private StorageBackedArtifactPublisher productionPublisher(
            ArtifactMetadataService metadata) {
        var storage = new LocalArtifactStorage(
                temporaryDirectory.resolve(UUID.randomUUID().toString()), CLOCK);
        var limits = new ArtifactStorageLimits("C:/unused-test-root", 1024, 4096, 4, 2);
        return new StorageBackedArtifactPublisher(
                request.executionId(), storage, limits,
                request.context().workspaceId(), request.context().projectId(), metadata,
                "retain:default");
    }

    private static ArtifactPublication publication(String content) {
        return new ArtifactPublication(
                ArtifactCategory.LOG, "engine.log", "text/plain", Map.of(),
                output -> output.write(content.getBytes(StandardCharsets.UTF_8)));
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
        return request(List.of());
    }

    private ExecutionOrchestrationRequest request(
            List<ExecutionSecretReference> secretReferences) {
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
                context(executionId, secretReferences),
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
        return context(executionId, List.of());
    }

    private ExecutionContext context(
            UUID executionId,
            List<ExecutionSecretReference> secretReferences) {
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
                secretReferences,
                Map.of(),
                new ExecutionRunnerContext(
                        UUID.randomUUID(), "runner", "1", "windows", "amd64",
                        Map.of(), Map.of()),
                new ExecutionMetadata(
                        UUID.randomUUID(), START, START, Duration.ofMinutes(5),
                        ExecutionRetryPolicy.DISABLED));
    }
}
