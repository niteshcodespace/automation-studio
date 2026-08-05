package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record EngineExecutionResult(
        UUID executionId,
        String engineId,
        String implementationVersion,
        WorkspaceId workspaceId,
        String resolvedRevision,
        EngineExecutionState state,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Duration duration) {

    public EngineExecutionResult {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        engineId = requireText(engineId, "Engine ID");
        implementationVersion = requireText(
                implementationVersion, "Engine implementation version");
        workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        resolvedRevision = requireText(resolvedRevision, "Resolved revision");
        state = Objects.requireNonNull(state, "Engine execution state must not be null");
        startedAt = Objects.requireNonNull(startedAt, "Engine start time must not be null");
        finishedAt = Objects.requireNonNull(finishedAt, "Engine finish time must not be null");
        duration = Objects.requireNonNull(duration, "Engine duration must not be null");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "Engine finish time must not precede start time");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Engine duration must not be negative");
        }
        if (!Duration.between(startedAt, finishedAt).equals(duration)) {
            throw new IllegalArgumentException(
                    "Engine duration must match its timestamps");
        }
    }

    public EngineExecutionResult validateFor(
            EngineExecutionRequest request, ExecutionEngineDescriptor descriptor) {
        EngineExecutionRequest invocation = Objects.requireNonNull(
                request, "Engine execution request must not be null");
        ExecutionEngineDescriptor selected = Objects.requireNonNull(
                descriptor, "Execution engine descriptor must not be null");
        if (!executionId.equals(invocation.executionId())
                || !engineId.equals(selected.engineId())
                || !implementationVersion.equals(selected.implementationVersion())
                || !workspaceId.equals(invocation.preparation().workspace().workspaceId())
                || !resolvedRevision.equals(
                        invocation.preparation().source().resolvedRevision())) {
            throw new IllegalArgumentException(
                    "Engine result does not match its execution request");
        }
        return this;
    }

    /**
     * Compatibility alias for callers written before canonical engine ID terminology.
     */
    @Deprecated(forRemoval = false)
    public String engineName() {
        return engineId;
    }

    /**
     * Compatibility alias for callers written before implementation-version terminology.
     */
    @Deprecated(forRemoval = false)
    public String engineVersion() {
        return implementationVersion;
    }

    @Override
    public String toString() {
        return "EngineExecutionResult[executionId=" + executionId
                + ", engineId=" + engineId
                + ", implementationVersion=" + implementationVersion
                + ", workspaceId=" + workspaceId
                + ", state=" + state + "]";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
