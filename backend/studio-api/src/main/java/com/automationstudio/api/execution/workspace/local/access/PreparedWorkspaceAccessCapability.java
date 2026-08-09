package com.automationstudio.api.execution.workspace.local.access;

import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.engine.sdk.PreparedSourceAccess;
import com.automationstudio.engine.sdk.WorkspaceAccess;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.util.Objects;
import java.util.UUID;

public final class PreparedWorkspaceAccessCapability implements WorkspaceAccess {

    private final SourcePreparationResult preparation;
    private final EngineWorkspaceAccessResolver resolver;

    public PreparedWorkspaceAccessCapability(
            SourcePreparationResult preparation, EngineWorkspaceAccessResolver resolver) {
        this.preparation = Objects.requireNonNull(preparation, "Preparation must not be null");
        this.resolver = Objects.requireNonNull(resolver, "Workspace resolver must not be null");
    }

    @Override
    public UUID executionId() { return preparation.executionId(); }

    @Override
    public WorkspaceId workspaceId() { return preparation.workspace().workspaceId(); }

    @Override
    public PreparedSourceAccess openPreparedSource() {
        return resolver.open(EngineWorkspaceAccessRequest.from(preparation));
    }

    @Override
    public String toString() {
        return "WorkspaceAccess[executionId=" + executionId() + ", workspaceId=" + workspaceId() + "]";
    }
}
