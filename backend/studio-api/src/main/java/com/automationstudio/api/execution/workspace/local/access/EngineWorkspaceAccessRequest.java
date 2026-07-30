package com.automationstudio.api.execution.workspace.local.access;

import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public final class EngineWorkspaceAccessRequest {

    private final SourcePreparationResult preparation;

    private EngineWorkspaceAccessRequest(SourcePreparationResult preparation) {
        this.preparation = preparation;
    }

    public static EngineWorkspaceAccessRequest from(SourcePreparationResult preparation) {
        if (preparation == null) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_ACCESS_REQUEST",
                    "Engine workspace access request is incomplete");
        }
        if (preparation.state() != SourcePreparationState.PREPARED
                || preparation.workspace() == null
                || preparation.workspace().workspaceId() == null
                || preparation.workspace().executionId() == null
                || preparation.workspace().state() != WorkspaceState.READY
                || preparation.source() == null
                || preparation.source().resolvedRevision() == null
                || preparation.source().resolvedRevision().isBlank()) {
            throw new EngineWorkspaceAccessException(
                    "WORKSPACE_NOT_PREPARED",
                    "Engine workspace access requires prepared source");
        }
        return new EngineWorkspaceAccessRequest(preparation);
    }

    @JsonProperty
    public UUID executionId() {
        return preparation.executionId();
    }

    @JsonProperty
    public WorkspaceId workspaceId() {
        return preparation.workspace().workspaceId();
    }

    @JsonProperty
    public String resolvedRevision() {
        return preparation.source().resolvedRevision();
    }

    SourcePreparationResult trustedPreparation() {
        return preparation;
    }
}
