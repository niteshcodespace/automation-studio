package com.automationstudio.api.execution.workspace.local;

import com.automationstudio.api.execution.workspace.WorkspaceId;

public interface WorkspaceLocationResolver {

    LocalWorkspaceLocation resolve(WorkspaceId workspaceId);
}
