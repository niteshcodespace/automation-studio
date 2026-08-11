package com.automationstudio.engine.restassured;

import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestException;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParser;
import com.automationstudio.engine.restassured.network.RestAssuredHttpTransport;
import com.automationstudio.engine.restassured.network.RestAssuredNetworkPolicy;
import com.automationstudio.engine.restassured.network.RestAssuredTargetAuthorizer;
import com.automationstudio.engine.restassured.network.RestAssuredTransport;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** AS-029C bounded outbound HTTP engine. Authentication and later-story features remain disabled. */
public final class RestAssuredEnginePlugin implements ExecutionEnginePlugin {

    public static final String ENGINE_ID = "rest-assured";
    public static final String IMPLEMENTATION_VERSION = "6.0.1";

    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            ENGINE_ID, IMPLEMENTATION_VERSION, "REST Assured API Engine",
            Set.of("prepared-source", "api-manifest"),
            Set.of("strict-configuration", "ssrf-safe-http-transport", "unauthenticated-requests"));

    private final RestAssuredManifestParser parser;
    private final Clock clock;
    private final RestAssuredTargetAuthorizer authorizer;
    private final RestAssuredTransport transport;

    public RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock) {
        this(parser, clock, RestAssuredNetworkPolicy.productionDefaults());
    }

    public RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock,
            RestAssuredNetworkPolicy policy) {
        this(parser, clock, new RestAssuredTargetAuthorizer(policy), new RestAssuredHttpTransport(policy));
    }

    public RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock,
            RestAssuredTargetAuthorizer authorizer, RestAssuredTransport transport) {
        this.parser = Objects.requireNonNull(parser, "Manifest parser must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.authorizer = Objects.requireNonNull(authorizer, "Target authorizer must not be null");
        this.transport = Objects.requireNonNull(transport, "HTTP transport must not be null");
    }

    @Override
    public ExecutionEngineDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void validate(EngineExecutionContext context) {
        if (context == null || !DESCRIPTOR.identity().equals(context.engineIdentity())) {
            throw failure("INVALID_ENGINE_CONTEXT", "REST Assured engine context is invalid");
        }
        if (!context.suiteConfiguration().equals(Map.of())) {
            throw failure("INVALID_ENGINE_CONFIGURATION", "REST Assured suite configuration is invalid");
        }
        validateReference(context.suiteReference());
    }

    @Override
    public EngineExecutionResult execute(EngineExecutionRequest request) {
        EngineExecutionRequest validated;
        try {
            validated = Objects.requireNonNull(request, "request").validateFor(DESCRIPTOR);
            validate(validated.context());
        } catch (RuntimeException exception) {
            throw failure("INVALID_ENGINE_REQUEST", "REST Assured engine request is invalid");
        }
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        EngineExecutionState state = EngineExecutionState.SUCCEEDED;
        try (var source = validated.workspaceAccess().openPreparedSource()) {
            RestAssuredApiManifest manifest = parser.load(validated.context().suiteReference(), source);
            for (var scenario : manifest.scenarios()) {
                for (var requestConfiguration : scenario.requests()) {
                    if (!executeRequest(validated, source, manifest.defaults(), requestConfiguration)) {
                        state = EngineExecutionState.FAILED;
                        break;
                    }
                }
                if (state == EngineExecutionState.FAILED) break;
            }
        } catch (RestAssuredManifestException exception) {
            throw failure(exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            if (exception instanceof RestAssuredEngineException engineException) throw engineException;
            throw failure("MANIFEST_LOAD_FAILED", "REST Assured manifest could not be loaded");
        }
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        return new EngineExecutionResult(validated.executionId(), ENGINE_ID, IMPLEMENTATION_VERSION,
                validated.preparedSource().workspaceId(), validated.preparedSource().resolvedRevision(),
                state, startedAt, finishedAt,
                Duration.between(startedAt, finishedAt));
    }

    private boolean executeRequest(EngineExecutionRequest execution, com.automationstudio.engine.sdk.PreparedSourceAccess source,
            RestAssuredApiManifest.Defaults defaults, RestAssuredApiManifest.Request request) {
        if (request.authentication().type() != RestAssuredApiManifest.AuthenticationType.NONE) {
            throw failure("AUTHENTICATION_DEFERRED", "REST Assured authentication is not available");
        }
        if (request.retry().maxRetries() != 0) {
            throw failure("RETRY_DEFERRED", "REST Assured retries are not available");
        }
        if (request.assertions().stream().anyMatch(assertion ->
                assertion.type() != RestAssuredApiManifest.AssertionType.STATUS)) {
            throw failure("ASSERTION_DEFERRED", "REST Assured response assertion is not available");
        }
        String targetPath = targetPath(request);
        var target = authorizer.authorize(execution.context().environmentBaseUrl(), targetPath);
        byte[] body = requestBody(source, request.body());
        Map<String, String> headers = new java.util.LinkedHashMap<>(defaults.headers());
        request.headers().forEach((name, value) -> headers.entrySet().removeIf(
                existing -> existing.getKey().equalsIgnoreCase(name)));
        headers.putAll(request.headers());
        var outbound = new RestAssuredApiManifest.Request(request.id(), request.method(), request.path(),
                request.pathParameters(), request.queryParameters(), headers, request.body(),
                request.authentication(), request.assertions(), request.retry(), request.evidence());
        var response = transport.execute(target, outbound, body);
        for (var assertion : request.assertions()) {
            if (!statusMatches(assertion.expected(), response.statusCode())) {
                return false;
            }
        }
        return true;
    }

    private String targetPath(RestAssuredApiManifest.Request request) {
        String path = request.path();
        for (var entry : request.pathParameters().entrySet()) {
            String token = "{" + entry.getKey() + "}";
            if (!path.contains(token)) throw failure("INVALID_PATH_PARAMETERS", "REST Assured path is invalid");
            path = path.replace(token, encode(entry.getValue()));
        }
        if (path.contains("{") || path.contains("}")) {
            throw failure("INVALID_PATH_PARAMETERS", "REST Assured path is invalid");
        }
        if (!request.queryParameters().isEmpty()) {
            String query = request.queryParameters().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .reduce((left, right) -> left + "&" + right).orElseThrow();
            path += "?" + query;
        }
        return path;
    }

    private byte[] requestBody(com.automationstudio.engine.sdk.PreparedSourceAccess source,
            RestAssuredApiManifest.Body body) {
        if (body == null) return null;
        if (body.inline() != null) {
            byte[] bytes = body.inline().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > RestAssuredApiManifest.MAX_BODY_LENGTH) {
                throw failure("REQUEST_BODY_TOO_LARGE", "REST Assured request body is too large");
            }
            return bytes;
        }
        try (var input = source.open(body.sourceReference())) {
            byte[] bytes = input.readNBytes(RestAssuredApiManifest.MAX_BODY_LENGTH + 1);
            if (bytes.length > RestAssuredApiManifest.MAX_BODY_LENGTH) {
                throw failure("REQUEST_BODY_TOO_LARGE", "REST Assured request body is too large");
            }
            return bytes;
        } catch (RestAssuredEngineException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("REQUEST_BODY_UNREADABLE", "REST Assured request body could not be read");
        }
    }

    private boolean statusMatches(String expected, int actual) {
        try {
            if (expected.matches("[1-5][0-9]{2}")) return Integer.parseInt(expected) == actual;
            if (expected.matches("[1-5][0-9]{2}-[1-5][0-9]{2}")) {
                String[] range = expected.split("-");
                return actual >= Integer.parseInt(range[0]) && actual <= Integer.parseInt(range[1]);
            }
        } catch (NumberFormatException ignored) { }
        throw failure("INVALID_STATUS_ASSERTION", "REST Assured status assertion is invalid");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void validateReference(String reference) {
        if (reference == null || reference.isBlank()
                || reference.length() > RestAssuredManifestParser.MAX_REFERENCE_LENGTH
                || reference.contains("\0")) {
            throw failure("INVALID_MANIFEST_REFERENCE", "REST Assured manifest reference is invalid");
        }
    }

    private RestAssuredEngineException failure(String code, String message) {
        return new RestAssuredEngineException(code, message);
    }
}
