package com.automationstudio.engine.sample;

import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Minimal non-production reference implementation of the reusable engine SDK. */
public final class SampleExecutionEnginePlugin implements ExecutionEnginePlugin {

    public static final String ENGINE_ID = "sample-engine";
    public static final String IMPLEMENTATION_VERSION = "1.0.0";
    public static final String SOURCE_FILE = "sample.txt";
    public static final String SECRET_NAME = "sample-token";

    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            ENGINE_ID, IMPLEMENTATION_VERSION, "Sample Reference Engine",
            Set.of("prepared-source", "secrets"), Set.of("deterministic-reference"));
    private static final Map<String, Object> REQUIRED_CONFIGURATION =
            Map.of("mode", "DETERMINISTIC");

    private final Clock clock;

    public SampleExecutionEnginePlugin(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public ExecutionEngineDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void validate(EngineExecutionContext context) {
        if (context == null
                || !DESCRIPTOR.identity().equals(context.engineIdentity())
                || !REQUIRED_CONFIGURATION.equals(context.suiteConfiguration())) {
            throw new IllegalArgumentException("Sample engine configuration is invalid");
        }
    }

    @Override
    public EngineExecutionResult execute(EngineExecutionRequest request) {
        EngineExecutionRequest validated = Objects.requireNonNull(
                request, "Engine execution request must not be null").validateFor(DESCRIPTOR);
        validate(validated.context());
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        try (var source = validated.workspaceAccess().openPreparedSource();
                var input = source.open(SOURCE_FILE);
                var secret = validated.secretAccess().resolve(SECRET_NAME)) {
            if (!"ready".equals(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))) {
                throw new IllegalStateException("Unexpected sample input");
            }
            secret.withValue(value -> {
                if (value.length == 0) {
                    throw new IllegalStateException("Empty sample secret");
                }
            });
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Sample engine execution failed", failure);
        }
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        return new EngineExecutionResult(validated.executionId(), ENGINE_ID,
                IMPLEMENTATION_VERSION, validated.preparedSource().workspaceId(),
                validated.preparedSource().resolvedRevision(), EngineExecutionState.SUCCEEDED,
                startedAt, finishedAt, Duration.between(startedAt, finishedAt));
    }
}
