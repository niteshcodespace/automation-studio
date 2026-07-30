package com.automationstudio.api.execution;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ExecutionContextSource(
        UUID executionId,
        UUID projectId,
        UUID workspaceId,
        UUID environmentId,
        UUID suiteId,
        OffsetDateTime createdAt,
        Map<String, Object> environmentSnapshot,
        Map<String, Object> suiteSnapshot,
        Map<String, Object> requestSnapshot,
        UUID runnerId,
        String runnerKey,
        String runnerVersion,
        String runnerOperatingSystem,
        String runnerArchitecture,
        Map<String, Object> runnerCapabilities,
        Map<String, Object> runnerLabels,
        OffsetDateTime claimedAt,
        Map<String, Object> systemDefaults,
        Map<String, Object> projectDefaults) {

    public ExecutionContextSource {
        environmentSnapshot = copyNullable(environmentSnapshot, "Environment snapshot");
        suiteSnapshot = copyNullable(suiteSnapshot, "Suite snapshot");
        requestSnapshot = copyNullable(requestSnapshot, "Request snapshot");
        runnerCapabilities = copyNullable(runnerCapabilities, "Runner capabilities");
        runnerLabels = copyNullable(runnerLabels, "Runner labels");
        systemDefaults = ImmutableExecutionValue.object(
                systemDefaults == null ? Map.of() : systemDefaults, "System defaults");
        projectDefaults = ImmutableExecutionValue.object(
                projectDefaults == null ? Map.of() : projectDefaults, "Project defaults");
    }

    private static Map<String, Object> copyNullable(Map<String, Object> value, String name) {
        return value == null ? null : ImmutableExecutionValue.object(value, name);
    }
}
