package com.automationstudio.api.execution.preparation;

import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.materialization.SourceMaterializationRequest;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import com.automationstudio.api.source.materialization.SourceMaterializer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class SourcePreparationServiceImpl implements SourcePreparationService {

    private final WorkspaceManager workspaceManager;
    private final SourceMaterializer sourceMaterializer;
    private final Clock clock;

    public SourcePreparationServiceImpl(
            WorkspaceManager workspaceManager,
            SourceMaterializer sourceMaterializer,
            Clock clock) {
        this.workspaceManager = Objects.requireNonNull(
                workspaceManager, "Workspace manager must not be null");
        this.sourceMaterializer = Objects.requireNonNull(
                sourceMaterializer, "Source materializer must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public SourcePreparationResult prepare(SourcePreparationRequest request) {
        Objects.requireNonNull(request, "Source preparation request must not be null");
        WorkspaceDescriptor prepared;
        try {
            prepared = workspaceManager.prepare(
                    request.workspace(), request.sourceReference());
        } catch (RuntimeException failure) {
            throw translated(
                    "WORKSPACE_PREPARATION_FAILED",
                    "Workspace preparation failed",
                    failure);
        }

        SourcePreparationException workspaceViolation =
                validateWorkspace(request, prepared);
        if (workspaceViolation != null) {
            throw compensate(prepared, workspaceViolation);
        }

        SourceMaterializationResult materialized;
        try {
            materialized = sourceMaterializer.materialize(
                    new SourceMaterializationRequest(
                            prepared.workspaceId(), request.sourceReference()));
        } catch (RuntimeException failure) {
            throw compensate(
                    prepared,
                    translated(
                            "SOURCE_MATERIALIZATION_FAILED",
                            "Source materialization failed",
                            failure));
        }

        SourcePreparationException sourceViolation =
                validateSource(request.sourceReference(), prepared, materialized);
        if (sourceViolation != null) {
            throw compensate(prepared, sourceViolation);
        }

        return new SourcePreparationResult(
                prepared,
                materialized,
                SourcePreparationState.PREPARED,
                OffsetDateTime.now(clock));
    }

    private SourcePreparationException validateWorkspace(
            SourcePreparationRequest request,
            WorkspaceDescriptor prepared) {
        if (prepared == null
                || prepared.state() != WorkspaceState.READY
                || !request.workspace().workspaceId().equals(prepared.workspaceId())
                || !request.executionId().equals(prepared.executionId())
                || !request.workspace().providerId().equals(prepared.providerId())
                || prepared.metadata() == null
                || !request.sourceReference().equals(prepared.metadata().sourceReference())) {
            return invariantViolation();
        }
        return null;
    }

    private SourcePreparationException validateSource(
            ExecutionSourceReference expected,
            WorkspaceDescriptor prepared,
            SourceMaterializationResult materialized) {
        if (materialized == null
                || materialized.state() != SourceMaterializationState.MATERIALIZED
                || !prepared.workspaceId().equals(materialized.workspaceId())
                || expected.sourceType() != materialized.sourceType()
                || !expected.revision().equals(materialized.resolvedRevision())) {
            return invariantViolation();
        }
        return null;
    }

    private SourcePreparationException compensate(
            WorkspaceDescriptor workspace,
            SourcePreparationException original) {
        if (workspace == null
                || workspace.state() != WorkspaceState.READY) {
            return original;
        }
        try {
            workspaceManager.release(workspace);
            return original;
        } catch (RuntimeException cleanupFailure) {
            cleanupFailure.addSuppressed(original);
            return translated(
                    "WORKSPACE_COMPENSATION_FAILED",
                    "Workspace compensation failed",
                    cleanupFailure);
        }
    }

    private static SourcePreparationException invariantViolation() {
        return new SourcePreparationException(
                "PREPARATION_INVARIANT_VIOLATION",
                "Source preparation returned inconsistent evidence");
    }

    private static SourcePreparationException translated(
            String code,
            String message,
            Throwable cause) {
        if (cause instanceof SourcePreparationException sourcePreparationException
                && sourcePreparationException.code().equals(code)) {
            return sourcePreparationException;
        }
        return new SourcePreparationException(code, message, cause);
    }
}
