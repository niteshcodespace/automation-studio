package com.automationstudio.engine.sdk;

public record EngineIdentity(String engineId, String implementationVersion) {

    public EngineIdentity {
        engineId = requireText(engineId, "Engine ID");
        implementationVersion = requireText(implementationVersion, "Engine implementation version");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
