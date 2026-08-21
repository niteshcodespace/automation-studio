package com.automationstudio.api.execution.orchestration;

import java.util.UUID;

@FunctionalInterface
interface ExecutionCancellationProbe {
    ExecutionCancellationObservation observe(UUID executionId);

    static ExecutionCancellationProbe never() {
        return ignored -> new ExecutionCancellationObservation(false, 0);
    }
}
