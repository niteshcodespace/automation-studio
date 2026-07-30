package com.automationstudio.api.execution;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionSuiteSnapshot(
        UUID suiteId,
        String suiteName,
        String engineId,
        String engineVersion,
        String engineType,
        String suiteType,
        String suiteReference,
        Map<String, Object> variables,
        Map<String, Object> configuration) {

    public ExecutionSuiteSnapshot {
        suiteId = Objects.requireNonNull(suiteId, "Suite ID must not be null");
        suiteName = requireText(suiteName, "Suite name");
        engineId = requireText(engineId, "Engine ID");
        engineVersion = requireText(engineVersion, "Engine version");
        engineType = requireText(engineType, "Engine type");
        suiteReference = requireText(suiteReference, "Suite reference");
        if (suiteType != null && suiteType.isBlank()) {
            throw new InvalidExecutionContextException("Suite type must not be blank");
        }
        variables = ImmutableExecutionValue.object(variables, "Suite variables");
        configuration = ImmutableExecutionValue.object(configuration, "Suite configuration");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidExecutionContextException(name + " must not be blank");
        }
        return value;
    }
}
