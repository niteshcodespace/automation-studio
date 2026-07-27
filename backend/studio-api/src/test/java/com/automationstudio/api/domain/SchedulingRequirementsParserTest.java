package com.automationstudio.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchedulingRequirementsParserTest {

    private static final UUID ENVIRONMENT_ID = UUID.randomUUID();
    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final OffsetDateTime REQUESTED_AT =
            OffsetDateTime.parse("2026-07-27T10:00:00Z");

    private final SchedulingRequirementsParser parser = new SchedulingRequirementsParser();

    @Test
    void parsesImmutableEngineCapabilityAndLabelRequirements() {
        Map<String, Object> request = validRequest();
        request.put("requiredCapabilities", Map.of("features", List.of("docker")));
        request.put("requiredLabels", Map.of("region", "eu"));

        SchedulingRequirements requirements = parser.parse(
                ENVIRONMENT_ID,
                SUITE_ID,
                ExecutionSelectionMode.SUITE,
                "requester",
                REQUESTED_AT,
                validEnvironment(),
                validSuite(),
                request);

        assertThat(requirements.engineId()).isEqualTo("playwright-java");
        assertThat(requirements.requiredCapabilities())
                .containsEntry("features", List.of("docker"));
        assertThat(requirements.requiredLabels()).containsEntry("region", "eu");
    }

    @Test
    void missingOrMalformedEngineFailsClosed() {
        Map<String, Object> missing = validSuite();
        missing.remove("engineId");
        Map<String, Object> malformed = validSuite();
        malformed.put("engineId", List.of("playwright-java"));

        assertThatThrownBy(() -> parse(validEnvironment(), missing, validRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(validEnvironment(), malformed, validRequest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedOrInternallyInconsistentSnapshotsFailClosed() {
        Map<String, Object> wrongEnvironment = validEnvironment();
        wrongEnvironment.put("id", UUID.randomUUID().toString());
        Map<String, Object> malformedRequest = validRequest();
        malformedRequest.put("requestedAt", "not-a-time");
        Map<String, Object> inconsistentRequest = validRequest();
        inconsistentRequest.put("selectionMode", "TEST_CASES");

        assertThatThrownBy(() ->
                        parse(wrongEnvironment, validSuite(), validRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        parse(validEnvironment(), validSuite(), malformedRequest))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        parse(validEnvironment(), validSuite(), inconsistentRequest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedCapabilityOrLabelRequirementsFailClosed() {
        Map<String, Object> malformedCapabilities = validRequest();
        malformedCapabilities.put("requiredCapabilities", List.of("docker"));
        Map<String, Object> malformedLabels = validRequest();
        malformedLabels.put("requiredLabels", Map.of("region", 42));

        assertThatThrownBy(() ->
                        parse(validEnvironment(), validSuite(), malformedCapabilities))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        parse(validEnvironment(), validSuite(), malformedLabels))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SchedulingRequirements parse(
            Map<String, Object> environment,
            Map<String, Object> suite,
            Map<String, Object> request) {
        return parser.parse(
                ENVIRONMENT_ID,
                SUITE_ID,
                ExecutionSelectionMode.SUITE,
                "requester",
                REQUESTED_AT,
                environment,
                suite,
                request);
    }

    private Map<String, Object> validEnvironment() {
        return new java.util.LinkedHashMap<>(Map.of(
                "id", ENVIRONMENT_ID.toString(),
                "name", "QA",
                "type", "TEST",
                "baseUrl", "https://example.test",
                "configuration", Map.of(),
                "secretReferences", Map.of()));
    }

    private Map<String, Object> validSuite() {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("id", SUITE_ID.toString());
        snapshot.put("name", "Checkout");
        snapshot.put("engineType", "PLAYWRIGHT");
        snapshot.put("engineId", "playwright-java");
        snapshot.put("suiteType", null);
        snapshot.put("suiteReference", "tests/checkout");
        snapshot.put("configuration", Map.of());
        return snapshot;
    }

    private Map<String, Object> validRequest() {
        return new java.util.LinkedHashMap<>(Map.of(
                "selectionMode", "SUITE",
                "testCaseIds", List.of(),
                "requestedBy", "requester",
                "requestedAt", REQUESTED_AT.toString()));
    }
}
