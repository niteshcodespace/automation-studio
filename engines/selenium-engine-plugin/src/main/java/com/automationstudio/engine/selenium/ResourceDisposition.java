package com.automationstudio.engine.selenium;

import java.util.Objects;

sealed interface ResourceDisposition permits ResourceDisposition.NeverAcquired,
        ResourceDisposition.OwnedPresent, ResourceDisposition.OwnedAbsent,
        ResourceDisposition.ForeignExcluded, ResourceDisposition.Ambiguous,
        ResourceDisposition.LateOwnedPresent, ResourceDisposition.LateOwnedAbsent,
        ResourceDisposition.Unknown {

    static NeverAcquired neverAcquired(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long revision, AcquisitionClosureProof proof) {
        Objects.requireNonNull(issuer, "issuer");
        if (!Objects.requireNonNull(proof, "proof").issuedBy(issuer, role, revision)) {
            throw new IllegalArgumentException("Acquisition closure proof has foreign provenance");
        }
        return new NeverAcquired(issuer, role, revision, proof);
    }

    static OwnedAbsent ownedAbsent(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long revision, OwnedPresent owned,
            AuthoritativeAbsenceProof absence) {
        Objects.requireNonNull(issuer, "issuer");
        if (!Objects.requireNonNull(absence, "absence").issuedBy(issuer)) {
            throw new IllegalArgumentException("Absence proof has foreign provenance");
        }
        return new OwnedAbsent(issuer, role, revision, owned, absence);
    }

    /** Untrusted acquisition reports can never directly produce a safe disposition. */
    static ResourceDisposition fromUnownedAcquisition(AcquisitionResult result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case AcquisitionResult.DefiniteFailure failure -> new Unknown(failure.evidence());
            case AcquisitionResult.AmbiguousCompletion ambiguous -> new Ambiguous(ambiguous.evidence());
            case AcquisitionResult.AmbiguousOwnership ambiguous -> new Ambiguous(ambiguous.evidence());
            case AcquisitionResult.ForeignCollision collision -> new Ambiguous(collision.evidence());
            case AcquisitionResult.CreatedAndOwned owned -> new Ambiguous(owned.causalResponse());
        };
    }

    /** Safety is meaningful only when the consuming authority supplies exact role and revision. */
    default boolean safeFor(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long revision) { return false; }

    default boolean authorityBound() { return false; }
    default boolean issuedFor(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, long revision) { return false; }

    default boolean unresolvedOwnership() {
        return this instanceof Ambiguous || this instanceof Unknown
                || this instanceof LateOwnedPresent || this instanceof LateOwnedAbsent;
    }
    default boolean lateOwnedPresent() { return this instanceof LateOwnedPresent; }

    final class NeverAcquired implements ResourceDisposition {
        private final SingleOwnerCleanup.ProofIssuer issuer;
        private final ContainmentResourceRole role;
        private final long revision;
        private final AcquisitionClosureProof proof;
        private NeverAcquired(SingleOwnerCleanup.ProofIssuer issuer, ContainmentResourceRole role,
                long revision, AcquisitionClosureProof proof) {
            this.issuer = issuer; this.role = role; this.revision = revision; this.proof = proof;
        }
        AcquisitionClosureProof proof() { return proof; }
        @Override public boolean authorityBound() { return true; }
        @Override public boolean issuedFor(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return issuer == expectedIssuer && role == expectedRole && revision == expectedRevision;
        }
        @Override public boolean safeFor(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return issuedFor(expectedIssuer, expectedRole, expectedRevision);
        }
    }

    final class OwnedPresent implements ResourceDisposition {
        private final SingleOwnerCleanup.ProofIssuer issuer;
        private final ContainmentResourceRole role;
        private final long acquisitionRevision;
        private final ContainmentIdentity identity;
        private final ContainmentEvidence identityEvidence;
        private OwnedPresent(SingleOwnerCleanup.ProofIssuer issuer, ContainmentResourceRole role,
                long acquisitionRevision, ContainmentIdentity identity,
                ContainmentEvidence identityEvidence) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            this.role = Objects.requireNonNull(role, "role");
            if (acquisitionRevision <= 0) throw new IllegalArgumentException("Invalid acquisition revision");
            this.acquisitionRevision = acquisitionRevision;
            this.identity = Objects.requireNonNull(identity, "identity");
            this.identityEvidence = Objects.requireNonNull(identityEvidence, "identityEvidence");
        }
        static OwnedPresent issue(SingleOwnerCleanup.ProofIssuer issuer,
                ContainmentResourceRole role, long acquisitionRevision,
                AcquisitionResult.CreatedAndOwned result) {
            Objects.requireNonNull(result, "result");
            return new OwnedPresent(issuer, role, acquisitionRevision, result.identity(),
                    result.causalResponse());
        }
        ContainmentIdentity identity() { return identity; }
        ContainmentEvidence identityEvidence() { return identityEvidence; }
        boolean matches(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision,
                ContainmentIdentity expectedIdentity) {
            return issuer == expectedIssuer && role == expectedRole
                    && acquisitionRevision == expectedRevision && identity.equals(expectedIdentity);
        }
        @Override public boolean authorityBound() { return true; }
        @Override public boolean issuedFor(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return matches(expectedIssuer, expectedRole, expectedRevision, identity);
        }
    }

    final class OwnedAbsent implements ResourceDisposition {
        private final SingleOwnerCleanup.ProofIssuer issuer;
        private final ContainmentResourceRole role;
        private final long revision;
        private final OwnedPresent owned;
        private final AuthoritativeAbsenceProof absence;
        private OwnedAbsent(SingleOwnerCleanup.ProofIssuer issuer, ContainmentResourceRole role,
                long revision, OwnedPresent owned, AuthoritativeAbsenceProof absence) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            this.role = Objects.requireNonNull(role, "role");
            this.revision = revision;
            this.owned = Objects.requireNonNull(owned, "owned");
            this.absence = Objects.requireNonNull(absence, "absence");
            if (!absence.matches(owned) || !owned.matches(issuer, role, revision, owned.identity())) {
                throw new IllegalArgumentException("Absence proof is not bound to causal ownership");
            }
        }
        OwnedPresent owned() { return owned; }
        @Override public boolean authorityBound() { return true; }
        @Override public boolean issuedFor(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return issuer == expectedIssuer && role == expectedRole && revision == expectedRevision;
        }
        @Override public boolean safeFor(SingleOwnerCleanup.ProofIssuer expectedIssuer,
                ContainmentResourceRole expectedRole, long expectedRevision) {
            return issuedFor(expectedIssuer, expectedRole, expectedRevision);
        }
    }

    final class ForeignExcluded implements ResourceDisposition {
        private final ForeignExclusionProof proof;
        private ForeignExcluded(ForeignExclusionProof proof) {
            this.proof = Objects.requireNonNull(proof, "proof");
        }
        ForeignExclusionProof proof() { return proof; }
    }

    record Ambiguous(ContainmentEvidence candidateEvidence) implements ResourceDisposition {
        public Ambiguous { Objects.requireNonNull(candidateEvidence, "candidateEvidence"); }
    }
    record LateOwnedPresent(ContainmentIdentity identity, ContainmentEvidence identityEvidence)
            implements ResourceDisposition {
        public LateOwnedPresent {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(identityEvidence, "identityEvidence");
        }
    }
    record LateOwnedAbsent(ContainmentIdentity identity, ContainmentEvidence identityEvidence,
            AuthoritativeAbsenceProof authoritativeAbsence) implements ResourceDisposition {
        public LateOwnedAbsent {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(identityEvidence, "identityEvidence");
            Objects.requireNonNull(authoritativeAbsence, "authoritativeAbsence");
            if (!identity.equals(authoritativeAbsence.identity())) {
                throw new IllegalArgumentException("Late absence proof does not match identity");
            }
        }
    }
    record Unknown(ContainmentEvidence evidence) implements ResourceDisposition {
        public Unknown { Objects.requireNonNull(evidence, "evidence"); }
    }
}
