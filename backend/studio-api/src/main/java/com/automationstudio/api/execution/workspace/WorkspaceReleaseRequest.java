package com.automationstudio.api.execution.workspace;

import java.util.Objects;

public record WorkspaceReleaseRequest(WorkspaceDescriptor workspace) {

    public WorkspaceReleaseRequest {
        workspace = Objects.requireNonNull(workspace, "Workspace must not be null");
        if (workspace.state() != WorkspaceState.RELEASING) {
            throw new WorkspaceContractException(
                    "Workspace release requires RELEASING state");
        }
    }
}
