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

/** Provider adapter for AS-030C3 controlled sequential Karate execution. */
public final class KarateEnginePlugin implements ExecutionEnginePlugin {
    public static final String ENGINE_ID = "karate";
    public static final String IMPLEMENTATION_VERSION = "1.5.2";
    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            ENGINE_ID, IMPLEMENTATION_VERSION, "Karate Engine",
            Set.of("prepared-source-listing", "karate-configuration"),
            Set.of("bounded-feature-discovery", "strict-configuration", "execution-correlation"));

    private final Clock clock;
    private final KarateFeatureDiscovery discovery;
    private final KarateWorkerRuntime workerRuntime;

    public KarateEnginePlugin() { this(Clock.systemUTC(), new KarateFeatureDiscovery(), new DockerKarateWorkerRuntime()); }

    KarateEnginePlugin(Clock clock, KarateFeatureDiscovery discovery, KarateWorkerRuntime workerRuntime) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.discovery = Objects.requireNonNull(discovery, "Discovery must not be null");
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "Worker runtime must not be null");
    }

    @Override public ExecutionEngineDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public void validate(EngineExecutionContext context) {
        if (context == null || !DESCRIPTOR.identity().equals(context.engineIdentity())) {
            throw failure("INVALID_ENGINE_CONTEXT", "Karate engine context is invalid");
        }
        KarateSuiteConfiguration.parse(context.suiteConfiguration());
        if (!context.environmentConfiguration().equals(Map.of())) {
            throw failure("INVALID_ENGINE_CONTEXT", "Karate engine context is invalid");
        }
        KarateSuiteConfiguration.parse(context.suiteConfiguration()).composeVariables(context.variables());
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
            var configuration=KarateSuiteConfiguration.parse(validated.context().suiteConfiguration());
            var features = discovery.discover(source, configuration);
            var projected = discovery.projectedFiles(source, configuration);
            var workerResult=workerRuntime.execute(validated.executionId(), source, projected, features,
                    configuration, configuration.composeVariables(validated.context().variables()),
                    validated.context().environmentBaseUrl(),validated.secretAccess());
            OffsetDateTime finishedAt = OffsetDateTime.now(clock);
            EngineExecutionState state=switch(workerResult.outcome()){case "SUCCEEDED"->EngineExecutionState.SUCCEEDED;case "FAILED"->EngineExecutionState.FAILED;case "CANCELLED"->EngineExecutionState.CANCELLED;default->throw failure("WORKER_PROTOCOL_ERROR","Karate worker result is invalid");};
            Duration duration=Duration.between(startedAt, finishedAt);
            EngineExecutionResult result=new EngineExecutionResult(validated.executionId(), ENGINE_ID, IMPLEMENTATION_VERSION,
                    validated.preparedSource().workspaceId(), validated.preparedSource().resolvedRevision(),
                    state, startedAt, finishedAt, duration);
            KarateEvidenceReport.publish(validated.artifactPublisher(), result.state(), workerResult, result.duration());
            return result;
        } catch (com.automationstudio.engine.sdk.ArtifactPublicationException exception) {
            throw exception;
        } catch (KarateEngineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("FEATURE_DISCOVERY_FAILED", "Karate feature discovery failed");
        }
    }

    private static KarateEngineException failure(String code, String message) {
        return new KarateEngineException(code, message);
    }
}
