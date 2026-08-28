package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.Objects;

/** Closed typed Docker boundary. Implementations, never callers, own argv construction. */
interface DockerControlPlane {
    DockerTransportOutcome resolveImage(String imageReference, ContainmentDeadline deadline);
    DockerTransportOutcome create(DockerCreateRequest request, ContainmentDeadline deadline);
    DockerTransportOutcome inspectExactId(String immutableId, ContainmentDeadline deadline);
    DockerTransportOutcome lookupAttempt(DockerAttemptLookup lookup, ContainmentDeadline deadline);
    DockerTransportOutcome removeExactId(String immutableId, ContainmentDeadline deadline);
    DockerTransportOutcome inspectExactIdAbsence(String immutableId, ContainmentDeadline deadline);

    record DockerCreateRequest(SingleOwnerCleanup.ProofIssuer issuer,
            DockerResourceSpec expected, SeleniumContainmentLimits limits) {
        public DockerCreateRequest {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(limits, "limits");
        }
    }

    record DockerAttemptLookup(java.util.UUID executionId, ContainmentResourceRole role,
            String attemptNonce) {
        public DockerAttemptLookup {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(attemptNonce, "attemptNonce");
        }
    }

    enum Dispatch { DEFINITELY_NOT_DISPATCHED, MAY_HAVE_DISPATCHED, DEFINITELY_DISPATCHED }
    enum Completion { COMPLETED, SPAWN_FAILED, TIMED_OUT, INTERRUPTED, DAEMON_FAILED,
        PROTOCOL_FAILED, READ_FAILED, OUTPUT_OVERFLOW, DEADLINE_EXHAUSTED }
    enum Semantic { NONE, EXACT_ID_NOT_FOUND }

    record DockerTransportOutcome(Dispatch dispatch, Completion completion, Integer exitStatus,
            String response, boolean responseComplete, long observationRevision, Semantic semantic,
            DockerDaemonIdentity daemonIdentity) {
        public DockerTransportOutcome {
            Objects.requireNonNull(dispatch, "dispatch");
            Objects.requireNonNull(completion, "completion");
            if (response != null && !responseComplete)
                response = null;
            if (observationRevision <= 0) throw new IllegalArgumentException("Invalid observation revision");
            if (completion == Completion.COMPLETED && exitStatus == null)
                throw new IllegalArgumentException("Completed outcome requires exit status");
            Objects.requireNonNull(semantic, "semantic");
            if (dispatch != Dispatch.DEFINITELY_NOT_DISPATCHED)
                Objects.requireNonNull(daemonIdentity, "daemonIdentity");
            if (dispatch == Dispatch.DEFINITELY_NOT_DISPATCHED
                    && completion != Completion.SPAWN_FAILED
                    && completion != Completion.DEADLINE_EXHAUSTED
                    && completion != Completion.PROTOCOL_FAILED)
                throw new IllegalArgumentException("Invalid pre-dispatch completion");
            if (dispatch == Dispatch.MAY_HAVE_DISPATCHED
                    && completion != Completion.TIMED_OUT && completion != Completion.INTERRUPTED)
                throw new IllegalArgumentException("Invalid uncertain-dispatch completion");
            if (semantic != Semantic.NONE && (dispatch != Dispatch.DEFINITELY_DISPATCHED
                    || completion != Completion.COMPLETED || !responseComplete))
                throw new IllegalArgumentException("Semantic outcome lacks authoritative completion");
        }
        public DockerTransportOutcome(Dispatch dispatch, Completion completion, Integer exitStatus,
                String response, boolean responseComplete, long observationRevision) {
            this(dispatch, completion, exitStatus, response, responseComplete, observationRevision,
                    Semantic.NONE, dispatch == Dispatch.DEFINITELY_NOT_DISPATCHED ? null
                            : DockerDaemonIdentity.testDefault());
        }
        boolean successful() {
            return completion == Completion.COMPLETED && Integer.valueOf(0).equals(exitStatus)
                    && responseComplete;
        }
        boolean definitelyNotDispatched() {
            return dispatch == Dispatch.DEFINITELY_NOT_DISPATCHED;
        }
    }

    record DockerDaemonIdentity(String endpoint, String context, String engineId) {
        public DockerDaemonIdentity {
            if (endpoint == null || endpoint.isBlank() || context == null || context.isBlank()
                    || engineId == null || engineId.isBlank())
                throw new IllegalArgumentException("Incomplete Docker continuity identity");
        }
        static DockerDaemonIdentity testDefault() {
            return new DockerDaemonIdentity("test://engine", "test", "test-generation");
        }
    }

    record DockerLookupResponse(List<String> immutableIds, boolean complete) {
        public DockerLookupResponse {
            immutableIds = List.copyOf(Objects.requireNonNull(immutableIds, "immutableIds"));
        }
    }
}
