package com.automationstudio.api.execution;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionRunnerContext(
        UUID runnerId,
        String runnerKey,
        String runnerVersion,
        String operatingSystem,
        String architecture,
        Map<String, Object> runnerCapabilities,
        Map<String, Object> runnerLabels) {

    public ExecutionRunnerContext {
        runnerId = Objects.requireNonNull(runnerId, "Runner ID must not be null");
        runnerKey = requireText(runnerKey, "Runner key");
        runnerVersion = requireText(runnerVersion, "Runner version");
        operatingSystem = requireText(operatingSystem, "Runner operating system");
        architecture = requireText(architecture, "Runner architecture");
        runnerCapabilities =
                ImmutableExecutionValue.object(runnerCapabilities, "Runner capabilities");
        runnerLabels = ImmutableExecutionValue.object(runnerLabels, "Runner labels");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidExecutionContextException(name + " must not be blank");
        }
        return value;
    }
}
