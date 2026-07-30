package com.automationstudio.api.execution.evidence;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ExecutionEvidenceCollectorImpl implements ExecutionEvidenceCollector {

    private final ExecutionEvidenceValidator evidenceValidator;

    public ExecutionEvidenceCollectorImpl(ExecutionEvidenceValidator evidenceValidator) {
        this.evidenceValidator = evidenceValidator;
    }

    @Override
    public ExecutionEvidence collect(ExecutionContext context, ExecutionResult result) {
        Objects.requireNonNull(result, "Execution result must not be null");
        ExecutionEvidence normalized = normalize(result.evidence());
        validate(context, normalized);
        if (!result.duration().equals(normalized.summary().duration())) {
            throw new ExecutionEvidenceException(
                    "Evidence duration does not match execution result");
        }
        return normalized;
    }

    @Override
    public void validate(ExecutionContext context, ExecutionEvidence evidence) {
        evidenceValidator.validate(context, evidence);
    }

    @Override
    public ExecutionEvidence normalize(ExecutionEvidence evidence) {
        if (evidence == null) {
            throw new ExecutionEvidenceException("Execution evidence must not be null");
        }
        if (evidence.artifacts().stream().anyMatch(Objects::isNull)) {
            throw new ExecutionEvidenceException(
                    "Execution evidence must not contain null artifacts");
        }
        List<ExecutionArtifact> artifacts = evidence.artifacts().stream()
                .sorted(Comparator.comparing(ExecutionArtifact::artifactId))
                .toList();
        ExecutionEvidenceSummary source = evidence.summary();
        return new ExecutionEvidence(
                evidence.executionId(),
                evidence.runnerId(),
                evidence.capturedAt(),
                artifacts,
                evidence.metadata(),
                new ExecutionEvidenceSummary(
                        artifacts.size(),
                        source.warningCount(),
                        source.errorCount(),
                        source.duration()));
    }
}
