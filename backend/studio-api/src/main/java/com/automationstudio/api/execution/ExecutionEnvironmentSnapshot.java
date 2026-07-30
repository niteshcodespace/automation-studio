package com.automationstudio.api.execution;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionEnvironmentSnapshot(
        UUID environmentId,
        String environmentName,
        String environmentType,
        String baseUrl,
        Map<String, Object> variables,
        Map<String, Object> configuration) {

    public ExecutionEnvironmentSnapshot {
        environmentId = Objects.requireNonNull(environmentId, "Environment ID must not be null");
        environmentName = requireText(environmentName, "Environment name");
        environmentType = requireText(environmentType, "Environment type");
        baseUrl = requireText(baseUrl, "Environment base URL");
        variables = ImmutableExecutionValue.object(variables, "Environment variables");
        configuration =
                ImmutableExecutionValue.object(configuration, "Environment configuration");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidExecutionContextException(name + " must not be blank");
        }
        return value;
    }
}
