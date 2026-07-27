package com.automationstudio.api.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RunnerCapabilities(
        Map<String, Object> capabilities,
        Map<String, String> labels) {

    public RunnerCapabilities {
        if (capabilities == null) {
            throw new IllegalArgumentException("Runner capabilities must not be null");
        }
        Map<String, Object> engines = stringObject(capabilities.get("engines"), "engines");
        Map<String, Object> copy = copyObject(capabilities);
        copy.put("engines", engines);
        capabilities = Map.copyOf(copy);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    public boolean supportsEngine(String engineId) {
        return engines().containsKey(engineId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> engines() {
        return (Map<String, Object>) capabilities.get("engines");
    }

    @Override
    public Map<String, Object> capabilities() {
        return Map.copyOf(copyObject(capabilities));
    }

    private static Map<String, Object> stringObject(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "Runner capability " + name + " must be an object");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, nested) -> {
            if (!(key instanceof String stringKey)
                    || stringKey.isBlank()
                    || !(nested instanceof String stringValue)
                    || stringValue.isBlank()) {
                throw new IllegalArgumentException(
                        "Runner capability " + name + " must contain nonblank strings");
            }
            copy.put(stringKey, stringValue);
        });
        return Map.copyOf(copy);
    }

    private static Map<String, Object> copyObject(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) ->
                copy.put(String.valueOf(key), copyValue(value)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Map.copyOf(copyObject(map));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RunnerCapabilities::copyValue).toList();
        }
        return value;
    }
}
