package com.automationstudio.engine.sdk;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class ExecutionEngineDescriptor {

    private final String engineId;
    private final String implementationVersion;
    private final String displayName;
    private final Set<String> supportedCapabilities;
    private final Set<String> supportedFeatures;

    public ExecutionEngineDescriptor(
            String engineId,
            String implementationVersion,
            String displayName,
            Set<String> supportedCapabilities,
            Set<String> supportedFeatures) {
        EngineIdentity identity = new EngineIdentity(engineId, implementationVersion);
        this.engineId = identity.engineId();
        this.implementationVersion = identity.implementationVersion();
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Engine display name must not be blank");
        }
        this.displayName = displayName;
        this.supportedCapabilities = copyNames(supportedCapabilities, "Supported capabilities");
        this.supportedFeatures = copyNames(supportedFeatures, "Supported features");
    }

    public final String engineId() { return engineId; }
    public final String implementationVersion() { return implementationVersion; }
    public final String displayName() { return displayName; }
    public final Set<String> supportedCapabilities() { return supportedCapabilities; }
    public final Set<String> supportedFeatures() { return supportedFeatures; }
    public final EngineIdentity identity() { return new EngineIdentity(engineId, implementationVersion); }

    @Deprecated(forRemoval = false)
    public final String engineName() { return engineId; }

    @Deprecated(forRemoval = false)
    public final String engineVersion() { return implementationVersion; }

    @Override
    public final boolean equals(Object other) {
        return other instanceof ExecutionEngineDescriptor that
                && engineId.equals(that.engineId)
                && implementationVersion.equals(that.implementationVersion)
                && displayName.equals(that.displayName)
                && supportedCapabilities.equals(that.supportedCapabilities)
                && supportedFeatures.equals(that.supportedFeatures);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(engineId, implementationVersion, displayName,
                supportedCapabilities, supportedFeatures);
    }

    @Override
    public String toString() {
        return "ExecutionEngineDescriptor[engineId=" + engineId
                + ", implementationVersion=" + implementationVersion
                + ", displayName=" + displayName + "]";
    }

    private static Set<String> copyNames(Set<String> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain nonblank values");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
    }
}
