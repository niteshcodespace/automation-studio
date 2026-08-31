package com.automationstudio.engine.selenium;

import java.util.Objects;

/** Closed, network-only Docker boundary. Implementations own every argv token. */
interface DockerNetworkControlPlane {
    DockerControlPlane.DockerTransportOutcome create(NetworkCreateRequest request,
            ContainmentDeadline deadline);
    DockerControlPlane.DockerTransportOutcome lookupAttempt(NetworkAttemptLookup lookup,
            ContainmentDeadline deadline);
    DockerControlPlane.DockerTransportOutcome inspectExactId(String immutableId,
            ContainmentDeadline deadline);
    DockerControlPlane.DockerTransportOutcome removeExactId(String immutableId,
            ContainmentDeadline deadline);
    DockerControlPlane.DockerTransportOutcome inspectExactIdAbsence(String immutableId,
            ContainmentDeadline deadline);

    record NetworkCreateRequest(SingleOwnerCleanup.ProofIssuer issuer,
            DockerNetworkSpec expected) {
        public NetworkCreateRequest {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(expected, "expected");
        }
    }

    record NetworkAttemptLookup(String executionId, String revision, String attemptNonce,
            String deadlineCorrelation) {
        public NetworkAttemptLookup {
            executionId = required(executionId, "executionId");
            revision = required(revision, "revision");
            attemptNonce = required(attemptNonce, "attemptNonce");
            deadlineCorrelation = required(deadlineCorrelation, "deadlineCorrelation");
        }
        private static String required(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
            return value;
        }
    }
}
