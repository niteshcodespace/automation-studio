package com.automationstudio.engine.selenium;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** The single shared terminal safety authority; safety may only degrade. */
final class AuthoritativeContainmentState {
    private final ContainmentTerminalReport report;
    private final AtomicBoolean compromised;

    private AuthoritativeContainmentState(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentTerminalReport report, boolean initiallyCompromised) {
        this.report = Objects.requireNonNull(report, "report");
        if (!report.issuedBy(Objects.requireNonNull(issuer, "issuer"))) {
            throw new IllegalArgumentException("Terminal report has foreign authority");
        }
        this.compromised = new AtomicBoolean(initiallyCompromised);
    }

    static AuthoritativeContainmentState issue(SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentTerminalReport report, boolean initiallyCompromised) {
        return new AuthoritativeContainmentState(issuer, report, initiallyCompromised);
    }

    ContainmentTerminalReport report() { return report; }

    boolean safe() {
        return !compromised.get()
                && report.containmentCode() == ContainmentCode.ABSENT
                && report.dependencyClosure() == DependencyEvidence.SATISFIED
                && report.resourcesSafe()
                && !report.unresolvedOwnershipPresent()
                && !report.lateOwnedPresent()
                && report.attachmentSafe();
    }

    boolean compromised() { return compromised.get(); }
    void compromise() { compromised.set(true); }
}
