package com.automationstudio.api.execution.evidence;

import com.automationstudio.api.execution.ExecutionContext;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ExecutionEvidenceValidator {

    public void validate(ExecutionContext context, ExecutionEvidence evidence) {
        if (context == null || evidence == null) {
            throw new ExecutionEvidenceException(
                    "Evidence validation requires context and evidence");
        }
        if (!context.executionId().equals(evidence.executionId())
                || !context.runner().runnerId().equals(evidence.runnerId())) {
            throw new ExecutionEvidenceException(
                    "Evidence identity does not match execution context");
        }
        validate(evidence);
    }

    public void validate(ExecutionEvidence evidence) {
        if (evidence == null) {
            throw new ExecutionEvidenceException("Execution evidence must not be null");
        }
        Set<UUID> artifactIds = new HashSet<>();
        for (ExecutionArtifact artifact : evidence.artifacts()) {
            if (artifact == null) {
                throw new ExecutionEvidenceException(
                        "Execution evidence must not contain null artifacts");
            }
            if (!artifactIds.add(artifact.artifactId())) {
                throw new ExecutionEvidenceException(
                        "Duplicate execution artifact ID: " + artifact.artifactId());
            }
            validateReference(artifact.reference());
        }
        if (evidence.summary().artifactCount() != evidence.artifacts().size()) {
            throw new ExecutionEvidenceException(
                    "Evidence artifact count does not match artifacts");
        }
    }

    private static void validateReference(ExecutionArtifactReference reference) {
        if (reference == null) {
            throw new ExecutionEvidenceException("Artifact reference must not be null");
        }
        URI uri = reference.uri();
        if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank()) {
            throw new ExecutionEvidenceException(
                    "Artifact reference URI must be absolute");
        }
        if (uri.getUserInfo() != null) {
            throw new ExecutionEvidenceException(
                    "Artifact reference URI must not contain user information");
        }
    }
}
