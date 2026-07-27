package com.automationstudio.api.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SchedulingRequirements(
        String engineId,
        Map<String, Object> requiredCapabilities,
        Map<String, String> requiredLabels) {

    public SchedulingRequirements {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("Scheduling engine ID must not be blank");
        }
        engineId = engineId.trim();
        requiredCapabilities = copyObject(requiredCapabilities);
        requiredLabels = requiredLabels == null
                ? Map.of()
                : Map.copyOf(requiredLabels);
    }

    @Override
    public Map<String, Object> requiredCapabilities() {
        return copyObject(requiredCapabilities);
    }

    private static Map<String, Object> copyObject(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return Map.copyOf(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) ->
                    copy.put(String.valueOf(key), copyValue(nested)));
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item)));
            return List.copyOf(copy);
        }
        return value;
    }
}
