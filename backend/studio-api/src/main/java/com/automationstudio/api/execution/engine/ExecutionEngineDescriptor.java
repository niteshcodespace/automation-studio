package com.automationstudio.api.execution.engine;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ExecutionEngineDescriptor(
        String engineId,
        String implementationVersion,
        String displayName,
        Set<String> supportedCapabilities,
        Set<String> supportedFeatures) {

    public ExecutionEngineDescriptor {
        engineId = requireText(engineId, "Engine ID");
        implementationVersion = requireText(
                implementationVersion, "Engine implementation version");
        displayName = requireText(displayName, "Engine display name");
        supportedCapabilities = copyNames(supportedCapabilities, "Supported capabilities");
        supportedFeatures = copyNames(supportedFeatures, "Supported features");
    }

    private static Set<String> copyNames(Set<String> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain nonblank values");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
    }

    /**
     * Compatibility alias for callers written before the canonical engine ID terminology.
     */
    @Deprecated(forRemoval = false)
    public String engineName() {
        return engineId;
    }

    /**
     * Compatibility alias for callers written before implementation-version terminology.
     */
    @Deprecated(forRemoval = false)
    public String engineVersion() {
        return implementationVersion;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
