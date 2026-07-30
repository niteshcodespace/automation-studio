package com.automationstudio.api.source.materialization;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.source.ExecutionSourceReference;
import java.util.Objects;

public record SourceMaterializationRequest(
        WorkspaceId workspaceId,
        ExecutionSourceReference sourceReference) {

    public SourceMaterializationRequest {
        workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        sourceReference = Objects.requireNonNull(
                sourceReference, "Source reference must not be null");
    }
}
