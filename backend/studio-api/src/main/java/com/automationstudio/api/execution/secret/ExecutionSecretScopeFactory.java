package com.automationstudio.api.execution.secret;

import com.automationstudio.api.execution.ExecutionSecretReference;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

public final class ExecutionSecretScopeFactory {

    private final ExecutionSecretProviderRegistry providers;

    public ExecutionSecretScopeFactory(ExecutionSecretProviderRegistry providers) {
        this.providers = Objects.requireNonNull(
                providers, "Secret provider registry must not be null");
    }

    public ExecutionSecretScope create(
            UUID executionId, Collection<ExecutionSecretReference> references) {
        return new ExecutionSecretScope(executionId, references, providers);
    }
}
