package com.automationstudio.api.execution.secret;

import java.util.Objects;
import java.util.UUID;

public interface ExecutionSecretAccess {

    UUID executionId();

    ResolvedSecret resolve(String logicalName);

    static ExecutionSecretAccess unavailable(UUID executionId) {
        UUID validatedExecutionId = Objects.requireNonNull(
                executionId, "Execution ID must not be null");
        return new ExecutionSecretAccess() {
            @Override
            public UUID executionId() {
                return validatedExecutionId;
            }

            @Override
            public ResolvedSecret resolve(String logicalName) {
                throw new SecretResolutionException(
                        "SECRET_CAPABILITY_UNAVAILABLE",
                        "Secret capability is unavailable");
            }

            @Override
            public String toString() {
                return "ExecutionSecretAccess[UNAVAILABLE]";
            }
        };
    }
}
