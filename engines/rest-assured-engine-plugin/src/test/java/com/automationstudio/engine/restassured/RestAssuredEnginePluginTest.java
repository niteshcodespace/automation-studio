package com.automationstudio.engine.restassured;

import static org.junit.jupiter.api.Assertions.*;

import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParser;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParserTest;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RestAssuredEnginePluginTest {

    @Test
    void exposesStableProviderNeutralDescriptor() {
        var descriptor = plugin().descriptor();
        assertEquals("rest-assured", descriptor.engineId());
        assertEquals("6.0.1", descriptor.implementationVersion());
        assertFalse(descriptor.supportedFeatures().contains("http-execution"));
        assertSame(descriptor, plugin().descriptor());
    }

    @Test
    void validationIsNetworkAndSecretInert() {
        TrackingSecretAccess secrets = new TrackingSecretAccess(EXECUTION_ID);
        EngineExecutionRequest request = request(secrets);
        plugin().validate(request.context());
        assertEquals(0, secrets.resolutions);
    }

    @Test
    void executionOnlyParsesManifestAndReturnsZeroArtifactResult() {
        TrackingSecretAccess secrets = new TrackingSecretAccess(EXECUTION_ID);
        EngineExecutionRequest request = request(secrets);
        var result = plugin().execute(request);

        assertEquals(EXECUTION_ID, result.executionId());
        assertEquals(0, secrets.resolutions);
        assertThrows(com.automationstudio.engine.sdk.ArtifactPublicationException.class,
                () -> request.artifactPublisher().publish(new com.automationstudio.engine.sdk.ArtifactPublication(
                        com.automationstudio.engine.sdk.ArtifactCategory.REPORT, "not-published.json",
                        "application/json", Map.of(), output -> output.write(1))));
    }

    @Test
    void rejectsProviderConfigurationAndSanitizesManifestFailure() {
        EngineExecutionRequest valid = request(new TrackingSecretAccess(EXECUTION_ID));
        var configuredContext = new EngineExecutionContext(EXECUTION_ID,
                valid.context().engineIdentity(), valid.context().suiteReference(),
                Map.of("secret", "must-not-be-accepted"), valid.context().environmentBaseUrl(),
                Map.of(), Map.of());
        RestAssuredEngineException invalidConfiguration = assertThrows(
                RestAssuredEngineException.class, () -> plugin().validate(configuredContext));
        assertFalse(invalidConfiguration.getMessage().contains("must-not-be-accepted"));

        EngineExecutionRequest malformed = request(new TrackingSecretAccess(EXECUTION_ID), "{bad");
        RestAssuredEngineException failure = assertThrows(
                RestAssuredEngineException.class, () -> plugin().execute(malformed));
        assertEquals("MALFORMED_JSON", failure.code());
        assertNull(failure.getCause());
        assertEquals(0, failure.getStackTrace().length);
    }

    static final UUID EXECUTION_ID = new UUID(29, 1);

    static RestAssuredEnginePlugin plugin() {
        return new RestAssuredEnginePlugin(new RestAssuredManifestParser(), Clock.fixed(
                Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));
    }

    static EngineExecutionRequest request(TrackingSecretAccess secrets) {
        return request(secrets, RestAssuredManifestParserTest.validManifest());
    }

    static EngineExecutionRequest request(TrackingSecretAccess secrets, String manifest) {
        WorkspaceId workspaceId = new WorkspaceId(new UUID(290, 1));
        var context = new EngineExecutionContext(EXECUTION_ID,
                new EngineIdentity(RestAssuredEnginePlugin.ENGINE_ID,
                        RestAssuredEnginePlugin.IMPLEMENTATION_VERSION),
                "api-manifest.json", Map.of(), "https://example.invalid", Map.of(), Map.of());
        var workspace = new InMemoryWorkspaceAccess(EXECUTION_ID, workspaceId,
                Map.of("api-manifest.json", manifest.getBytes(StandardCharsets.UTF_8)));
        return new EngineExecutionRequest(context, new PreparedSource(workspaceId, "GIT_HTTPS", "abc123"),
                workspace, secrets);
    }

    static final class TrackingSecretAccess implements ExecutionSecretAccess {
        private final UUID executionId;
        int resolutions;
        TrackingSecretAccess(UUID executionId) { this.executionId = executionId; }
        @Override public UUID executionId() { return executionId; }
        @Override public com.automationstudio.engine.sdk.ResolvedSecret resolve(String logicalName) {
            resolutions++;
            throw new AssertionError("AS-029B must not resolve secrets");
        }
    }
}
