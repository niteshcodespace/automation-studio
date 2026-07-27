package com.automationstudio.api.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionContext(
        UUID executionId,
        UUID projectId,
        UUID workspaceId,
        ExecutionSuiteSnapshot suite,
        ExecutionEnvironmentSnapshot environment,
        List<ExecutionSecretReference> secretReferences,
        Map<String, ExecutionVariable> variables,
        ExecutionRunnerContext runner,
        ExecutionMetadata metadata) {

    public ExecutionContext {
        executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        projectId = Objects.requireNonNull(projectId, "Project ID must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        suite = Objects.requireNonNull(suite, "Suite snapshot must not be null");
        environment = Objects.requireNonNull(
                environment, "Environment snapshot must not be null");
        secretReferences = List.copyOf(Objects.requireNonNull(
                secretReferences, "Secret references must not be null"));
        variables = Map.copyOf(Objects.requireNonNull(
                variables, "Execution variables must not be null"));
        runner = Objects.requireNonNull(runner, "Runner context must not be null");
        metadata = Objects.requireNonNull(metadata, "Execution metadata must not be null");
    }
}
