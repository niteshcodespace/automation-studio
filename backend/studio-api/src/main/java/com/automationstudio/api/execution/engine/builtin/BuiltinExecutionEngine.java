package com.automationstudio.api.execution.engine.builtin;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.evidence.ExecutionArtifact;
import com.automationstudio.api.execution.evidence.ExecutionArtifactReference;
import com.automationstudio.api.execution.evidence.ExecutionArtifactType;
import com.automationstudio.api.execution.evidence.ExecutionEvidence;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceSummary;
import com.automationstudio.api.execution.lifecycle.ExecutionFailureReason;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;
import com.automationstudio.api.execution.lifecycle.ExecutionStatus;
import com.automationstudio.api.execution.lifecycle.ExecutionTerminationReason;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class BuiltinExecutionEngine implements ExecutionEngine {

    public static final String ENGINE_ID = "BUILTIN";
    public static final String IMPLEMENTATION_VERSION = "1.0.0";
    @Deprecated(forRemoval = false)
    public static final String ENGINE_NAME = ENGINE_ID;
    @Deprecated(forRemoval = false)
    public static final String ENGINE_VERSION = IMPLEMENTATION_VERSION;
    private static final ExecutionEngineDescriptor DESCRIPTOR =
            new ExecutionEngineDescriptor(
                    ENGINE_ID,
                    IMPLEMENTATION_VERSION,
                    "Built-in Deterministic Engine",
                    Set.of("deterministic"),
                    Set.of("evidence"));

    private final BuiltinExecutionEngineConfiguration configuration;
    private final Clock clock;

    public BuiltinExecutionEngine(
            BuiltinExecutionEngineConfiguration configuration, Clock clock) {
        this.configuration = configuration;
        this.clock = clock;
    }

    @Override
    public ExecutionEngineDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void validate(ExecutionContext context) {
        configuration.parse(context);
    }

    @Override
    public ExecutionResult execute(ExecutionContext context) {
        BuiltinExecutionEngineConfiguration.Parsed parsed = configuration.parse(context);
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        Duration duration = Duration.between(startedAt, finishedAt);

        ExecutionStatus status = parsed.operation() == BuiltinExecutionOperation.SUCCEED
                ? ExecutionStatus.SUCCEEDED
                : ExecutionStatus.FAILED;
        ExecutionFailureReason failureReason =
                status == ExecutionStatus.SUCCEEDED
                        ? ExecutionFailureReason.NONE
                        : ExecutionFailureReason.ENGINE_REPORTED_FAILURE;
        ExecutionTerminationReason terminationReason =
                status == ExecutionStatus.SUCCEEDED
                        ? ExecutionTerminationReason.COMPLETED
                        : ExecutionTerminationReason.ENGINE_FAILURE;
        Map<String, String> metadata = resultMetadata(parsed);
        ExecutionEvidence evidence = evidence(
                context, parsed, finishedAt, duration);
        return new ExecutionResult(
                context.executionId(),
                context.runner().runnerId(),
                status,
                startedAt,
                finishedAt,
                duration,
                terminationReason,
                failureReason,
                metadata,
                evidence);
    }

    private static Map<String, String> resultMetadata(
            BuiltinExecutionEngineConfiguration.Parsed parsed) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("engine", ENGINE_NAME);
        metadata.put("engineVersion", ENGINE_VERSION);
        metadata.put("operation", parsed.operation().name());
        if (parsed.message() != null) {
            metadata.put("message", parsed.message());
        }
        return Map.copyOf(metadata);
    }

    private static ExecutionEvidence evidence(
            ExecutionContext context,
            BuiltinExecutionEngineConfiguration.Parsed parsed,
            OffsetDateTime capturedAt,
            Duration duration) {
        List<ExecutionArtifact> artifacts = parsed.evidenceEnabled()
                ? List.of(reportArtifact(context, parsed))
                : List.of();
        return new ExecutionEvidence(
                context.executionId(),
                context.runner().runnerId(),
                capturedAt,
                artifacts,
                Map.of(
                        "engine", ENGINE_NAME,
                        "operation", parsed.operation().name()),
                new ExecutionEvidenceSummary(
                        artifacts.size(),
                        0,
                        parsed.operation() == BuiltinExecutionOperation.FAIL ? 1 : 0,
                        duration));
    }

    private static ExecutionArtifact reportArtifact(
            ExecutionContext context,
            BuiltinExecutionEngineConfiguration.Parsed parsed) {
        UUID artifactId = UUID.nameUUIDFromBytes(
                (context.executionId() + ":builtin-report")
                        .getBytes(StandardCharsets.UTF_8));
        URI reference = URI.create(
                "builtin://execution/" + context.executionId() + "/report");
        return new ExecutionArtifact(
                artifactId,
                ExecutionArtifactType.REPORT,
                "Built-in execution report",
                "application/json",
                0,
                new ExecutionArtifactReference(
                        reference, "BUILTIN", null, null),
                Map.of(
                        "engine", ENGINE_NAME,
                        "operation", parsed.operation().name()));
    }
}
