package com.automationstudio.engine.restassured;

import static org.junit.jupiter.api.Assertions.*;

import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.conformance.InMemoryArtifactPublisher;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParser;
import com.automationstudio.engine.restassured.network.RestAssuredHttpTransport;
import com.automationstudio.engine.restassured.network.RestAssuredNetworkPolicy;
import com.automationstudio.engine.restassured.network.RestAssuredTargetAuthorizer;
import com.automationstudio.engine.restassured.network.RestAssuredTransport;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.ResolvedSecret;
import com.automationstudio.engine.sdk.SecretResolutionException;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RestAssuredAs029dTest {
    private static final UUID EXECUTION = new UUID(29, 4);

    @Test
    void injectsApprovedAuthenticationOnlyAfterAuthorizationAndClosesSecrets() {
        for (var fixture : List.of(
                new AuthFixture("BEARER", "\"secretRef\":\"token\"", Map.of("Authorization", "Bearer s3cr3t")),
                new AuthFixture("BASIC", "\"secretRef\":\"password\",\"usernameSecretRef\":\"username\"",
                        Map.of("Authorization", "Basic dXNlcjpzM2NyM3Q=")),
                new AuthFixture("API_KEY_HEADER", "\"secretRef\":\"token\",\"placement\":\"X-Service-Key\"",
                        Map.of("X-Service-Key", "s3cr3t")))) {
            var secrets = new TrackingSecrets(Map.of("token", "s3cr3t", "password", "s3cr3t", "username", "user"));
            var capture = new CaptureTransport(200, Map.of(), "{}");
            assertTrue(execute(manifest(fixture.type, fixture.fields, null, status()), secrets, capture).state()
                    == com.automationstudio.engine.sdk.EngineExecutionState.SUCCEEDED);
            assertEquals(fixture.expected, capture.authentication);
            assertTrue(secrets.handles.stream().allMatch(ResolvedSecret::isClosed));
        }
    }

    @Test
    void injectsApiKeyQueryWithoutLeakingItToDiagnostics() {
        var secrets = new TrackingSecrets(Map.of("token", "query-secret"));
        var capture = new CaptureTransport(200, Map.of(), "{}");
        execute(manifest("API_KEY_QUERY", "\"secretRef\":\"token\",\"placement\":\"servicekey\"",
                null, status()), secrets, capture);
        assertEquals("view=summary&servicekey=query-secret", capture.target.uri().getRawQuery());
        assertTrue(secrets.handles.getFirst().isClosed());

        var failing = new TrackingSecrets(Map.of());
        RestAssuredEngineException error = assertThrows(RestAssuredEngineException.class,
                () -> execute(manifest("BEARER", "\"secretRef\":\"missing-secret\"", null, status()),
                        failing, capture));
        assertEquals("SECRET_RESOLUTION_FAILED", error.code());
        assertFalse(error.getMessage().contains("missing-secret"));
    }

    @Test
    void closesSecretsAcrossAssertionTransportSchemaAndConstructionFailures() {
        List<FailureFixture> failures = List.of(
                new FailureFixture(new CaptureTransport(500, Map.of(), "{}"), status(), Map.of()),
                new FailureFixture(new ThrowingTransport("TRANSPORT_FAILURE"), status(), Map.of()),
                new FailureFixture(new CaptureTransport(200, Map.of(), "{}"),
                        "{\"type\":\"JSON_SCHEMA\",\"expression\":\"missing.json\"}", Map.of()));
        for (FailureFixture fixture : failures) {
            var secrets = new TrackingSecrets(Map.of("token", "never-visible"));
            try { execute(manifest("BEARER", "\"secretRef\":\"token\"", null, fixture.assertion),
                    secrets, fixture.transport, fixture.files); } catch (RuntimeException ignored) { }
            assertEquals(1, secrets.handles.size());
            assertTrue(secrets.handles.getFirst().isClosed());
        }
        var invalid = new TrackingSecrets(Map.of("token", "line\nbreak"));
        assertThrows(RestAssuredEngineException.class, () -> execute(
                manifest("BEARER", "\"secretRef\":\"token\"", null, status()), invalid,
                new CaptureTransport(200, Map.of(), "{}")));
        assertTrue(invalid.handles.getFirst().isClosed());
    }

    @Test
    void evaluatesHeadersJsonExistenceValuesAndLocalSchemaWithinBounds() {
        String assertions = String.join(",",
                "{\"type\":\"STATUS\",\"expected\":\"200\"}",
                "{\"type\":\"HEADER\",\"name\":\"X-Result\",\"expected\":\"ok\"}",
                "{\"type\":\"JSON\",\"expression\":\"$.user.name\",\"expected\":\"Ada\"}",
                "{\"type\":\"JSON\",\"expression\":\"$.user\",\"expected\":\"__exists__\"}",
                "{\"type\":\"JSON\",\"expression\":\"$.password\",\"expected\":\"__absent__\"}",
                "{\"type\":\"JSON_SCHEMA\",\"expression\":\"schemas/user.json\"}");
        var transport = new CaptureTransport(200, Map.of("x-result", "ok"), "{\"user\":{\"name\":\"Ada\"}}");
        var result = execute(manifest("NONE", "", null, assertions), new TrackingSecrets(Map.of()), transport,
                Map.of("schemas/user.json", "{\"type\":\"object\",\"required\":[\"user\"],"
                        + "\"properties\":{\"user\":{\"type\":\"object\"}}}"));
        assertEquals(com.automationstudio.engine.sdk.EngineExecutionState.SUCCEEDED, result.state());
        assertEquals(com.automationstudio.engine.sdk.EngineExecutionState.FAILED,
                execute(manifest("NONE", "", null,
                        "{\"type\":\"JSON\",\"expression\":\"$.user.name\",\"expected\":\"Grace\"}"),
                        new TrackingSecrets(Map.of()), transport).state());
        assertEquals("RESPONSE_JSON_INVALID", assertThrows(RestAssuredEngineException.class,
                () -> execute(manifest("NONE", "", null,
                        "{\"type\":\"JSON\",\"expression\":\"$.user\",\"expected\":\"__exists__\"}"),
                        new TrackingSecrets(Map.of()), new CaptureTransport(200, Map.of(), "{bad"))).code());
    }

    @Test
    void rejectsRemoteSchemaReferencesAndUnsafeCorrelationCollisionsStructurally() {
        var parser = new RestAssuredManifestParser();
        assertThrows(com.automationstudio.engine.restassured.manifest.RestAssuredManifestException.class,
                () -> parser.parse(manifest("NONE", "", null,
                        "{\"type\":\"JSON_SCHEMA\",\"expression\":\"https://evil/schema\"}")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(com.automationstudio.engine.restassured.manifest.RestAssuredManifestException.class,
                () -> parser.parse(manifest("NONE", "", "Authorization", status()).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void propagatesOnlyExecutionCorrelationAndRetriesOnlyIdempotentTransportFailures() {
        var retry = new AtomicInteger();
        RestAssuredTransport transport = (target, request, body, authentication) -> {
            assertEquals(EXECUTION.toString(), request.headers().get("X-Execution-Correlation"));
            if (retry.getAndIncrement() == 0) throw new RestAssuredEngineException(
                    "TRANSPORT_FAILURE", "REST Assured transport failed");
            return new RestAssuredHttpTransport.Response(200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        };
        String manifest = manifest("NONE", "", "X-Execution-Correlation", status())
                .replace("\"maxRetries\":0", "\"maxRetries\":1");
        assertEquals(com.automationstudio.engine.sdk.EngineExecutionState.SUCCEEDED,
                execute(manifest, new TrackingSecrets(Map.of()), transport).state());
        assertEquals(2, retry.get());

        String post = manifest.replace("\"method\":\"GET\"", "\"method\":\"POST\"");
        assertEquals("RETRY_METHOD_DENIED", assertThrows(RestAssuredEngineException.class,
                () -> execute(post, new TrackingSecrets(Map.of()), transport)).code());
    }

    @Test
    void retryBackoffIsExponentialBoundedAndDeadlineAware() {
        var now = new java.util.concurrent.atomic.AtomicLong(100);
        List<Long> delays = new ArrayList<>();
        var policy = new RestAssuredRetryPolicy(now::get, delays::add);
        long start = policy.start();
        policy.beforeRetry(25, 1, start);
        policy.beforeRetry(25, 2, start);
        assertEquals(List.of(25L, 50L), delays);
        now.set(start + RestAssuredRetryPolicy.MAX_REQUEST_DURATION.toNanos());
        assertEquals("RETRY_DEADLINE_EXCEEDED", assertThrows(RestAssuredEngineException.class,
                () -> policy.beforeRetry(0, 3, start)).code());
    }

    private com.automationstudio.engine.sdk.EngineExecutionResult execute(String manifest,
            TrackingSecrets secrets, RestAssuredTransport transport) {
        return execute(manifest, secrets, transport, Map.of());
    }

    private com.automationstudio.engine.sdk.EngineExecutionResult execute(String manifest,
            TrackingSecrets secrets, RestAssuredTransport transport, Map<String, String> extraFiles) {
        WorkspaceId workspaceId = new WorkspaceId(new UUID(294, 1));
        var context = new EngineExecutionContext(EXECUTION,
                new EngineIdentity(RestAssuredEnginePlugin.ENGINE_ID, RestAssuredEnginePlugin.IMPLEMENTATION_VERSION),
                "api.json", Map.of(), "https://example.invalid", Map.of(), Map.of());
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("api.json", manifest.getBytes(StandardCharsets.UTF_8));
        extraFiles.forEach((name, value) -> files.put(name, value.getBytes(StandardCharsets.UTF_8)));
        var workspace = new InMemoryWorkspaceAccess(EXECUTION, workspaceId, files);
        var policy = RestAssuredNetworkPolicy.productionDefaults();
        var authorizer = new RestAssuredTargetAuthorizer(policy,
                host -> new InetAddress[] { InetAddress.getByName("8.8.8.8") });
        return new RestAssuredEnginePlugin(new RestAssuredManifestParser(), Clock.fixed(
                Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC), authorizer, transport).execute(
                new EngineExecutionRequest(context, new PreparedSource(workspaceId, "GIT_HTTPS", "revision"),
                        workspace, secrets, new InMemoryArtifactPublisher(EXECUTION)));
    }

    private String manifest(String type, String authFields, String correlation, String assertions) {
        String authentication = "\"type\":\"" + type + "\"" + (authFields.isEmpty() ? "" : "," + authFields);
        String correlationField = correlation == null ? "" : ",\"correlationHeader\":\"" + correlation + "\"";
        return "{\"schemaVersion\":\"1\",\"name\":\"AS-029D\",\"scenarios\":[{\"id\":\"s\","
                + "\"name\":\"scenario\",\"requests\":[{\"id\":\"r\",\"method\":\"GET\","
                + "\"path\":\"/resource\",\"queryParameters\":{\"view\":\"summary\"},"
                + "\"authentication\":{" + authentication + "},\"assertions\":[" + assertions + "],"
                + "\"retry\":{\"maxRetries\":0,\"backoffMillis\":0}" + correlationField + "}]}]}";
    }

    private String status() { return "{\"type\":\"STATUS\",\"expected\":\"200\"}"; }
    private record AuthFixture(String type, String fields, Map<String, String> expected) { }
    private record FailureFixture(RestAssuredTransport transport, String assertion, Map<String, String> files) { }

    private static final class CaptureTransport implements RestAssuredTransport {
        final int status; final Map<String, String> headers; final byte[] body;
        Map<String, String> authentication; RestAssuredTargetAuthorizer.AuthorizedTarget target;
        CaptureTransport(int status, Map<String, String> headers, String body) {
            this.status = status; this.headers = headers; this.body = body.getBytes(StandardCharsets.UTF_8);
        }
        @Override public RestAssuredHttpTransport.Response execute(RestAssuredTargetAuthorizer.AuthorizedTarget target,
                com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest.Request request,
                byte[] body, Map<String, String> authentication) {
            this.target = target; this.authentication = authentication;
            return new RestAssuredHttpTransport.Response(status, headers, this.body);
        }
    }

    private static final class ThrowingTransport implements RestAssuredTransport {
        final String code; ThrowingTransport(String code) { this.code = code; }
        @Override public RestAssuredHttpTransport.Response execute(RestAssuredTargetAuthorizer.AuthorizedTarget target,
                com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest.Request request,
                byte[] body, Map<String, String> authentication) { throw new RestAssuredEngineException(code, "sanitized"); }
    }

    private static final class TrackingSecrets implements ExecutionSecretAccess {
        final Map<String, String> values; final List<ResolvedSecret> handles = new ArrayList<>();
        TrackingSecrets(Map<String, String> values) { this.values = values; }
        @Override public UUID executionId() { return EXECUTION; }
        @Override public ResolvedSecret resolve(String logicalName) {
            String value = values.get(logicalName);
            if (value == null) throw new SecretResolutionException("SECRET_NOT_FOUND", "Secret unavailable");
            ResolvedSecret handle = ResolvedSecret.from(value.toCharArray()); handles.add(handle); return handle;
        }
    }
}
