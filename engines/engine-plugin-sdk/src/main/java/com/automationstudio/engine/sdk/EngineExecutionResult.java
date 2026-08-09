package com.automationstudio.engine.sdk;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public class EngineExecutionResult {

    private final UUID executionId;
    private final String engineId;
    private final String implementationVersion;
    private final WorkspaceId workspaceId;
    private final String resolvedRevision;
    private final EngineExecutionState state;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime finishedAt;
    private final Duration duration;

    public EngineExecutionResult(
            UUID executionId, String engineId, String implementationVersion,
            WorkspaceId workspaceId, String resolvedRevision, EngineExecutionState state,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, Duration duration) {
        this.executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        EngineIdentity identity = new EngineIdentity(engineId, implementationVersion);
        this.engineId = identity.engineId();
        this.implementationVersion = identity.implementationVersion();
        this.workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        if (resolvedRevision == null || resolvedRevision.isBlank()) {
            throw new IllegalArgumentException("Resolved revision must not be blank");
        }
        this.resolvedRevision = resolvedRevision;
        this.state = Objects.requireNonNull(state, "Engine execution state must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "Engine start time must not be null");
        this.finishedAt = Objects.requireNonNull(finishedAt, "Engine finish time must not be null");
        this.duration = Objects.requireNonNull(duration, "Engine duration must not be null");
        if (finishedAt.isBefore(startedAt) || duration.isNegative()
                || !Duration.between(startedAt, finishedAt).equals(duration)) {
            throw new IllegalArgumentException("Engine execution timing is inconsistent");
        }
    }

    public final UUID executionId() { return executionId; }
    public final String engineId() { return engineId; }
    public final String implementationVersion() { return implementationVersion; }
    public final WorkspaceId workspaceId() { return workspaceId; }
    public final String resolvedRevision() { return resolvedRevision; }
    public final EngineExecutionState state() { return state; }
    public final OffsetDateTime startedAt() { return startedAt; }
    public final OffsetDateTime finishedAt() { return finishedAt; }
    public final Duration duration() { return duration; }

    public EngineExecutionResult validateFor(
            EngineExecutionRequest request, ExecutionEngineDescriptor descriptor) {
        Objects.requireNonNull(request, "Engine execution request must not be null");
        Objects.requireNonNull(descriptor, "Execution engine descriptor must not be null");
        if (!executionId.equals(request.executionId())
                || !new EngineIdentity(engineId, implementationVersion).equals(descriptor.identity())
                || !workspaceId.equals(request.preparedSource().workspaceId())
                || !resolvedRevision.equals(request.preparedSource().resolvedRevision())) {
            throw new IllegalArgumentException("Engine result does not match its execution request");
        }
        return this;
    }

    @Override
    public String toString() {
        return "EngineExecutionResult[executionId=" + executionId + ", engineId=" + engineId
                + ", implementationVersion=" + implementationVersion + ", workspaceId="
                + workspaceId + ", state=" + state + "]";
    }
}
