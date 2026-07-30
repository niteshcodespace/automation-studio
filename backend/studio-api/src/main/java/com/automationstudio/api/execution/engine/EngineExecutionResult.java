package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record EngineExecutionResult(
        UUID executionId,
        String engineName,
        String engineVersion,
        WorkspaceId workspaceId,
        String resolvedRevision,
        EngineExecutionState state,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Duration duration) {

    public EngineExecutionResult {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        engineName = requireText(engineName, "Engine name");
        engineVersion = requireText(engineVersion, "Engine version");
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
