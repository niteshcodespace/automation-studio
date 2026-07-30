package com.automationstudio.api.execution.workspace;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceDescriptor(
        WorkspaceId workspaceId,
        UUID executionId,
        WorkspaceProviderId providerId,
        WorkspaceState state,
        WorkspaceMetadata metadata) {

    public WorkspaceDescriptor {
        workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        providerId = Objects.requireNonNull(providerId, "Workspace provider must not be null");
        state = Objects.requireNonNull(state, "Workspace state must not be null");
        requireMetadataState(state, metadata);
    }

    public static WorkspaceDescriptor planned(
            WorkspaceId workspaceId,
            UUID executionId,
            WorkspaceProviderId providerId) {
        return new WorkspaceDescriptor(
                workspaceId, executionId, providerId, WorkspaceState.PLANNED, null);
    }

    public WorkspaceDescriptor transitionTo(
            WorkspaceState target,
            WorkspaceMetadata preparedMetadata) {
        state.requireTransitionTo(target);
        WorkspaceMetadata targetMetadata = switch (target) {
            case READY -> Objects.requireNonNull(
                    preparedMetadata, "Prepared workspace metadata must not be null");
            case IN_USE, RELEASING, RELEASED -> {
                if (preparedMetadata != null && !preparedMetadata.equals(metadata)) {
                    throw new WorkspaceContractException(
                            "Prepared workspace metadata must not change");
                }
                yield metadata;
            }
            case PLANNED, PREPARING -> {
                if (preparedMetadata != null) {
                    throw new WorkspaceContractException(
                            "Workspace metadata is unavailable before preparation");
                }
                yield null;
            }
        };
        return new WorkspaceDescriptor(
                workspaceId, executionId, providerId, target, targetMetadata);
    }

    private static void requireMetadataState(
            WorkspaceState state,
            WorkspaceMetadata metadata) {
        boolean prepared = switch (state) {
            case READY, IN_USE, RELEASING, RELEASED -> true;
            case PLANNED, PREPARING -> false;
        };
        if (prepared && metadata == null) {
            throw new WorkspaceContractException(
                    "Prepared workspace state requires immutable metadata");
        }
        if (!prepared && metadata != null) {
            throw new WorkspaceContractException(
                    "Workspace metadata is unavailable before preparation");
        }
    }
}
