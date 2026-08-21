package com.automationstudio.api.execution.orchestration;

import com.automationstudio.engine.sdk.EngineExecutionState;
import java.util.Objects;

/** Platform-internal outcome carrying repository-observed lifecycle metadata. */
record PlatformExecutionOrchestrationResult(
        ExecutionOrchestrationResult result,
        Long observedCancellationVersion) {

    PlatformExecutionOrchestrationResult {
        result = Objects.requireNonNull(result, "Orchestration result must not be null");
        if (observedCancellationVersion != null && observedCancellationVersion < 0) {
            throw new IllegalArgumentException("Observed cancellation version must not be negative");
        }
        if (result.engineResult().state() != EngineExecutionState.CANCELLED
                && observedCancellationVersion != null) {
            throw new IllegalArgumentException(
                    "Observed cancellation version is valid only for cancellation");
        }
    }

    static PlatformExecutionOrchestrationResult ordinary(
            ExecutionOrchestrationResult result) {
        return new PlatformExecutionOrchestrationResult(result, null);
    }
}
