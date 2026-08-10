package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Deprecated platform compatibility name for the canonical SDK result. */
@Deprecated(forRemoval = false)
public final class EngineExecutionResult extends com.automationstudio.engine.sdk.EngineExecutionResult {

    public EngineExecutionResult(
            UUID executionId, String engineId, String implementationVersion,
            WorkspaceId workspaceId, String resolvedRevision, EngineExecutionState state,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, Duration duration) {
        super(executionId, engineId, implementationVersion, workspaceId, resolvedRevision,
                com.automationstudio.engine.sdk.EngineExecutionState.valueOf(state.name()),
                startedAt, finishedAt, duration);
    }

    @Deprecated(forRemoval = false)
    public String engineName() { return engineId(); }

    @Deprecated(forRemoval = false)
    public String engineVersion() { return implementationVersion(); }

    public EngineExecutionResult validateFor(
            EngineExecutionRequest request,
            com.automationstudio.engine.sdk.ExecutionEngineDescriptor descriptor) {
        if (!executionId().equals(request.executionId())
                || !engineId().equals(descriptor.engineId())
                || !implementationVersion().equals(descriptor.implementationVersion())
                || !workspaceId().equals(request.preparation().workspace().workspaceId())
                || !resolvedRevision().equals(request.preparation().source().resolvedRevision())) {
            throw new IllegalArgumentException(
                    "Engine result does not match its execution request");
        }
        return this;
    }
}
