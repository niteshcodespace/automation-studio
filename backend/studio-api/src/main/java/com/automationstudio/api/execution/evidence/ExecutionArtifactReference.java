package com.automationstudio.api.execution.evidence;

import java.net.URI;
import java.util.Objects;

public record ExecutionArtifactReference(
        URI uri,
        String storageProvider,
        String checksum,
        String compression) {

    public ExecutionArtifactReference {
        uri = Objects.requireNonNull(uri, "Artifact URI must not be null");
        storageProvider = requireText(storageProvider, "Storage provider");
        checksum = optionalText(checksum, "Checksum");
        compression = optionalText(compression, "Compression");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ExecutionEvidenceException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new ExecutionEvidenceException(name + " must not be blank");
        }
        return value == null ? null : value.trim();
    }
}
