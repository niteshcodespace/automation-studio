package com.automationstudio.engine.sdk;

import java.util.Objects;
import java.util.UUID;

public class WorkspaceId {

    private final UUID value;

    public WorkspaceId(UUID value) {
        this.value = Objects.requireNonNull(value, "Workspace ID must not be null");
    }

    public final UUID value() {
        return value;
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof WorkspaceId that && value.equals(that.value);
    }

    @Override
    public final int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "WorkspaceId[value=" + value + "]";
    }
}
