package com.automationstudio.api.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImmutableExecutionValue {

    private ImmutableExecutionValue() {
    }

    static Map<String, Object> object(Map<String, ?> source, String name) {
        if (source == null) {
            throw new InvalidExecutionContextException(name + " must not be null");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new InvalidExecutionContextException(
                        name + " must contain nonblank string keys");
            }
            copy.put(key, value(value, name + "." + key));
        });
        return Collections.unmodifiableMap(copy);
    }

    static Object value(Object source, String name) {
        if (source == null
                || source instanceof String
                || source instanceof Number
                || source instanceof Boolean) {
            return source;
        }
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                    throw new InvalidExecutionContextException(
                            name + " must contain nonblank string keys");
                }
                converted.put(stringKey, value(value, name + "." + stringKey));
            });
            return Collections.unmodifiableMap(converted);
        }
        if (source instanceof List<?> list) {
            return list.stream().map(item -> value(item, name)).toList();
        }
        throw new InvalidExecutionContextException(
                name + " contains unsupported value type " + source.getClass().getSimpleName());
    }
}
