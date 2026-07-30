package com.automationstudio.api.execution.workspace;

import com.automationstudio.api.source.ExecutionSourceReference;
import java.util.Objects;

public record WorkspacePreparationRequest(
        WorkspaceDescriptor workspace,
        ExecutionSourceReference sourceReference) {

    public WorkspacePreparationRequest {
        workspace = Objects.requireNonNull(workspace, "Workspace must not be null");
        if (workspace.state() != WorkspaceState.PREPARING) {
            throw new WorkspaceContractException(
                    "Workspace preparation requires PREPARING state");
        }
    }
}
