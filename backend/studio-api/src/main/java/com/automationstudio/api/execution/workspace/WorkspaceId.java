package com.automationstudio.api.execution.workspace;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class WorkspaceId extends com.automationstudio.engine.sdk.WorkspaceId {

    @JsonCreator
    public WorkspaceId(@JsonProperty("value") UUID value) {
        super(value);
    }

    @JsonProperty("value")
    public UUID jsonValue() {
        return value();
    }
}
