package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.execution.engine.EngineExecutionContextProjection;
import com.automationstudio.api.execution.artifact.storage.ArtifactPublisherFactory;
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
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
import com.automationstudio.api.execution.workspace.local.access.PreparedWorkspaceAccessCapability;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class ExecutionOrchestratorImpl implements ExecutionOrchestrator {

    private final SourcePreparationService preparationService;
    private final ExecutionEngineRegistry engineRegistry;
    private final WorkspaceManager workspaceManager;
    private final ExecutionSecretScopeFactory secretScopeFactory;
    private final EngineWorkspaceAccessResolver workspaceAccessResolver;
    private final ArtifactPublisherFactory artifactPublisherFactory;
    private final ExecutionCancellationProbe cancellationProbe;
    private final ExecutionSupervisor executionSupervisor;
    private final Clock clock;

    ExecutionOrchestratorImpl(
            SourcePreparationService preparationService,
            ExecutionEngineRegistry engineRegistry,
            WorkspaceManager workspaceManager,
            ExecutionSecretScopeFactory secretScopeFactory,
            EngineWorkspaceAccessResolver workspaceAccessResolver,
            ArtifactPublisherFactory artifactPublisherFactory,
            ExecutionCancellationProbe cancellationProbe,
            ExecutionSupervisor executionSupervisor,
            Clock clock) {
        this.preparationService = Objects.requireNonNull(
                preparationService, "Source preparation service must not be null");
        this.engineRegistry = Objects.requireNonNull(
                engineRegistry, "Execution engine registry must not be null");
        this.workspaceManager = Objects.requireNonNull(
                workspaceManager, "Workspace manager must not be null");
        this.secretScopeFactory = Objects.requireNonNull(
                secretScopeFactory, "Execution secret scope factory must not be null");
        this.workspaceAccessResolver = Objects.requireNonNull(
                workspaceAccessResolver, "Engine workspace access resolver must not be null");
        this.artifactPublisherFactory = Objects.requireNonNull(
                artifactPublisherFactory, "Artifact publisher factory must not be null");
        this.cancellationProbe = Objects.requireNonNull(
                cancellationProbe, "Execution cancellation probe must not be null");
        this.executionSupervisor = Objects.requireNonNull(
                executionSupervisor, "Execution supervisor must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public ExecutionOrchestratorImpl(
            SourcePreparationService preparationService,
            ExecutionEngineRegistry engineRegistry,
            WorkspaceManager workspaceManager,
            ExecutionSecretScopeFactory secretScopeFactory,
            EngineWorkspaceAccessResolver workspaceAccessResolver,
            ArtifactPublisherFactory artifactPublisherFactory,
            Clock clock) {
        this(preparationService, engineRegistry, workspaceManager, secretScopeFactory,
                workspaceAccessResolver, artifactPublisherFactory,
                ExecutionCancellationProbe.never(), new ExecutionSupervisorImpl(clock), clock);
    }

    /** Compatibility constructor for callers whose engine invocation does not open source access. */
    @Deprecated(forRemoval = false)
    public ExecutionOrchestratorImpl(
            SourcePreparationService preparationService,
            ExecutionEngineRegistry engineRegistry,
            WorkspaceManager workspaceManager,
            ExecutionSecretScopeFactory secretScopeFactory,
            EngineWorkspaceAccessResolver workspaceAccessResolver,
            Clock clock) {
        this(preparationService, engineRegistry, workspaceManager, secretScopeFactory,
                workspaceAccessResolver, ArtifactPublisherFactory.unavailable(), clock);
    }

    /** Compatibility constructor for callers whose engine invocation does not open source access. */
    @Deprecated(forRemoval = false)
    public ExecutionOrchestratorImpl(
            SourcePreparationService preparationService,
            ExecutionEngineRegistry engineRegistry,
            WorkspaceManager workspaceManager,
            ExecutionSecretScopeFactory secretScopeFactory,
            Clock clock) {
        this(
                preparationService,
                engineRegistry,
                workspaceManager,
                secretScopeFactory,
                request -> { throw new IllegalStateException(
                        "Prepared source access is unavailable for this compatibility caller"); },
                ArtifactPublisherFactory.unavailable(),
                clock);
    }

    @Override
    public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
        return executePlatform(request).result();
    }

    PlatformExecutionOrchestrationResult executePlatform(
            ExecutionOrchestrationRequest request) {
        if (request == null) {
            throw new ExecutionOrchestrationException(
                    "INVALID_EXECUTION_REQUEST", "Execution request must not be null");
        }

        ExecutionSecretScope secretScope = createSecretScope(request);
        SourcePreparationResult preparation;
        try {
            preparation = preparationService.prepare(request.preparationRequest());
        } catch (RuntimeException failure) {
            throw cleanup(
                    secretScope,
                    null,
                    failure(
                            "SOURCE_PREPARATION_FAILED",
                            "Source preparation failed",
                            failure));
        }

        ExecutionOrchestrationException preparationViolation =
                validatePreparation(request, preparation);
        if (preparationViolation != null) {
            throw cleanup(
                    secretScope,
                    trustedWorkspace(request, preparation),
                    preparationViolation);
        }

        ExecutionEngineSupport support;
        try {
            support = engineRegistry.resolve(request.engineName(), request.engineVersion());
        } catch (RuntimeException failure) {
            throw cleanup(
                    secretScope,
                    preparation.workspace(),
                    failure("ENGINE_NOT_FOUND", "Execution engine was not found", failure));
        }
        if (support == null
                || support.engine() == null
                || support.descriptor() == null
                || !request.engineName().equals(support.descriptor().engineId())
                || !request.engineVersion().equals(support.descriptor().implementationVersion())) {
            throw cleanup(secretScope, preparation.workspace(), engineInvariant());
        }

        StorageBackedArtifactPublisher artifactPublisher;
        try {
            artifactPublisher = artifactPublisherFactory.create(
                    request.context().workspaceId(), request.context().projectId(),
                    request.executionId());
            if (artifactPublisher == null
                    || !request.executionId().equals(artifactPublisher.executionId())) {
                throw new IllegalStateException("Artifact publisher factory returned invalid scope");
            }
        } catch (RuntimeException failure) {
            throw cleanup(secretScope, preparation.workspace(),
                    failure("ARTIFACT_PUBLISHER_CREATION_FAILED",
                            "Artifact publisher could not be created", null));
        }

        EngineExecutionResult engineResult;
        PlatformExecutionControl executionControl = null;
        try {
            ExecutionEnginePlugin engine = support.engine();
            executionControl = new PlatformExecutionControl(
                    request.context().metadata().timeout(), clock, System::nanoTime,
                    () -> cancellationProbe.observe(request.executionId()));
            EngineExecutionRequest sdkRequest = new EngineExecutionRequest(
                            EngineExecutionContextProjection.from(request.context()),
                            new PreparedSource(
                                    preparation.workspace().workspaceId(),
                                    preparation.source().sourceType().name(),
                                    preparation.source().resolvedRevision()),
                            new PreparedWorkspaceAccessCapability(
                            preparation, workspaceAccessResolver),
                            secretScope,
                            artifactPublisher,
                            executionControl);
            engineResult = executionSupervisor.execute(engine, sdkRequest, executionControl);
        } catch (RuntimeException failure) {
            ExecutionOrchestrationException operationalFailure =
                    failure instanceof ExecutionOrchestrationException orchestrationFailure
                            ? orchestrationFailure
                            : failure(
                                    failure instanceof ArtifactPublicationException
                                            ? "ARTIFACT_PUBLICATION_FAILED"
                                            : "ENGINE_EXECUTION_FAILED",
                                    failure instanceof ArtifactPublicationException
                                            ? "Required artifact publication failed"
                                            : "Execution engine failed",
                                    failure);
            throw preserveCancellationObservation(cleanupArtifactPublisher(
                    artifactPublisher,
                    secretScope,
                    preparation.workspace(),
                    operationalFailure), executionControl);
        }

        ExecutionOrchestrationException resultViolation =
                validateEngineResult(request, preparation, support, engineResult);
        if (resultViolation != null) {
            throw preserveCancellationObservation(cleanupArtifactPublisher(
                    artifactPublisher, secretScope, preparation.workspace(), resultViolation),
                    executionControl);
        }

        try {
            artifactPublisher.complete();
        } catch (RuntimeException failure) {
            throw preserveCancellationObservation(cleanupArtifactPublisher(
                    artifactPublisher, secretScope, preparation.workspace(),
                    failure("ARTIFACT_PUBLICATION_FAILED",
                            "Required artifact publication failed", null)), executionControl);
        }

        ExecutionOrchestrationException cleanupFailure = cleanup(
                secretScope, preparation.workspace(), null);
        if (cleanupFailure != null) {
            throw preserveCancellationObservation(cleanupFailure, executionControl);
        }
        return new PlatformExecutionOrchestrationResult(
                new ExecutionOrchestrationResult(engineResult, OffsetDateTime.now(clock)),
                engineResult.state() == com.automationstudio.engine.sdk.EngineExecutionState.CANCELLED
                        && executionControl.lastCancellationObservation() != null
                        && executionControl.lastCancellationObservation().requested()
                        ? executionControl.lastCancellationObservation().executionVersion()
                        : null);
    }

    private ExecutionOrchestrationException validatePreparation(
            ExecutionOrchestrationRequest request,
            SourcePreparationResult preparation) {
        if (preparation == null
                || preparation.state() != SourcePreparationState.PREPARED
                || !request.executionId().equals(preparation.executionId())
                || preparation.workspace() == null
                || preparation.workspace().state() != WorkspaceState.READY
                || preparation.workspace().workspaceId() == null
                || preparation.source() == null
                || preparation.source().resolvedRevision() == null
                || !request.preparationRequest().sourceReference().revision()
                        .equals(preparation.source().resolvedRevision())
                || preparation.preparedAt() == null) {
            return failure(
                    "SOURCE_PREPARATION_INVARIANT_VIOLATION",
                    "Source preparation returned inconsistent evidence",
                    null);
        }
        return null;
    }

    private ExecutionOrchestrationException validateEngineResult(
            ExecutionOrchestrationRequest request,
            SourcePreparationResult preparation,
            ExecutionEngineSupport support,
            EngineExecutionResult result) {
        if (result == null
                || !request.executionId().equals(result.executionId())
                || !support.descriptor().engineId().equals(result.engineId())
                || !support.descriptor().implementationVersion().equals(
                        result.implementationVersion())
                || !preparation.workspace().workspaceId().equals(result.workspaceId())
                || !preparation.source().resolvedRevision().equals(result.resolvedRevision())
                || result.state() == null
                || result.startedAt() == null
                || result.finishedAt() == null
                || result.finishedAt().isBefore(result.startedAt())
                || result.duration() == null
                || result.duration().isNegative()
                || !Duration.between(result.startedAt(), result.finishedAt())
                        .equals(result.duration())) {
            return engineInvariant();
        }
        return null;
    }

    private WorkspaceDescriptor trustedWorkspace(
            ExecutionOrchestrationRequest request,
            SourcePreparationResult preparation) {
        if (preparation == null || preparation.workspace() == null) {
            return null;
        }
        WorkspaceDescriptor workspace = preparation.workspace();
        return workspace.state() == WorkspaceState.READY
                        && request.executionId().equals(workspace.executionId())
                        && request.preparationRequest().workspace().workspaceId()
                                .equals(workspace.workspaceId())
                        && request.preparationRequest().workspace().providerId()
                                .equals(workspace.providerId())
                ? workspace
                : null;
    }

    private ExecutionSecretScope createSecretScope(
            ExecutionOrchestrationRequest request) {
        try {
            ExecutionSecretScope scope = secretScopeFactory.create(
                    request.executionId(), request.context().secretReferences());
            if (scope == null || !request.executionId().equals(scope.executionId())) {
                if (scope != null) {
                    scope.close();
                }
                throw new IllegalStateException("Secret scope factory returned invalid scope");
            }
            return scope;
        } catch (RuntimeException failure) {
            throw failure(
                    "SECRET_SCOPE_CREATION_FAILED",
                    "Execution secret scope could not be created",
                    null);
        }
    }

    private ExecutionOrchestrationException cleanup(
            ExecutionSecretScope secretScope,
            WorkspaceDescriptor workspace,
            ExecutionOrchestrationException original) {
        ExecutionOrchestrationException afterSecretScope =
                closeSecretScope(secretScope, original);
        ExecutionOrchestrationException workspaceFailure =
                release(workspace, afterSecretScope);
        return workspaceFailure == null ? afterSecretScope : workspaceFailure;
    }

    private ExecutionOrchestrationException cleanupArtifactPublisher(
            StorageBackedArtifactPublisher artifactPublisher,
            ExecutionSecretScope secretScope,
            WorkspaceDescriptor workspace,
            ExecutionOrchestrationException original) {
        try {
            artifactPublisher.abort();
        } catch (RuntimeException cleanupFailure) {
            ExecutionOrchestrationException sanitized = failure(
                    "ARTIFACT_PUBLISHER_CLEANUP_FAILED",
                    "Artifact publisher cleanup failed", null);
            if (original != null) {
                sanitized.addSuppressed(original);
            }
            original = sanitized;
        }
        return cleanup(secretScope, workspace, original);
    }

    private ExecutionOrchestrationException closeSecretScope(
            ExecutionSecretScope secretScope,
            ExecutionOrchestrationException original) {
        try {
            secretScope.close();
            return original;
        } catch (RuntimeException cleanupFailure) {
            ExecutionOrchestrationException sanitized = failure(
                    "SECRET_SCOPE_CLEANUP_FAILED",
                    "Execution secret scope cleanup failed",
                    null);
            if (original != null) {
                sanitized.addSuppressed(original);
            }
            return sanitized;
        }
    }

    private ExecutionOrchestrationException release(
            WorkspaceDescriptor workspace,
            ExecutionOrchestrationException original) {
        if (workspace == null) {
            return null;
        }
        try {
            workspaceManager.release(workspace);
            return null;
        } catch (RuntimeException cleanupFailure) {
            if (original != null) {
                cleanupFailure.addSuppressed(original);
            }
            return failure(
                    "WORKSPACE_CLEANUP_FAILED",
                    "Workspace cleanup failed",
                    cleanupFailure);
        }
    }

    private static ExecutionOrchestrationException engineInvariant() {
        return failure(
                "ENGINE_RESULT_INVARIANT_VIOLATION",
                "Execution engine returned inconsistent evidence",
                null);
    }

    private static ExecutionOrchestrationException preserveCancellationObservation(
            ExecutionOrchestrationException failure, PlatformExecutionControl executionControl) {
        if (executionControl == null) {
            return failure;
        }
        ExecutionCancellationObservation observation =
                executionControl.lastCancellationObservation();
        return observation != null && observation.requested()
                ? failure.withObservedCancellationVersion(observation.executionVersion())
                : failure;
    }

    private static ExecutionOrchestrationException failure(
            String code,
            String message,
            Throwable cause) {
        return cause == null
                ? new ExecutionOrchestrationException(code, message)
                : new ExecutionOrchestrationException(code, message, cause);
    }
}
