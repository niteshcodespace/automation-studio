package com.automationstudio.engine.restassured;

import static org.junit.jupiter.api.Assertions.*;

import com.automationstudio.engine.conformance.InMemoryArtifactPublisher;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParser;
import com.automationstudio.engine.restassured.network.RestAssuredHttpTransport;
import com.automationstudio.engine.restassured.network.RestAssuredNetworkPolicy;
import com.automationstudio.engine.restassured.network.RestAssuredTargetAuthorizer;
import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
import com.automationstudio.engine.sdk.ArtifactPublisher;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.ResolvedSecret;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestAssuredAs029eEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void publishesOneBoundedProviderNeutralSanitizedReport() throws Exception {
        UUID executionId = new UUID(29, 5);
        var publisher = new InMemoryArtifactPublisher(executionId);
        String secret = "token-never-durable";
        String body = "request-body-never-durable";
        execute(executionId, manifest(200, body, secret, 0), publisher,
                new AtomicInteger(), 200, "response-body-never-durable");

        var observation = publisher.observations().getFirst();
        assertEquals(ArtifactCategory.REPORT, observation.receipt().category());
        assertEquals(RestAssuredEvidenceReport.LOGICAL_NAME, observation.receipt().logicalName());
        assertEquals(RestAssuredEvidenceReport.MEDIA_TYPE, observation.receipt().mediaType());
        assertTrue(observation.content().length <= RestAssuredEvidenceReport.MAX_REPORT_BYTES);
        String report = new String(observation.content(), StandardCharsets.UTF_8);
        assertAll(
                () -> assertFalse(report.contains(secret)),
                () -> assertFalse(report.contains(body)),
                () -> assertFalse(report.contains("response-body-never-durable")),
                () -> assertFalse(report.contains("Authorization")),
                () -> assertFalse(report.contains("secretRef")),
                () -> assertFalse(report.contains("view=private")));
        var json = JSON.readTree(observation.content());
        assertEquals(executionId.toString(), json.get("executionId").asText());
        assertEquals("SUCCEEDED", json.get("outcome").asText());
        assertEquals(200, json.get("requests").get(0).get("statusCode").asInt());
        assertEquals(1, json.get("requests").get(0).get("attemptCount").asInt());
    }

    @Test
    void publishesFailedAssertionAndFinalRetrySummary() throws Exception {
        UUID failedId = new UUID(29, 51);
        var failedPublisher = new InMemoryArtifactPublisher(failedId);
        assertEquals(com.automationstudio.engine.sdk.EngineExecutionState.FAILED,
                execute(failedId, manifest(201, "safe", "secret", 0), failedPublisher,
                        new AtomicInteger(), 200, "{}").state());
        var failed = JSON.readTree(failedPublisher.observations().getFirst().content());
        assertEquals("FAILED", failed.get("outcome").asText());
        assertFalse(failed.get("requests").get(0).get("assertionsPassed").asBoolean());

        UUID retryId = new UUID(29, 52);
        var retryPublisher = new InMemoryArtifactPublisher(retryId);
        AtomicInteger attempts = new AtomicInteger();
        execute(retryId, manifest(200, "safe", "secret", 1), retryPublisher,
                attempts, 200, "{}");
        var retry = JSON.readTree(retryPublisher.observations().getFirst().content());
        assertEquals(2, retry.get("requests").get(0).get("attemptCount").asInt());
    }

    @Test
    void requiredPublicationFailureFailsClosed() {
        UUID executionId = new UUID(29, 53);
        ArtifactPublisher failing = new ArtifactPublisher() {
            public UUID executionId() { return executionId; }
            public com.automationstudio.engine.sdk.ArtifactReceipt publish(
                    com.automationstudio.engine.sdk.ArtifactPublication publication) {
                throw new ArtifactPublicationException(
                        "ARTIFACT_PUBLICATION_FAILED", "Artifact publication failed safely");
            }
        };
        assertThrows(ArtifactPublicationException.class,
                () -> execute(executionId, manifest(200, "safe", "secret", 0), failing,
                        new AtomicInteger(), 200, "{}"));
    }

    private com.automationstudio.engine.sdk.EngineExecutionResult execute(UUID executionId,
            String manifest, ArtifactPublisher publisher, AtomicInteger attempts,
            int finalStatus, String responseBody) {
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        var context = new EngineExecutionContext(executionId,
                new EngineIdentity(RestAssuredEnginePlugin.ENGINE_ID,
                        RestAssuredEnginePlugin.IMPLEMENTATION_VERSION),
                "api.json", Map.of(), "https://example.invalid", Map.of(), Map.of());
        var workspace = new InMemoryWorkspaceAccess(executionId, workspaceId,
                Map.of("api.json", manifest.getBytes(StandardCharsets.UTF_8)));
        ExecutionSecretAccess secrets = new ExecutionSecretAccess() {
            public UUID executionId() { return executionId; }
            public ResolvedSecret resolve(String logicalName) {
                return ResolvedSecret.from("token-never-durable".toCharArray());
            }
        };
        var policy = RestAssuredNetworkPolicy.productionDefaults();
        var authorizer = new RestAssuredTargetAuthorizer(policy,
                ignored -> new InetAddress[] { InetAddress.getByName("8.8.8.8") });
        var transport = (com.automationstudio.engine.restassured.network.RestAssuredTransport)
                (target, request, requestBody, authentication) -> {
                    if (attempts.getAndIncrement() == 0 && request.retry().maxRetries() > 0) {
                        throw new RestAssuredEngineException("TRANSPORT_FAILURE", "sanitized");
                    }
                    return new RestAssuredHttpTransport.Response(finalStatus, Map.of(),
                            responseBody.getBytes(StandardCharsets.UTF_8));
                };
        var engine = new RestAssuredEnginePlugin(new RestAssuredManifestParser(),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                authorizer, transport);
        return engine.execute(new EngineExecutionRequest(context,
                new PreparedSource(workspaceId, "GIT_HTTPS", "revision"), workspace,
                secrets, publisher));
    }

    private String manifest(int expectedStatus, String body, String secret, int retries) {
        return "{\"schemaVersion\":\"1\",\"name\":\"evidence\",\"scenarios\":[{"
                + "\"id\":\"scenario-safe\",\"name\":\"safe\",\"requests\":[{"
                + "\"id\":\"request-safe\",\"method\":\"GET\",\"path\":\"/resource\","
                + "\"queryParameters\":{\"view\":\"private\"},"
                + "\"headers\":{\"X-Safe\":\"header-never-durable\"},"
                + "\"body\":{\"mediaType\":\"text/plain\",\"inline\":\"" + body + "\"},"
                + "\"authentication\":{\"type\":\"BEARER\",\"secretRef\":\"" + secret + "\"},"
                + "\"assertions\":[{\"type\":\"STATUS\",\"expected\":\"" + expectedStatus + "\"}],"
                + "\"retry\":{\"maxRetries\":" + retries + ",\"backoffMillis\":0}}]}]}";
    }
}
