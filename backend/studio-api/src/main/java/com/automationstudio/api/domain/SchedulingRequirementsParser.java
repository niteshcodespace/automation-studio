package com.automationstudio.api.domain;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SchedulingRequirementsParser {

    public SchedulingRequirements parse(
            UUID environmentId,
            UUID suiteId,
            ExecutionSelectionMode selectionMode,
            String requestedBy,
            OffsetDateTime requestedAt,
            Map<String, Object> environmentSnapshot,
            Map<String, Object> suiteSnapshot,
            Map<String, Object> requestSnapshot) {
        requireSnapshot(environmentSnapshot, "environment");
        requireSnapshot(suiteSnapshot, "suite");
        requireSnapshot(requestSnapshot, "request");

        requireUuid(environmentSnapshot, "id", environmentId, "environment");
        requireString(environmentSnapshot, "name", "environment");
        requireString(environmentSnapshot, "type", "environment");
        requireString(environmentSnapshot, "baseUrl", "environment");
        requireObject(environmentSnapshot, "configuration", "environment");
        requireObject(environmentSnapshot, "secretReferences", "environment");

        requireUuid(suiteSnapshot, "id", suiteId, "suite");
        requireString(suiteSnapshot, "name", "suite");
        requireString(suiteSnapshot, "engineType", "suite");
        String engineId = requireString(suiteSnapshot, "engineId", "suite");
        requireString(suiteSnapshot, "suiteReference", "suite");
        requireObject(suiteSnapshot, "configuration", "suite");
        requireNullableString(suiteSnapshot, "suiteType", "suite");

        String snapshotMode = requireString(requestSnapshot, "selectionMode", "request");
        if (!selectionMode.name().equals(snapshotMode)) {
            throw malformed("Request selection mode does not match execution");
        }
        if (!requestedBy.equals(requireString(requestSnapshot, "requestedBy", "request"))) {
            throw malformed("Request actor does not match execution");
        }
        OffsetDateTime snapshotRequestedAt;
        try {
            snapshotRequestedAt = OffsetDateTime.parse(
                    requireString(requestSnapshot, "requestedAt", "request"));
        } catch (DateTimeParseException exception) {
            throw malformed("Request timestamp is invalid", exception);
        }
        if (!snapshotRequestedAt.isEqual(requestedAt)) {
            throw malformed("Request timestamp does not match execution");
        }
        requireUuidList(requestSnapshot, "testCaseIds");

        Map<String, Object> requiredCapabilities =
                optionalObject(requestSnapshot, "requiredCapabilities");
        Map<String, String> requiredLabels =
                optionalStringObject(requestSnapshot, "requiredLabels");
        return new SchedulingRequirements(engineId, requiredCapabilities, requiredLabels);
    }

    private static void requireSnapshot(Map<String, Object> snapshot, String name) {
        if (snapshot == null) {
            throw malformed("Execution " + name + " snapshot is missing");
        }
    }

    private static String requireString(
            Map<String, Object> snapshot, String field, String name) {
        Object value = snapshot.get(field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw malformed("Execution " + name + " snapshot " + field + " is invalid");
        }
        return stringValue;
    }

    private static void requireNullableString(
            Map<String, Object> snapshot, String field, String name) {
        Object value = snapshot.get(field);
        if (value != null && (!(value instanceof String stringValue) || stringValue.isBlank())) {
            throw malformed("Execution " + name + " snapshot " + field + " is invalid");
        }
    }

    private static void requireUuid(
            Map<String, Object> snapshot,
            String field,
            UUID expected,
            String name) {
        try {
            if (!UUID.fromString(requireString(snapshot, field, name)).equals(expected)) {
                throw malformed("Execution " + name + " snapshot identity does not match");
            }
        } catch (IllegalArgumentException exception) {
            throw malformed("Execution " + name + " snapshot identity is invalid", exception);
        }
    }

    private static Map<String, Object> requireObject(
            Map<String, Object> snapshot, String field, String name) {
        Object value = snapshot.get(field);
        if (!(value instanceof Map<?, ?> map)) {
            throw malformed("Execution " + name + " snapshot " + field + " is invalid");
        }
        return copyObject(map, field);
    }

    private static Map<String, Object> optionalObject(
            Map<String, Object> snapshot, String field) {
        if (!snapshot.containsKey(field)) {
            return Map.of();
        }
        return requireObject(snapshot, field, "request");
    }

    private static Map<String, String> optionalStringObject(
            Map<String, Object> snapshot, String field) {
        Map<String, Object> object = optionalObject(snapshot, field);
        Map<String, String> result = new LinkedHashMap<>();
        object.forEach((key, value) -> {
            if (key.isBlank() || !(value instanceof String stringValue)
                    || stringValue.isBlank()) {
                throw malformed("Execution request snapshot " + field + " is invalid");
            }
            result.put(key, stringValue);
        });
        return Map.copyOf(result);
    }

    private static Map<String, Object> copyObject(Map<?, ?> source, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                throw malformed("Execution snapshot " + field + " has an invalid key");
            }
            result.put(stringKey, value);
        });
        return result;
    }

    private static void requireUuidList(Map<String, Object> snapshot, String field) {
        Object value = snapshot.get(field);
        if (!(value instanceof List<?> list)) {
            throw malformed("Execution request snapshot " + field + " is invalid");
        }
        for (Object item : list) {
            if (!(item instanceof String stringItem)) {
                throw malformed("Execution request snapshot " + field + " is invalid");
            }
            try {
                UUID.fromString(stringItem);
            } catch (IllegalArgumentException exception) {
                throw malformed(
                        "Execution request snapshot " + field + " is invalid", exception);
            }
        }
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException malformed(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }
}
