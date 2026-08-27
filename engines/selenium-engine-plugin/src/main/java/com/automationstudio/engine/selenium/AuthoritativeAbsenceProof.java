package com.automationstudio.engine.selenium;

import java.util.Objects;

/** A fresh, cleanup-scoped absence capability bound to its causal acquisition. */
final class AuthoritativeAbsenceProof {
    private final SingleOwnerCleanup.ProofIssuer issuer;
    private final ContainmentResourceRole role;
    private final ContainmentIdentity identity;
    private final long acquisitionRevision;
    private final long cleanupRevision;
    private final long observationRevision;
    private final ContainmentEvidence evidence;

    private AuthoritativeAbsenceProof(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, ContainmentIdentity identity,
            long acquisitionRevision, long cleanupRevision,
            long observationRevision, ContainmentEvidence evidence) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.role = Objects.requireNonNull(role, "role");
        this.identity = Objects.requireNonNull(identity, "identity");
        if (acquisitionRevision <= 0 || cleanupRevision <= 0 || observationRevision <= 0) {
            throw new IllegalArgumentException("Proof revisions must be positive");
        }
        this.acquisitionRevision = acquisitionRevision;
        this.cleanupRevision = cleanupRevision;
        this.observationRevision = observationRevision;
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    static AuthoritativeAbsenceProof issue(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentResourceRole role, ContainmentIdentity identity,
            long acquisitionRevision, long cleanupRevision,
            long observationRevision, ContainmentEvidence evidence) {
        return new AuthoritativeAbsenceProof(issuer, role, identity, acquisitionRevision,
                cleanupRevision, observationRevision, evidence);
    }

    ContainmentIdentity identity() { return identity; }
    long cleanupRevision() { return cleanupRevision; }
    long observationRevision() { return observationRevision; }
    ContainmentEvidence evidence() { return evidence; }
    boolean matches(ResourceDisposition.OwnedPresent owned) {
        return owned.matches(issuer, role, acquisitionRevision, identity);
    }
    boolean issuedBy(SingleOwnerCleanup.ProofIssuer expectedIssuer) {
        return issuer == expectedIssuer;
    }
}
