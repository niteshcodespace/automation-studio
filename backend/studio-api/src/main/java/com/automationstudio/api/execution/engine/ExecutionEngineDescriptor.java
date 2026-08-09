package com.automationstudio.api.execution.engine;

import java.util.Set;

/** Deprecated platform name retained as a stateless compatibility view of the SDK descriptor. */
@Deprecated(forRemoval = false)
public final class ExecutionEngineDescriptor
        extends com.automationstudio.engine.sdk.ExecutionEngineDescriptor {

    public ExecutionEngineDescriptor(
            String engineId,
            String implementationVersion,
            String displayName,
            Set<String> supportedCapabilities,
            Set<String> supportedFeatures) {
        super(engineId, implementationVersion, displayName, supportedCapabilities, supportedFeatures);
    }
}
