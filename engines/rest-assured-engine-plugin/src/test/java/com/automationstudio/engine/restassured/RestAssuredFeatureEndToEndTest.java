package com.automationstudio.engine.restassured;

import static org.junit.jupiter.api.Assertions.*;

import com.automationstudio.engine.conformance.InMemoryArtifactPublisher;
import com.automationstudio.engine.conformance.InMemoryExecutionSecretAccess;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParser;
import com.automationstudio.engine.restassured.network.RestAssuredNetworkPolicy;
import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.WorkspaceId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestAssuredFeatureEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void executesControlledLoopbackScenarioAndPublishesSanitizedCorrelatedReport() throws Exception {
        String secret = "feature-secret-never-durable";
        UUID executionId = new UUID(29, 6);
        try (Fixture fixture = new Fixture(executionId, secret)) {
            var result = fixture.engine().execute(fixture.request());

            assertEquals(EngineExecutionState.SUCCEEDED, result.state());
            assertEquals(executionId, result.executionId());
            assertEquals(1, fixture.secrets().resolutionCount());
            assertEquals(executionId.toString(), fixture.observedCorrelation());
            assertEquals("Bearer " + secret, fixture.observedAuthorization());

            var publication = fixture.publisher().observations().getFirst();
            assertEquals(ArtifactCategory.REPORT, publication.receipt().category());
            String report = new String(publication.content(), StandardCharsets.UTF_8);
            assertFalse(report.contains(secret));
            assertFalse(report.contains("Authorization"));
            assertFalse(report.contains("response-sensitive-body"));
            var json = JSON.readTree(publication.content());
            assertEquals(executionId.toString(), json.get("executionId").asText());
            assertEquals("SUCCEEDED", json.get("outcome").asText());
            assertEquals(200, json.get("requests").get(0).get("statusCode").asInt());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final UUID executionId;
        private final String secret;
        private final HttpServer server;
        private final InMemoryArtifactPublisher publisher;
        private final InMemoryExecutionSecretAccess secrets;
        private volatile String observedAuthorization;
        private volatile String observedCorrelation;

        private Fixture(UUID executionId, String secret) throws Exception {
            this.executionId = executionId;
            this.secret = secret;
            publisher = new InMemoryArtifactPublisher(executionId);
            secrets = new InMemoryExecutionSecretAccess(
                    executionId, Map.of("api-token", secret.toCharArray()));
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/feature", exchange -> {
                observedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                observedCorrelation = exchange.getRequestHeaders().getFirst("X-Execution-Id");
                byte[] response = "{\"result\":\"ok\",\"private\":\"response-sensitive-body\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        private RestAssuredEnginePlugin engine() {
            var policy = new RestAssuredNetworkPolicy(Set.of(new RestAssuredNetworkPolicy.Origin(
                    "http", "localhost", server.getAddress().getPort())), Duration.ofSeconds(2),
                    Duration.ofSeconds(2), 4096, 8192);
            return new RestAssuredEnginePlugin(new RestAssuredManifestParser(), Clock.systemUTC(), policy);
        }

        private EngineExecutionRequest request() {
            WorkspaceId workspaceId = new WorkspaceId(new UUID(296, 1));
            String manifest = "{\"schemaVersion\":\"1\",\"name\":\"feature\",\"scenarios\":[{"
                    + "\"id\":\"scenario\",\"name\":\"Feature\",\"requests\":[{"
                    + "\"id\":\"request\",\"method\":\"GET\",\"path\":\"/feature\","
                    + "\"authentication\":{\"type\":\"BEARER\",\"secretRef\":\"api-token\"},"
                    + "\"correlationHeader\":\"X-Execution-Id\",\"assertions\":["
                    + "{\"type\":\"STATUS\",\"expected\":\"200\"},"
                    + "{\"type\":\"JSON\",\"expression\":\"$.result\",\"expected\":\"ok\"}]}]}]}";
            var context = new EngineExecutionContext(executionId,
                    new EngineIdentity(RestAssuredEnginePlugin.ENGINE_ID,
                            RestAssuredEnginePlugin.IMPLEMENTATION_VERSION),
                    "api.json", Map.of(), "http://localhost:" + server.getAddress().getPort(),
                    Map.of(), Map.of());
            var workspace = new InMemoryWorkspaceAccess(executionId, workspaceId,
                    Map.of("api.json", manifest.getBytes(StandardCharsets.UTF_8)));
            return new EngineExecutionRequest(context,
                    new PreparedSource(workspaceId, "GIT_HTTPS", "revision"), workspace,
                    secrets, publisher);
        }

        private InMemoryArtifactPublisher publisher() { return publisher; }
        private InMemoryExecutionSecretAccess secrets() { return secrets; }
        private String observedAuthorization() { return observedAuthorization; }
        private String observedCorrelation() { return observedCorrelation; }
        @Override public void close() { server.stop(0); }
    }
}
