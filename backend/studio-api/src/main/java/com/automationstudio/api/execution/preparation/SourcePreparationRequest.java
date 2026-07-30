package com.automationstudio.api.execution.preparation;

import com.automationstudio.api.execution.workspace.WorkspaceContractException;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.ExecutionSourceReference;
import java.util.Objects;
import java.util.UUID;

public record SourcePreparationRequest(
        WorkspaceDescriptor workspace,
        ExecutionSourceReference sourceReference) {

    public SourcePreparationRequest {
        workspace = Objects.requireNonNull(workspace, "Workspace must not be null");
        sourceReference = Objects.requireNonNull(
                sourceReference, "Source reference must not be null");
        if (workspace.state() != WorkspaceState.PLANNED) {
            throw new WorkspaceContractException(
                    "Source preparation requires a PLANNED workspace");
        }
    }

    public UUID executionId() {
        return workspace.executionId();
    }
}
