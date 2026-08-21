package com.automationstudio.api.execution.orchestration;

public final class ExecutionOrchestrationException extends RuntimeException {

    private final String code;
    private final Long observedCancellationVersion;

    public ExecutionOrchestrationException(String code, String message) {
        this(code, message, null, null);
    }

    public ExecutionOrchestrationException(String code, String message, Throwable cause) {
        this(code, message, cause, null);
    }

    private ExecutionOrchestrationException(
            String code, String message, Throwable cause, Long observedCancellationVersion) {
        super(message, cause);
        this.code = code;
        if (observedCancellationVersion != null && observedCancellationVersion < 0) {
            throw new IllegalArgumentException("Observed cancellation version must not be negative");
        }
        this.observedCancellationVersion = observedCancellationVersion;
    }

    public String code() {
        return code;
    }

    public Long observedCancellationVersion() {
        return observedCancellationVersion;
    }

    ExecutionOrchestrationException withObservedCancellationVersion(long executionVersion) {
        ExecutionOrchestrationException enriched = new ExecutionOrchestrationException(
                code, getMessage(), getCause(), executionVersion);
        for (Throwable suppressed : getSuppressed()) {
            enriched.addSuppressed(suppressed);
        }
        return enriched;
    }
}
