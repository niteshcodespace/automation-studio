package com.automationstudio.engine.sdk;

import java.util.Objects;
import java.util.UUID;

public record EngineExecutionRequest(
        EngineExecutionContext context,
        PreparedSource preparedSource,
        WorkspaceAccess workspaceAccess,
        ExecutionSecretAccess secretAccess) {

    public EngineExecutionRequest {
        context = Objects.requireNonNull(context, "Engine context must not be null");
        preparedSource = Objects.requireNonNull(preparedSource, "Prepared source must not be null");
        workspaceAccess = Objects.requireNonNull(workspaceAccess, "Workspace access must not be null");
        secretAccess = Objects.requireNonNull(secretAccess, "Secret access must not be null");
        if (!context.executionId().equals(workspaceAccess.executionId())
                || !context.executionId().equals(secretAccess.executionId())
                || !preparedSource.workspaceId().equals(workspaceAccess.workspaceId())) {
            throw new IllegalArgumentException("Engine request identities are inconsistent");
        }
    }

    public UUID executionId() {
        return context.executionId();
    }

    public EngineExecutionRequest validateFor(ExecutionEngineDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Execution engine descriptor must not be null");
        if (!context.engineIdentity().equals(descriptor.identity())) {
            throw new IllegalArgumentException("Engine request does not target the selected engine");
        }
        return this;
    }

    @Override
    public String toString() {
        return "EngineExecutionRequest[executionId=" + executionId() + ", capabilities=REDACTED]";
    }
}
