package com.automationstudio.api.execution.workspace;

import java.util.Objects;

public record WorkspaceReleaseResult(
        WorkspaceReleaseRequest request,
        WorkspaceDescriptor workspace) {

    public WorkspaceReleaseResult {
        request = Objects.requireNonNull(request, "Release request must not be null");
        workspace = Objects.requireNonNull(workspace, "Workspace must not be null");
        if (workspace.state() != WorkspaceState.RELEASED) {
            throw new WorkspaceContractException(
                    "Workspace release result requires RELEASED state");
        }
        WorkspaceDescriptor releasing = request.workspace();
        if (!releasing.workspaceId().equals(workspace.workspaceId())
                || !releasing.executionId().equals(workspace.executionId())
                || !releasing.providerId().equals(workspace.providerId())
                || !releasing.metadata().equals(workspace.metadata())) {
            throw new WorkspaceContractException(
                    "Released workspace identity, ownership, and metadata must match the request");
        }
    }
}
