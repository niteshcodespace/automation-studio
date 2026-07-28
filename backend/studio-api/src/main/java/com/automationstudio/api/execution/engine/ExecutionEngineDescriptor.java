package com.automationstudio.api.execution.engine;

import java.util.Objects;
import java.util.Set;

public record ExecutionEngineDescriptor(
        String engineName,
        String engineVersion,
        String displayName,
        Set<String> supportedCapabilities,
        Set<String> supportedFeatures) {

    public ExecutionEngineDescriptor {
        engineName = requireText(engineName, "Engine name");
        engineVersion = requireText(engineVersion, "Engine version");
        displayName = requireText(displayName, "Engine display name");
        supportedCapabilities = copyNames(supportedCapabilities, "Supported capabilities");
        supportedFeatures = copyNames(supportedFeatures, "Supported features");
    }

    private static Set<String> copyNames(Set<String> values, String name) {
        Set<String> copy = Set.copyOf(Objects.requireNonNull(values, name + " must not be null"));
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain nonblank values");
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
