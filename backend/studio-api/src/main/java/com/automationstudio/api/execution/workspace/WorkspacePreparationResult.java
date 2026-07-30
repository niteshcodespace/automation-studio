package com.automationstudio.api.execution.workspace;

import java.util.Objects;

public record WorkspacePreparationResult(
        WorkspacePreparationRequest request,
        WorkspaceDescriptor workspace) {

    public WorkspacePreparationResult {
        request = Objects.requireNonNull(request, "Preparation request must not be null");
        workspace = Objects.requireNonNull(workspace, "Workspace must not be null");
        if (workspace.state() != WorkspaceState.READY) {
            throw new WorkspaceContractException(
                    "Workspace preparation result requires READY state");
        }
        requireSameOwnership(request.workspace(), workspace);
        if (!Objects.equals(
                request.sourceReference(), workspace.metadata().sourceReference())) {
            throw new WorkspaceContractException(
                    "Prepared workspace source must match the preparation request");
        }
    }

    private static void requireSameOwnership(
            WorkspaceDescriptor requested,
            WorkspaceDescriptor prepared) {
        if (!requested.workspaceId().equals(prepared.workspaceId())
                || !requested.executionId().equals(prepared.executionId())
                || !requested.providerId().equals(prepared.providerId())) {
            throw new WorkspaceContractException(
                    "Prepared workspace identity and ownership must match the request");
        }
    }
}
