package com.automationstudio.api.execution.evidence;

import java.time.Duration;
import java.util.Objects;

public record ExecutionEvidenceSummary(
        int artifactCount,
        int warningCount,
        int errorCount,
        Duration duration) {

    public ExecutionEvidenceSummary {
        if (artifactCount < 0 || warningCount < 0 || errorCount < 0) {
            throw new ExecutionEvidenceException(
                    "Evidence summary counts must not be negative");
        }
        duration = Objects.requireNonNull(duration, "Evidence duration must not be null");
        if (duration.isNegative()) {
            throw new ExecutionEvidenceException(
                    "Evidence duration must not be negative");
        }
    }
}
