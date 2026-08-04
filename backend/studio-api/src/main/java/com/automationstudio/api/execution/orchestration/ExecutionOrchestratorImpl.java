package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.ExecutionEngine;
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
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class ExecutionOrchestratorImpl implements ExecutionOrchestrator {

    private final SourcePreparationService preparationService;
    private final ExecutionEngineRegistry engineRegistry;
    private final WorkspaceManager workspaceManager;
    private final ExecutionSecretScopeFactory secretScopeFactory;
    private final Clock clock;

    public ExecutionOrchestratorImpl(
            SourcePreparationService preparationService,
            ExecutionEngineRegistry engineRegistry,
            WorkspaceManager workspaceManager,
            ExecutionSecretScopeFactory secretScopeFactory,
            Clock clock) {
        this.preparationService = Objects.requireNonNull(
                preparationService, "Source preparation service must not be null");
        this.engineRegistry = Objects.requireNonNull(
                engineRegistry, "Execution engine registry must not be null");
        this.workspaceManager = Objects.requireNonNull(
                workspaceManager, "Workspace manager must not be null");
        this.secretScopeFactory = Objects.requireNonNull(
                secretScopeFactory, "Execution secret scope factory must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
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

        EngineExecutionResult engineResult;
        try {
            ExecutionEngine engine = support.engine();
            engineResult = engine.execute(
                    new EngineExecutionRequest(
                            request.context(), preparation, secretScope));
        } catch (RuntimeException failure) {
            throw cleanup(
                    secretScope,
                    preparation.workspace(),
                    failure(
                            "ENGINE_EXECUTION_FAILED",
                            "Execution engine failed",
                            failure));
        }

        ExecutionOrchestrationException resultViolation =
                validateEngineResult(request, preparation, support, engineResult);
        if (resultViolation != null) {
            throw cleanup(secretScope, preparation.workspace(), resultViolation);
        }

        ExecutionOrchestrationException cleanupFailure = cleanup(
                secretScope, preparation.workspace(), null);
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
        return new ExecutionOrchestrationResult(
                engineResult, OffsetDateTime.now(clock));
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
                || !support.descriptor().engineId().equals(result.engineName())
                || !support.descriptor().implementationVersion().equals(result.engineVersion())
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

    private static ExecutionOrchestrationException failure(
            String code,
            String message,
            Throwable cause) {
        return cause == null
                ? new ExecutionOrchestrationException(code, message)
                : new ExecutionOrchestrationException(code, message, cause);
    }
}
