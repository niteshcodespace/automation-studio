package com.automationstudio.engine.selenium;

import java.util.Objects;

/** Opaque evidence issued only by one cleanup-scoped transition authority. */
sealed interface AcquisitionClosureProof permits AcquisitionClosureProof.NoAttemptClosed,
        AcquisitionClosureProof.DefiniteFailureClosed,
        AcquisitionClosureProof.DefinitelyNotDispatched {

    static AcquisitionClosureProof noAttemptClosed(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long resourceRevision) {
        return new NoAttemptClosed(issuer, role, resourceRevision);
    }

    static AcquisitionClosureProof definiteFailureClosed(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long resourceRevision,
            AcquisitionResult.DefiniteFailure failure) {
        return new DefiniteFailureClosed(issuer, role, resourceRevision, failure);
    }

    static AcquisitionClosureProof definitelyNotDispatched(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long resourceRevision, Object attempt,
            DockerControlPlane.DockerTransportOutcome outcome) {
        if (!outcome.definitelyNotDispatched())
            throw new IllegalArgumentException("Create may have been dispatched");
        return new DefinitelyNotDispatched(issuer, role, resourceRevision, attempt, outcome);
    }

    boolean issuedBy(SingleOwnerCleanup.ProofIssuer expectedIssuer,
            ContainmentResourceRole expectedRole, long expectedRevision);

    final class NoAttemptClosed implements AcquisitionClosureProof {
        private final SingleOwnerCleanup.ProofIssuer issuer;
        private final ContainmentResourceRole role;
        private final long resourceRevision;

        private NoAttemptClosed(SingleOwnerCleanup.ProofIssuer issuer, ContainmentResourceRole role,
                long resourceRevision) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            this.role = Objects.requireNonNull(role, "role");
            if (resourceRevision <= 0) throw new IllegalArgumentException("Invalid resource revision");
            this.resourceRevision = resourceRevision;
        }

        @Override public boolean issuedBy(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return issuer == expectedIssuer && role == expectedRole
                    && resourceRevision == expectedRevision;
        }

    }

    final class DefiniteFailureClosed implements AcquisitionClosureProof {
        private final SingleOwnerCleanup.ProofIssuer issuer;
        private final ContainmentResourceRole role;
        private final long resourceRevision;
        private final AcquisitionResult.DefiniteFailure failure;

        private DefiniteFailureClosed(SingleOwnerCleanup.ProofIssuer issuer,
                ContainmentResourceRole role, long resourceRevision,
                AcquisitionResult.DefiniteFailure failure) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            this.role = Objects.requireNonNull(role, "role");
            if (resourceRevision <= 0) throw new IllegalArgumentException("Invalid resource revision");
            this.resourceRevision = resourceRevision;
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        AcquisitionResult.DefiniteFailure failure() { return failure; }
        @Override public boolean issuedBy(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return issuer == expectedIssuer && role == expectedRole
                    && resourceRevision == expectedRevision;
        }
    }

    final class DefinitelyNotDispatched implements AcquisitionClosureProof {
        private final SingleOwnerCleanup.ProofIssuer issuer;
        private final ContainmentResourceRole role;
        private final long revision;
        private final Object attempt;
        private final DockerControlPlane.DockerTransportOutcome outcome;
        private DefinitelyNotDispatched(SingleOwnerCleanup.ProofIssuer issuer,
                ContainmentResourceRole role, long revision, Object attempt,
                DockerControlPlane.DockerTransportOutcome outcome) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            this.role = Objects.requireNonNull(role, "role");
            this.attempt = Objects.requireNonNull(attempt, "attempt");
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            if (revision <= 0 || !outcome.definitelyNotDispatched())
                throw new IllegalArgumentException("Invalid non-dispatch closure");
            this.revision = revision;
        }
        Object attempt() { return attempt; }
        DockerControlPlane.DockerTransportOutcome outcome() { return outcome; }
        @Override public boolean issuedBy(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return issuer == expectedIssuer && role == expectedRole && revision == expectedRevision;
        }
    }
}
