package com.automationstudio.api.execution.workspace;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceId(UUID value) {

    public WorkspaceId {
        value = Objects.requireNonNull(value, "Workspace ID must not be null");
    }
}
