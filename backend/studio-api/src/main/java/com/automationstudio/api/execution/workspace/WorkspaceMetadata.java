package com.automationstudio.api.execution.workspace;

import com.automationstudio.api.source.ExecutionSourceReference;
import java.time.OffsetDateTime;
import java.util.Objects;

public record WorkspaceMetadata(
        OffsetDateTime preparedAt,
        ExecutionSourceReference sourceReference) {

    public WorkspaceMetadata {
        preparedAt = Objects.requireNonNull(
                preparedAt, "Workspace preparation time must not be null");
    }
}
