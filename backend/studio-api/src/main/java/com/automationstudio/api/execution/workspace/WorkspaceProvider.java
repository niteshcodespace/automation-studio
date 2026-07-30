package com.automationstudio.api.execution.workspace;

public interface WorkspaceProvider {

    WorkspaceProviderId providerId();

    WorkspacePreparationResult prepare(WorkspacePreparationRequest request);

    WorkspaceReleaseResult release(WorkspaceReleaseRequest request);
}
