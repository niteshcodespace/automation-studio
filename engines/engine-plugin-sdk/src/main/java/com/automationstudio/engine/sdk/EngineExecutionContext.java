package com.automationstudio.engine.sdk;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

public record EngineExecutionContext(
        UUID executionId,
        EngineIdentity engineIdentity,
        String suiteReference,
        Map<String, Object> suiteConfiguration,
        String environmentBaseUrl,
        Map<String, Object> environmentConfiguration,
        Map<String, String> variables) {

    public EngineExecutionContext {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        engineIdentity = Objects.requireNonNull(engineIdentity, "Engine identity must not be null");
        if (suiteReference == null || suiteReference.isBlank()) {
            throw new IllegalArgumentException("Suite reference must not be blank");
        }
        suiteConfiguration = immutableObjectMap(suiteConfiguration, "Suite configuration");
        if (environmentBaseUrl == null || environmentBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Environment base URL must not be blank");
        }
        environmentConfiguration = immutableObjectMap(
                environmentConfiguration, "Environment configuration");
        variables = Map.copyOf(Objects.requireNonNull(variables, "Variables must not be null"));
        if (variables.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("Variables must contain nonblank names and values");
        }
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException(name + " must contain nonblank names and values");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, immutableValue(value, name)));
        return Map.copyOf(copy);
    }

    private static Object immutableValue(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey) || stringKey.isBlank() || nested == null) {
                    throw new IllegalArgumentException(name + " contains an invalid nested map");
                }
                copy.put(stringKey, immutableValue(nested, name));
            });
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> {
                if (item == null) {
                    throw new IllegalArgumentException(name + " contains a null list value");
                }
                return immutableValue(item, name);
            }).toList();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException(name + " contains an unsupported value type");
    }
}
