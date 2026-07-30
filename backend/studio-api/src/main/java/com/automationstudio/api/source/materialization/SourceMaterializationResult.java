package com.automationstudio.api.source.materialization;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.source.SourceType;
import java.time.OffsetDateTime;
import java.util.Objects;

public record SourceMaterializationResult(
        WorkspaceId workspaceId,
        SourceType sourceType,
        String resolvedRevision,
        SourceMaterializationState state,
        OffsetDateTime materializedAt) {

    public SourceMaterializationResult {
        workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        sourceType = Objects.requireNonNull(sourceType, "Source type must not be null");
        if (resolvedRevision == null || resolvedRevision.isBlank()) {
            throw new SourceMaterializationException(
                    "INVALID_RESOLVED_REVISION",
                    "Resolved source revision must not be blank");
        }
        state = Objects.requireNonNull(state, "Materialization state must not be null");
        materializedAt = Objects.requireNonNull(
                materializedAt, "Materialization time must not be null");
    }
}
