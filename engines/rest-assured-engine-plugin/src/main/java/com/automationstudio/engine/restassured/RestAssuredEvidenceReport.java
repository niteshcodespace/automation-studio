package com.automationstudio.engine.restassured;

import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Builds required evidence exclusively from bounded, non-sensitive structural values. */
final class RestAssuredEvidenceReport {
    static final String LOGICAL_NAME = "rest-assured-sanitized-summary.json";
    static final String MEDIA_TYPE = "application/json";
    static final int MAX_REPORT_BYTES = 65_536;
    private static final ObjectMapper JSON = new ObjectMapper();

    private RestAssuredEvidenceReport() { }

    static void publish(ArtifactPublisher publisher, UUID executionId, String outcome,
            List<RequestSummary> requests) {
        Objects.requireNonNull(publisher, "Artifact publisher must not be null");
        if (!executionId.equals(publisher.executionId())) {
            throw new IllegalArgumentException("Artifact publisher execution is inconsistent");
        }
        byte[] content = serialize(executionId, outcome, requests);
        publisher.publish(new ArtifactPublication(ArtifactCategory.REPORT, LOGICAL_NAME, MEDIA_TYPE,
                Map.of("schemaVersion", "1", "engineId", RestAssuredEnginePlugin.ENGINE_ID),
                output -> output.write(content)));
    }

    private static byte[] serialize(UUID executionId, String outcome,
            List<RequestSummary> requests) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("engineId", RestAssuredEnginePlugin.ENGINE_ID);
        report.put("engineVersion", RestAssuredEnginePlugin.IMPLEMENTATION_VERSION);
        report.put("executionId", executionId.toString());
        report.put("outcome", outcome);
        report.put("requests", List.copyOf(requests));
        try {
            byte[] content = JSON.writeValueAsBytes(report);
            if (content.length > MAX_REPORT_BYTES) {
                throw RestAssuredEnginePlugin.failure("EVIDENCE_TOO_LARGE",
                        "REST Assured evidence is too large");
            }
            return content;
        } catch (RestAssuredEngineException exception) {
            throw exception;
        } catch (Exception exception) {
            throw RestAssuredEnginePlugin.failure("EVIDENCE_SERIALIZATION_FAILED",
                    "REST Assured evidence could not be serialized");
        }
    }

    record RequestSummary(String scenarioId, String requestId, String method, Integer statusCode,
            String outcome, int assertionCount, boolean assertionsPassed, int attemptCount) { }
}
