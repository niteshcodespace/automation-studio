package com.automationstudio.api.execution.orchestration;

record ExecutionCancellationObservation(boolean requested, long executionVersion) {
    public ExecutionCancellationObservation {
        if (executionVersion < 0) throw new IllegalArgumentException("Execution version must not be negative");
    }
}
