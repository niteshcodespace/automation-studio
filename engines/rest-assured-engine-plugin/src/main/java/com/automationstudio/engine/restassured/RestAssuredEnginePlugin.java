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

/** Bounded outbound HTTP engine with invocation-local authentication and validation. */
public final class RestAssuredEnginePlugin implements ExecutionEnginePlugin {

    public static final String ENGINE_ID = "rest-assured";
    public static final String IMPLEMENTATION_VERSION = "6.0.1";

    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            ENGINE_ID, IMPLEMENTATION_VERSION, "REST Assured API Engine",
            Set.of("prepared-source", "api-manifest"),
            Set.of("strict-configuration", "ssrf-safe-http-transport", "execution-scoped-authentication",
                    "bounded-response-assertions", "execution-correlation", "bounded-request-retry",
                    "sanitized-evidence-report"));

    private final RestAssuredManifestParser parser;
    private final Clock clock;
    private final RestAssuredTargetAuthorizer authorizer;
    private final RestAssuredTransport transport;
    private final RestAssuredResponseAssertions responseAssertions = new RestAssuredResponseAssertions();
    private final RestAssuredRetryPolicy retryPolicy;

    public RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock) {
        this(parser, clock, RestAssuredNetworkPolicy.productionDefaults());
    }

    public RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock,
            RestAssuredNetworkPolicy policy) {
        this(parser, clock, new RestAssuredTargetAuthorizer(policy), new RestAssuredHttpTransport(policy));
    }

    public RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock,
            RestAssuredTargetAuthorizer authorizer, RestAssuredTransport transport) {
        this(parser, clock, authorizer, transport, new RestAssuredRetryPolicy());
    }

    RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock,
            RestAssuredTargetAuthorizer authorizer, RestAssuredTransport transport,
            RestAssuredRetryPolicy retryPolicy) {
        this.parser = Objects.requireNonNull(parser, "Manifest parser must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.authorizer = Objects.requireNonNull(authorizer, "Target authorizer must not be null");
        this.transport = Objects.requireNonNull(transport, "HTTP transport must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "Retry policy must not be null");
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
        String reportOutcome = state.name();
        List<RestAssuredEvidenceReport.RequestSummary> evidence = new ArrayList<>();
        RestAssuredEngineException executionFailure = null;
        try (var source = validated.workspaceAccess().openPreparedSource()) {
            RestAssuredApiManifest manifest = parser.load(validated.context().suiteReference(), source);
            for (var scenario : manifest.scenarios()) {
                for (var requestConfiguration : scenario.requests()) {
                    if (!executeRequest(validated, source, manifest.defaults(), scenario.id(),
                            requestConfiguration, evidence)) {
                        state = EngineExecutionState.FAILED;
                        break;
                    }
                }
                if (state == EngineExecutionState.FAILED) break;
            }
        } catch (RestAssuredManifestException exception) {
            executionFailure = failure(exception.code(), exception.getMessage());
            reportOutcome = "ERROR";
        } catch (RuntimeException exception) {
            executionFailure = exception instanceof RestAssuredEngineException engineException
                    ? engineException
                    : failure("MANIFEST_LOAD_FAILED", "REST Assured manifest could not be loaded");
            reportOutcome = "ERROR";
        }
        RestAssuredEvidenceReport.publish(validated.artifactPublisher(), validated.executionId(),
                executionFailure == null ? state.name() : reportOutcome, evidence);
        if (executionFailure != null) throw executionFailure;
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        return new EngineExecutionResult(validated.executionId(), ENGINE_ID, IMPLEMENTATION_VERSION,
                validated.preparedSource().workspaceId(), validated.preparedSource().resolvedRevision(),
                state, startedAt, finishedAt,
                Duration.between(startedAt, finishedAt));
    }

    private boolean executeRequest(EngineExecutionRequest execution, com.automationstudio.engine.sdk.PreparedSourceAccess source,
            RestAssuredApiManifest.Defaults defaults, String scenarioId,
            RestAssuredApiManifest.Request request,
            List<RestAssuredEvidenceReport.RequestSummary> evidence) {
        String targetPath = targetPath(request);
        var target = authorizer.authorize(execution.context().environmentBaseUrl(), targetPath);
        byte[] body = requestBody(source, request.body());
        Map<String, String> headers = new java.util.LinkedHashMap<>(defaults.headers());
        request.headers().forEach((name, value) -> headers.entrySet().removeIf(
                existing -> existing.getKey().equalsIgnoreCase(name)));
        headers.putAll(request.headers());
        if (request.correlationHeader() != null) {
            headers.put(request.correlationHeader(), execution.executionId().toString());
        }
        var outbound = new RestAssuredApiManifest.Request(request.id(), request.method(), request.path(),
                request.pathParameters(), request.queryParameters(), headers, request.body(),
                request.authentication(), request.assertions(), request.retry(), null, request.evidence());
        if (request.retry().maxRetries() > 0 && !idempotent(request.method())) {
            throw failure("RETRY_METHOD_DENIED", "REST Assured retry method is not permitted");
        }
        int attempt = 0;
        long requestStartedAt = retryPolicy.start();
        while (true) {
            try (var authentication = RestAssuredAuthentication.materialize(
                    request.authentication(), execution.secretAccess(), target)) {
                try {
                    var response = transport.execute(authentication.target(), outbound, body,
                            authentication.headers());
                    boolean assertionsPassed = responseAssertions.evaluate(
                            source, response, request.assertions());
                    evidence.add(summary(scenarioId, request, response.statusCode(),
                            assertionsPassed ? "SUCCEEDED" : "FAILED", attempt + 1));
                    return assertionsPassed;
                } catch (RestAssuredEngineException exception) {
                    if (!retryable(exception) || attempt >= request.retry().maxRetries()) throw exception;
                    attempt++;
                    retryPolicy.beforeRetry(request.retry().backoffMillis(), attempt, requestStartedAt);
                    target = authorizer.authorize(execution.context().environmentBaseUrl(), targetPath);
                }
            } catch (RestAssuredEngineException exception) {
                evidence.add(summary(scenarioId, request, null, "ERROR", attempt + 1));
                throw exception;
            }
        }
    }

    private RestAssuredEvidenceReport.RequestSummary summary(String scenarioId,
            RestAssuredApiManifest.Request request, Integer statusCode, String outcome,
            int attemptCount) {
        return new RestAssuredEvidenceReport.RequestSummary(scenarioId, request.id(),
                request.method().name(), statusCode, outcome, request.assertions().size(),
                "SUCCEEDED".equals(outcome), attemptCount);
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

    private boolean idempotent(RestAssuredApiManifest.HttpMethod method) {
        return Set.of(RestAssuredApiManifest.HttpMethod.GET, RestAssuredApiManifest.HttpMethod.HEAD,
                RestAssuredApiManifest.HttpMethod.PUT, RestAssuredApiManifest.HttpMethod.DELETE,
                RestAssuredApiManifest.HttpMethod.OPTIONS).contains(method);
    }

    private boolean retryable(RestAssuredEngineException exception) {
        return exception.code().equals("TRANSPORT_FAILURE") || exception.code().equals("TRANSPORT_TIMEOUT");
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

    static RestAssuredEngineException failure(String code, String message) {
        return new RestAssuredEngineException(code, message);
    }
}
