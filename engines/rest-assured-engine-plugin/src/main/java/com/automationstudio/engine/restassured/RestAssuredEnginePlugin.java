package com.automationstudio.engine.restassured;

import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestException;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParser;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** AS-029B configuration-only foundation. It performs no HTTP or authentication work. */
public final class RestAssuredEnginePlugin implements ExecutionEnginePlugin {

    public static final String ENGINE_ID = "rest-assured";
    public static final String IMPLEMENTATION_VERSION = "6.0.1";

    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            ENGINE_ID, IMPLEMENTATION_VERSION, "REST Assured API Engine",
            Set.of("prepared-source", "api-manifest"),
            Set.of("strict-configuration", "network-inert-foundation"));

    private final RestAssuredManifestParser parser;
    private final Clock clock;

    public RestAssuredEnginePlugin(RestAssuredManifestParser parser, Clock clock) {
        this.parser = Objects.requireNonNull(parser, "Manifest parser must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
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
        try (var source = validated.workspaceAccess().openPreparedSource()) {
            RestAssuredApiManifest manifest = parser.load(validated.context().suiteReference(), source);
            if (manifest.scenarios().isEmpty()) {
                throw failure("INVALID_ENGINE_CONFIGURATION", "REST Assured manifest is invalid");
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
                EngineExecutionState.SUCCEEDED, startedAt, finishedAt,
                Duration.between(startedAt, finishedAt));
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
