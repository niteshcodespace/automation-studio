package com.automationstudio.api.execution.preparation;

import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record SourcePreparationResult(
        WorkspaceDescriptor workspace,
        SourceMaterializationResult source,
        SourcePreparationState state,
        OffsetDateTime preparedAt) {

    public SourcePreparationResult {
        workspace = Objects.requireNonNull(workspace, "Workspace must not be null");
        source = Objects.requireNonNull(source, "Materialized source must not be null");
        state = Objects.requireNonNull(state, "Preparation state must not be null");
        preparedAt = Objects.requireNonNull(preparedAt, "Preparation time must not be null");
        if (workspace.state() != WorkspaceState.READY
                || source.state() != SourceMaterializationState.MATERIALIZED
                || !workspace.workspaceId().equals(source.workspaceId())) {
            throw new SourcePreparationException(
                    "PREPARATION_INVARIANT_VIOLATION",
                    "Prepared workspace and source evidence are inconsistent");
        }
    }

    public UUID executionId() {
        return workspace.executionId();
    }
}
