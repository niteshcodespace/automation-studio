package com.automationstudio.api.source;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

public record ExecutionSourceReference(
        SourceType sourceType,
        String repository,
        String revision,
        String sourceLocation) {

    public ExecutionSourceReference {
        sourceType = Objects.requireNonNull(sourceType, "Source type must not be null");
        repository = Objects.requireNonNull(repository, "Repository identity must not be null");
        revision = Objects.requireNonNull(revision, "Source revision must not be null");
    }

    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceType", sourceType.name());
        snapshot.put("repository", repository);
        snapshot.put("revision", revision);
        snapshot.put("sourceLocation", sourceLocation);
        return Collections.unmodifiableMap(snapshot);
    }
}
