package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.repository.ExecutionRepository;
import java.util.Objects;
import java.util.UUID;

final class RepositoryExecutionCancellationProbe implements ExecutionCancellationProbe {
    private final ExecutionRepository repository;

    RepositoryExecutionCancellationProbe(ExecutionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "Execution repository must not be null");
    }

    @Override
    public ExecutionCancellationObservation observe(UUID executionId) {
        var execution = repository.findById(Objects.requireNonNull(executionId, "Execution ID must not be null"))
                .orElseThrow(() -> new ExecutionOrchestrationException(
                        "EXECUTION_CANCELLATION_OBSERVATION_FAILED", "Execution cancellation could not be observed"));
        return new ExecutionCancellationObservation(
                execution.getStatus() == ExecutionStatus.CANCEL_REQUESTED,
                execution.getVersion());
    }
}
