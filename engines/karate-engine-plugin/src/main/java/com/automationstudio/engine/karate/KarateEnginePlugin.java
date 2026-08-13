package com.automationstudio.engine.karate;

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

/** AS-030B structural provider; Karate execution is intentionally not implemented. */
public final class KarateEnginePlugin implements ExecutionEnginePlugin {
    public static final String ENGINE_ID = "karate";
    public static final String IMPLEMENTATION_VERSION = "1.5.2";
    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            ENGINE_ID, IMPLEMENTATION_VERSION, "Karate Engine",
            Set.of("prepared-source-listing", "karate-configuration"),
            Set.of("bounded-feature-discovery", "strict-configuration", "execution-correlation"));

    private final Clock clock;
    private final KarateFeatureDiscovery discovery;

    public KarateEnginePlugin() { this(Clock.systemUTC(), new KarateFeatureDiscovery()); }

    KarateEnginePlugin(Clock clock, KarateFeatureDiscovery discovery) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.discovery = Objects.requireNonNull(discovery, "Discovery must not be null");
    }

    @Override public ExecutionEngineDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public void validate(EngineExecutionContext context) {
        if (context == null || !DESCRIPTOR.identity().equals(context.engineIdentity())) {
            throw failure("INVALID_ENGINE_CONTEXT", "Karate engine context is invalid");
        }
        KarateSuiteConfiguration.parse(context.suiteConfiguration());
        if (!context.environmentConfiguration().equals(Map.of()) || !context.variables().equals(Map.of())) {
            throw failure("INVALID_ENGINE_CONTEXT", "Karate engine context is invalid");
        }
    }

    @Override
    public EngineExecutionResult execute(EngineExecutionRequest request) {
        EngineExecutionRequest validated;
        try {
            validated = Objects.requireNonNull(request, "request").validateFor(DESCRIPTOR);
            validate(validated.context());
        } catch (KarateEngineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("INVALID_ENGINE_REQUEST", "Karate engine request is invalid");
        }
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        try (var source = validated.workspaceAccess().openPreparedSource()) {
            discovery.discover(source, KarateSuiteConfiguration.parse(validated.context().suiteConfiguration()));
        } catch (KarateEngineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("FEATURE_DISCOVERY_FAILED", "Karate feature discovery failed");
        }
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        return new EngineExecutionResult(validated.executionId(), ENGINE_ID, IMPLEMENTATION_VERSION,
                validated.preparedSource().workspaceId(), validated.preparedSource().resolvedRevision(),
                EngineExecutionState.SUCCEEDED, startedAt, finishedAt,
                Duration.between(startedAt, finishedAt));
    }

    private static KarateEngineException failure(String code, String message) {
        return new KarateEngineException(code, message);
    }
}
