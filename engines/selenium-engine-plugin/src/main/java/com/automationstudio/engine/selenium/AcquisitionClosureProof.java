package com.automationstudio.engine.selenium;

import java.util.Objects;

/** Opaque evidence issued only by one cleanup-scoped transition authority. */
sealed interface AcquisitionClosureProof permits AcquisitionClosureProof.NoAttemptClosed,
        AcquisitionClosureProof.DefiniteFailureClosed {

    static AcquisitionClosureProof noAttemptClosed(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long resourceRevision) {
        return new NoAttemptClosed(issuer, role, resourceRevision);
    }

    static AcquisitionClosureProof definiteFailureClosed(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long resourceRevision,
            AcquisitionResult.DefiniteFailure failure) {
        return new DefiniteFailureClosed(issuer, role, resourceRevision, failure);
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
}
