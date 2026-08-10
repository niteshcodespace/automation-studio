package com.automationstudio.engine.sdk;

import java.util.Objects;

public record PreparedSource(WorkspaceId workspaceId, String sourceType, String resolvedRevision) {

    public PreparedSource {
        workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        sourceType = requireText(sourceType, "Source type");
        resolvedRevision = requireText(resolvedRevision, "Resolved revision");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
