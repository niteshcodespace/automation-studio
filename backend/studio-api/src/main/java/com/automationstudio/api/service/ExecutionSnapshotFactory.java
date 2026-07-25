package com.automationstudio.api.service;

import com.automationstudio.api.domain.ExecutionSelection;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ExecutionSnapshotFactory {

    private final SensitiveKeyDetector sensitiveKeyDetector;

    public ExecutionSnapshotFactory(SensitiveKeyDetector sensitiveKeyDetector) {
        this.sensitiveKeyDetector = sensitiveKeyDetector;
    }

    public Map<String, Object> environment(Environment environment) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", environment.getId().toString());
        snapshot.put("name", environment.getName());
        snapshot.put("type", environment.getType().name());
        snapshot.put("baseUrl", environment.getBaseUrl());
        snapshot.put("configuration", removeSensitiveValues(environment.getConfiguration()));
        snapshot.put("secretReferences", environment.getSecretReferences());
        return snapshot;
    }

    public Map<String, Object> suite(AutomationSuite suite) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", suite.getId().toString());
        snapshot.put("name", suite.getName());
        snapshot.put("engineType", suite.getEngineType());
        snapshot.put("engineId", suite.getEngineId());
        snapshot.put("suiteType", suite.getSuiteType() == null ? null : suite.getSuiteType().name());
        snapshot.put("suiteReference", suite.getSuiteReference());
        snapshot.put("configuration", removeSensitiveValues(suite.getConfiguration()));
        return snapshot;
    }

    public Map<String, Object> request(
            ExecutionSelection selection, String requester, OffsetDateTime requestedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("selectionMode", selection.getMode().name());
        snapshot.put("testCaseIds", selection.getTestCaseIds().stream()
                .map(UUID::toString)
                .toList());
        snapshot.put("requestedBy", requester);
        snapshot.put("requestedAt", requestedAt.toString());
        return snapshot;
    }

    public Map<String, Object> testCase(AutomationTestCase testCase) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", testCase.getId().toString());
        snapshot.put("name", testCase.getName());
        snapshot.put("caseReference", testCase.getCaseReference());
        snapshot.put("position", testCase.getPosition());
        snapshot.put("configuration", removeSensitiveValues(testCase.getConfiguration()));
        return snapshot;
    }

    private Map<String, Object> removeSensitiveValues(Map<String, Object> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!sensitiveKeyDetector.isSensitive(key)) {
                safe.put(key, sanitizeValue(value));
            }
        });
        return safe;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                String stringKey = String.valueOf(key);
                if (!sensitiveKeyDetector.isSensitive(stringKey)) {
                    nested.put(stringKey, sanitizeValue(nestedValue));
                }
            });
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }

}
