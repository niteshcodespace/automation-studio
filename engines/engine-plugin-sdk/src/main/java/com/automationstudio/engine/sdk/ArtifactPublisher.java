package com.automationstudio.engine.sdk;

import java.util.Objects;
import java.util.UUID;

/** Execution-scoped capability for provider-neutral artifact publication. */
public interface ArtifactPublisher {

    UUID executionId();

    ArtifactReceipt publish(ArtifactPublication publication);

    static ArtifactPublisher unavailable(UUID executionId) {
        UUID boundExecutionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        return new ArtifactPublisher() {
            @Override
            public UUID executionId() {
                return boundExecutionId;
            }

            @Override
            public ArtifactReceipt publish(ArtifactPublication publication) {
                Objects.requireNonNull(publication, "Artifact publication must not be null");
                throw new ArtifactPublicationException(
                        "ARTIFACT_PUBLICATION_UNAVAILABLE",
                        "Artifact publication is unavailable for this execution");
            }

            @Override
            public String toString() {
                return "ArtifactPublisher[executionId=" + boundExecutionId + ", available=false]";
            }
        };
    }
}
