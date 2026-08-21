package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionEnvironmentSnapshot;
import com.automationstudio.api.execution.ExecutionMetadata;
import com.automationstudio.api.execution.ExecutionRetryPolicy;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.artifact.storage.StorageBackedArtifactPublisher;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.ExecutionEngineSupport;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationService;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.secret.ExecutionSecretScope;
import com.automationstudio.api.execution.secret.ExecutionSecretScopeFactory;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.integration.IntegrationTestBase;
import com.automationstudio.api.repository.ExecutionHeartbeatRepository;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ExecutionContextService;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class CancellationOperationalErrorPersistenceIntegrationTest extends IntegrationTestBase {

    private static final String ACTOR = "as-031b-persistence-test";
    private static final String RUNNER = "as-031b-persistence-runner";
    private static final String WORKSPACE_PREFIX = "as-031b-persistence-";
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-20T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    private static final WorkspaceProviderId PROVIDER = new WorkspaceProviderId("controlled");
    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            "dummy", "1.0", "Dummy", Set.of(), Set.of());

    @Autowired private ExecutionLeaseRepository leaseRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionHeartbeatRepository databaseTimeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM execution_lease WHERE runner_id = ?", RUNNER);
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", ACTOR);
        jdbcTemplate.update("DELETE FROM environment WHERE project_id IN (SELECT project.id FROM project JOIN workspace ON workspace.id = project.workspace_id WHERE workspace.slug LIKE ?)", WORKSPACE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM test_suite WHERE project_id IN (SELECT project.id FROM project JOIN workspace ON workspace.id = project.workspace_id WHERE workspace.slug LIKE ?)", WORKSPACE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM project WHERE workspace_id IN (SELECT id FROM workspace WHERE slug LIKE ?)", WORKSPACE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_PREFIX + "%");
    }

    @Test
    void cancellationAndTeardownExceptionPersistErrorUsingObservedVersion() {
        Fixture fixture = fixture();
        CapturingOrchestrator orchestrator = orchestrator(
                fixture, blockingEngine(control -> control.registerTeardown(
                        () -> { throw new IllegalStateException("private teardown detail"); })),
                new ExecutionSupervisorImpl(CLOCK), publisher(fixture.executionId()), false);

        RunnerPipelineResult result = coordinator(fixture, orchestrator).execute(fixture.request());

        assertPersistedError(result, fixture.executionId(), 4);
        assertThat(orchestrator.failureCode()).isEqualTo("EXECUTION_TEARDOWN_FAILED");
    }

    @Test
    void cancellationAndTeardownTimeoutPersistErrorUsingObservedVersion() {
        Fixture fixture = fixture();
        AtomicBoolean release = new AtomicBoolean();
        CapturingOrchestrator orchestrator = orchestrator(
                fixture, blockingEngine(control -> control.registerTeardown(() -> {
                    while (!release.get()) LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                })), new ExecutionSupervisorImpl(CLOCK), publisher(fixture.executionId()), false);
        try {
            RunnerPipelineResult result = coordinator(fixture, orchestrator).execute(fixture.request());
            assertPersistedError(result, fixture.executionId(), 4);
            assertThat(orchestrator.failureCode()).isEqualTo("EXECUTION_TEARDOWN_TIMEOUT");
        } finally {
            release.set(true);
        }
    }

    @Test
    void cancellationAndArtifactFinalizationFailurePersistErrorUsingObservedVersion() {
        Fixture fixture = fixture();
        StorageBackedArtifactPublisher publisher = publisher(fixture.executionId());
        doThrow(new IllegalStateException("private artifact detail")).when(publisher).complete();
        CapturingOrchestrator orchestrator = orchestrator(
                fixture, successfulEngine(), observedCancellationSupervisor(), publisher, false);

        RunnerPipelineResult result = coordinator(fixture, orchestrator).execute(fixture.request());

        assertPersistedError(result, fixture.executionId(), 4);
        assertThat(orchestrator.failureCode()).isEqualTo("ARTIFACT_PUBLICATION_FAILED");
    }

    @Test
    void cancellationAndWorkspaceCleanupFailurePersistErrorUsingObservedVersion() {
        Fixture fixture = fixture();
        CapturingOrchestrator orchestrator = orchestrator(
                fixture, successfulEngine(), observedCancellationSupervisor(),
                publisher(fixture.executionId()), true);

        RunnerPipelineResult result = coordinator(fixture, orchestrator).execute(fixture.request());

        assertPersistedError(result, fixture.executionId(), 4);
        assertThat(orchestrator.failureCode()).isEqualTo("WORKSPACE_CLEANUP_FAILED");
    }

    @Test
    void staleObservedVersionIsRejectedWithoutOverwritingPersistentAdvance() {
        Fixture fixture = fixture();
        ExecutionSupervisor staleSupervisor = (plugin, request, control) -> {
            assertThat(control.cancellationRequested()).isTrue();
            jdbcTemplate.update(
                    "UPDATE execution SET error_message = 'unrelated mutation', version = version + 1 WHERE id = ?",
                    fixture.executionId());
            throw new ExecutionOrchestrationException(
                    "EXECUTION_TEARDOWN_FAILED", "Execution teardown failed");
        };
        CapturingOrchestrator orchestrator = orchestrator(
                fixture, successfulEngine(), staleSupervisor,
                publisher(fixture.executionId()), false);

        assertThatThrownBy(() -> coordinator(fixture, orchestrator).execute(fixture.request()))
                .isInstanceOf(ExecutionOwnershipException.class)
                .hasMessageContaining("Execution version");

        Map<String, Object> persisted = persisted(fixture.executionId());
        assertThat(persisted).containsEntry("status", "CANCEL_REQUESTED")
                .containsEntry("version", 4L)
                .containsEntry("error_message", "unrelated mutation");
        assertThat(orchestrator.failureCode()).isEqualTo("EXECUTION_TEARDOWN_FAILED");
    }

    @Test
    void operationalFailureWithoutCancellationUsesStartedVersionAndPersistsError() {
        Fixture fixture = fixture();
        ExecutionSupervisor failureBeforeCancellation = (plugin, request, control) -> {
            assertThat(control.cancellationRequested()).isFalse();
            throw new ExecutionOrchestrationException(
                    "ENGINE_EXECUTION_FAILED", "Execution engine failed");
        };
        CapturingOrchestrator orchestrator = orchestrator(
                fixture, successfulEngine(), failureBeforeCancellation,
                publisher(fixture.executionId()), false, false);

        RunnerPipelineResult result = coordinator(fixture, orchestrator).execute(fixture.request());

        assertPersistedError(result, fixture.executionId(), 3);
        assertThat(orchestrator.failureCode()).isEqualTo("ENGINE_EXECUTION_FAILED");
    }

    private RunnerPipelineCoordinator coordinator(
            Fixture fixture, ExecutionOrchestrator orchestrator) {
        ExecutionContextService contextService = mock(ExecutionContextService.class);
        when(contextService.createContext(fixture.executionId())).thenReturn(fixture.context());
        ExecutionEngineRegistry lifecycleRegistry = mock(ExecutionEngineRegistry.class);
        ExecutionEngineSupport support = new ExecutionEngineSupport(successfulEngine(), DESCRIPTOR);
        when(lifecycleRegistry.validateCompatibility(fixture.context())).thenReturn(support);
        RunnerExecutionService delegate = new RunnerExecutionServiceImpl(
                leaseRepository, executionRepository, databaseTimeRepository,
                contextService, lifecycleRegistry,
                new ExecutionOwnershipValidator(), new ExecutionStateValidator());
        RunnerExecutionService service = new RunnerExecutionService() {
            @Override public ExecutionStartResult start(RunnerExecutionRequest request) {
                return transactionTemplate.execute(status -> delegate.start(request));
            }
            @Override public ExecutionCompletionResult prepareCompletion(
                    RunnerExecutionRequest request) {
                return transactionTemplate.execute(
                        status -> delegate.prepareCompletion(request));
            }
            @Override public ExecutionCompletionResult complete(
                    RunnerExecutionRequest request, ExecutionStatus status) {
                return transactionTemplate.execute(
                        ignored -> delegate.complete(request, status));
            }
        };
        return new RunnerPipelineCoordinatorImpl(
                service, orchestrator,
                new AdmittedSourceSnapshotMapper(new SourceConfigurationValidator()), PROVIDER);
    }

    private CapturingOrchestrator orchestrator(
            Fixture fixture, ExecutionEnginePlugin engine, ExecutionSupervisor supervisor,
            StorageBackedArtifactPublisher publisher, boolean failWorkspaceCleanup) {
        return orchestrator(fixture, engine, supervisor, publisher, failWorkspaceCleanup, true);
    }

    private CapturingOrchestrator orchestrator(
            Fixture fixture, ExecutionEnginePlugin engine, ExecutionSupervisor supervisor,
            StorageBackedArtifactPublisher publisher, boolean failWorkspaceCleanup,
            boolean cancellationRequested) {
        SourcePreparationService preparation = mock(SourcePreparationService.class);
        when(preparation.prepare(any())).thenAnswer(invocation -> {
            var request = (com.automationstudio.api.execution.preparation.SourcePreparationRequest)
                    invocation.getArgument(0);
            WorkspaceDescriptor ready = request.workspace()
                    .transitionTo(WorkspaceState.PREPARING, null)
                    .transitionTo(WorkspaceState.READY,
                            new WorkspaceMetadata(NOW, request.sourceReference()));
            return new SourcePreparationResult(
                    ready,
                    new SourceMaterializationResult(
                            ready.workspaceId(), request.sourceReference().sourceType(),
                            request.sourceReference().revision(),
                            SourceMaterializationState.MATERIALIZED, NOW),
                    SourcePreparationState.PREPARED, NOW);
        });
        ExecutionEngineRegistry registry = mock(ExecutionEngineRegistry.class);
        when(registry.resolve("dummy", "1.0"))
                .thenReturn(new ExecutionEngineSupport(engine, DESCRIPTOR));
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.release(any())).thenAnswer(invocation -> {
            if (failWorkspaceCleanup) throw new IllegalStateException("private workspace detail");
            WorkspaceDescriptor workspace = invocation.getArgument(0);
            return workspace.transitionTo(WorkspaceState.IN_USE, null)
                    .transitionTo(WorkspaceState.RELEASING, null)
                    .transitionTo(WorkspaceState.RELEASED, null);
        });
        ExecutionSecretScope secretScope = mock(ExecutionSecretScope.class);
        when(secretScope.executionId()).thenReturn(fixture.executionId());
        ExecutionSecretScopeFactory secretFactory = mock(ExecutionSecretScopeFactory.class);
        when(secretFactory.create(fixture.executionId(), List.of())).thenReturn(secretScope);
        AtomicBoolean cancellationPersisted = new AtomicBoolean();
        ExecutionCancellationProbe probe = ignored -> {
            if (cancellationRequested && cancellationPersisted.compareAndSet(false, true)) {
                jdbcTemplate.update("UPDATE execution SET status = 'CANCEL_REQUESTED', cancel_requested_at = CURRENT_TIMESTAMP, cancelled_by = 'operator', version = version + 1 WHERE id = ? AND status = 'RUNNING'",
                        fixture.executionId());
            }
            Map<String, Object> state = persisted(fixture.executionId());
            return new ExecutionCancellationObservation(
                    "CANCEL_REQUESTED".equals(state.get("status")),
                    ((Number) state.get("version")).longValue());
        };
        ExecutionOrchestrator delegate = new ExecutionOrchestratorImpl(
                preparation, registry, workspaceManager, secretFactory,
                ignored -> { throw new IllegalStateException("unused"); },
                (workspaceId, projectId, executionId) -> publisher,
                probe, supervisor, CLOCK);
        return new CapturingOrchestrator(delegate);
    }

    private static ExecutionSupervisor observedCancellationSupervisor() {
        return (plugin, request, control) -> {
            assertThat(control.cancellationRequested()).isTrue();
            return result(request, EngineExecutionState.CANCELLED);
        };
    }

    private static ExecutionEnginePlugin successfulEngine() {
        return plugin(request -> result(request, EngineExecutionState.SUCCEEDED));
    }

    private static ExecutionEnginePlugin blockingEngine(
            java.util.function.Consumer<com.automationstudio.engine.sdk.ExecutionControl> setup) {
        return plugin(request -> {
            setup.accept(request.executionControl());
            while (!Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            }
            return result(request, EngineExecutionState.CANCELLED);
        });
    }

    private static ExecutionEnginePlugin plugin(
            java.util.function.Function<EngineExecutionRequest, EngineExecutionResult> execution) {
        return new ExecutionEnginePlugin() {
            @Override public ExecutionEngineDescriptor descriptor() { return DESCRIPTOR; }
            @Override public void validate(
                    com.automationstudio.engine.sdk.EngineExecutionContext context) { }
            @Override public EngineExecutionResult execute(EngineExecutionRequest request) {
                return execution.apply(request);
            }
        };
    }

    private static EngineExecutionResult result(
            EngineExecutionRequest request, EngineExecutionState state) {
        return new EngineExecutionResult(
                request.executionId(), "dummy", "1.0", request.preparedSource().workspaceId(),
                REVISION, state, NOW, NOW, Duration.ZERO);
    }

    private static StorageBackedArtifactPublisher publisher(UUID executionId) {
        StorageBackedArtifactPublisher publisher = mock(StorageBackedArtifactPublisher.class);
        when(publisher.executionId()).thenReturn(executionId);
        return publisher;
    }

    private void assertPersistedError(
            RunnerPipelineResult result, UUID executionId, long expectedVersion) {
        assertThat(result.completion().status()).isEqualTo(ExecutionStatus.ERROR);
        Map<String, Object> persisted = persisted(executionId);
        assertThat(persisted).containsEntry("status", "ERROR")
                .containsEntry("version", expectedVersion);
    }

    private Map<String, Object> persisted(UUID executionId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, version, error_message FROM execution WHERE id = ?", executionId);
    }

    private Fixture fixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("INSERT INTO workspace (id, name, slug, status) VALUES (?, ?, ?, 'ACTIVE')",
                workspaceId, "AS-031B Workspace " + suffix, WORKSPACE_PREFIX + suffix);
        jdbcTemplate.update("INSERT INTO project (id, workspace_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                projectId, workspaceId, "AS-031B Project " + suffix);
        jdbcTemplate.update("INSERT INTO environment (id, project_id, name, base_url, type, status) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE')",
                environmentId, projectId, "AS-031B Environment " + suffix);
        jdbcTemplate.update("INSERT INTO test_suite (id, project_id, name, engine_type, suite_reference, status) VALUES (?, ?, ?, 'BUILTIN', ?, 'ACTIVE')",
                suiteId, projectId, "AS-031B Suite " + suffix, "tests/" + suffix);
        jdbcTemplate.update("INSERT INTO execution (id, project_id, environment_id, test_suite_id, selection_mode, status, requested_by, source_snapshot, version) VALUES (?, ?, ?, ?, 'SUITE', 'CLAIMED', ?, CAST(? AS jsonb), 1)",
                executionId, projectId, environmentId, suiteId, ACTOR,
                "{\"sourceType\":\"GIT_HTTPS\",\"repository\":\"https://example.test/repository.git\",\"revision\":\"" + REVISION + "\",\"sourceLocation\":null}");
        jdbcTemplate.update("INSERT INTO execution_lease (execution_id, runner_id, claim_token, lease_generation, claimed_at, last_heartbeat_at, lease_expires_at, version, created_at, updated_at) VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '2 days', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                executionId, RUNNER, claimToken);
        ExecutionContext context = context(executionId, projectId, workspaceId, suiteId, environmentId);
        return new Fixture(executionId, context,
                new RunnerExecutionRequest(executionId, RUNNER, claimToken, 1, 0, 1));
    }

    private static ExecutionContext context(
            UUID executionId, UUID projectId, UUID workspaceId,
            UUID suiteId, UUID environmentId) {
        return new ExecutionContext(
                executionId, projectId, workspaceId,
                new ExecutionSuiteSnapshot(
                        suiteId, "Suite", "dummy", "1.0", "BUILTIN",
                        null, "tests", Map.of(), Map.of()),
                new ExecutionEnvironmentSnapshot(
                        environmentId, "QA", "TEST", "https://example.test",
                        Map.of(), Map.of()),
                List.of(), Map.of(),
                new ExecutionRunnerContext(
                        UUID.randomUUID(), RUNNER, "1", "windows", "amd64",
                        Map.of(), Map.of()),
                new ExecutionMetadata(
                        UUID.randomUUID(), NOW, NOW, Duration.ofMinutes(5),
                        ExecutionRetryPolicy.DISABLED));
    }

    private record Fixture(
            UUID executionId, ExecutionContext context, RunnerExecutionRequest request) {
    }

    private static final class CapturingOrchestrator implements ExecutionOrchestrator {
        private final ExecutionOrchestrator delegate;
        private String failureCode;

        private CapturingOrchestrator(ExecutionOrchestrator delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
            try {
                return delegate.execute(request);
            } catch (ExecutionOrchestrationException failure) {
                failureCode = failure.code();
                throw failure;
            }
        }

        private String failureCode() {
            return failureCode;
        }
    }
}
