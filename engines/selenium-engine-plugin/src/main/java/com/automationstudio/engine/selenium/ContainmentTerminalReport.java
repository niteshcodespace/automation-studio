package com.automationstudio.engine.selenium;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable terminal payload issued only by its cleanup authority. */
final class ContainmentTerminalReport {
    private final SingleOwnerCleanup.ProofIssuer issuer;
    private final ExecutionOutcome executionOutcome;
    private final ContainmentCode containmentCode;
    private final Map<ContainmentResourceRole, ResourceDisposition> dispositions;
    private final Map<ContainmentResourceRole, Long> revisions;
    private final AttachmentState workerAttachment;
    private final DependencyEvidence dependencyClosure;
    private final D2cTerminalEvidence d2cEvidence;

    private ContainmentTerminalReport(SingleOwnerCleanup.ProofIssuer issuer,
            ExecutionOutcome executionOutcome, ContainmentCode containmentCode,
            Map<ContainmentResourceRole, ResourceDisposition> dispositions,
            Map<ContainmentResourceRole, Long> revisions, AttachmentState workerAttachment,
            DependencyEvidence dependencyClosure,D2cTerminalEvidence d2cEvidence) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.executionOutcome = Objects.requireNonNull(executionOutcome, "executionOutcome");
        this.containmentCode = Objects.requireNonNull(containmentCode, "containmentCode");
        this.workerAttachment = Objects.requireNonNull(workerAttachment, "workerAttachment");
        this.dependencyClosure = Objects.requireNonNull(dependencyClosure, "dependencyClosure");
        this.d2cEvidence=Objects.requireNonNull(d2cEvidence,"d2cEvidence");
        var dispositionCopy = new EnumMap<ContainmentResourceRole, ResourceDisposition>(
                ContainmentResourceRole.class);
        var revisionCopy = new EnumMap<ContainmentResourceRole, Long>(ContainmentResourceRole.class);
        for (ContainmentResourceRole role : ContainmentResourceRole.values()) {
            var disposition = Objects.requireNonNull(dispositions.get(role), "disposition for " + role);
            long revision = Objects.requireNonNull(revisions.get(role), "revision for " + role);
            if (revision <= 0) throw new IllegalArgumentException("Invalid revision for " + role);
            if (disposition.authorityBound() && !disposition.issuedFor(issuer, role, revision)) {
                throw new IllegalArgumentException("Disposition provenance mismatch for " + role);
            }
            dispositionCopy.put(role, disposition);
            revisionCopy.put(role, revision);
        }
        if (dispositions.size() != ContainmentResourceRole.values().length
                || revisions.size() != ContainmentResourceRole.values().length) {
            throw new IllegalArgumentException("Terminal report requires each exact resource role once");
        }
        this.dispositions = Map.copyOf(dispositionCopy);
        this.revisions = Map.copyOf(revisionCopy);
    }

    static ContainmentTerminalReport issue(SingleOwnerCleanup.ProofIssuer issuer,
            ExecutionOutcome executionOutcome, ContainmentCode containmentCode,
            Map<ContainmentResourceRole, ResourceDisposition> dispositions,
            Map<ContainmentResourceRole, Long> revisions, AttachmentState workerAttachment,
            DependencyEvidence dependencyClosure) {
        return new ContainmentTerminalReport(issuer, executionOutcome, containmentCode,
                dispositions, revisions, workerAttachment, dependencyClosure,D2cTerminalEvidence.empty());
    }
    static ContainmentTerminalReport issue(SingleOwnerCleanup.ProofIssuer issuer,ExecutionOutcome executionOutcome,ContainmentCode containmentCode,
            Map<ContainmentResourceRole,ResourceDisposition> dispositions,Map<ContainmentResourceRole,Long> revisions,AttachmentState workerAttachment,
            DependencyEvidence dependencyClosure,D2cTerminalEvidence d2cEvidence){return new ContainmentTerminalReport(issuer,executionOutcome,containmentCode,dispositions,revisions,workerAttachment,dependencyClosure,d2cEvidence);}

    boolean issuedBy(SingleOwnerCleanup.ProofIssuer expected) { return issuer == expected; }
    ExecutionOutcome executionOutcome() { return executionOutcome; }
    ContainmentCode containmentCode() { return containmentCode; }
    ResourceDisposition network() { return disposition(ContainmentResourceRole.NETWORK); }
    ResourceDisposition anchor() { return disposition(ContainmentResourceRole.ANCHOR); }
    ResourceDisposition bootstrap() { return disposition(ContainmentResourceRole.BOOTSTRAP); }
    ResourceDisposition gateway() { return disposition(ContainmentResourceRole.GATEWAY); }
    ResourceDisposition worker() { return disposition(ContainmentResourceRole.WORKER); }
    AttachmentState workerAttachment() { return workerAttachment; }
    DependencyEvidence dependencyClosure() { return dependencyClosure; }

    boolean resourcesSafe() {
        return d2cEvidence.safe()&&dispositions.entrySet().stream().allMatch(entry -> entry.getValue().safeFor(
                issuer, entry.getKey(), revisions.get(entry.getKey())));
    }

    boolean unresolvedOwnershipPresent() {
        return dispositions.values().stream().anyMatch(ResourceDisposition::unresolvedOwnership);
    }

    boolean lateOwnedPresent() {
        return dispositions.values().stream().anyMatch(ResourceDisposition::lateOwnedPresent);
    }

    boolean reconciles(OwnershipEvidence evidence) {
        return dispositions.values().stream()
                .filter(ResourceDisposition.OwnedAbsent.class::isInstance)
                .map(ResourceDisposition.OwnedAbsent.class::cast)
                .anyMatch(absent -> absent.owned().identity().equals(evidence.identity()));
    }

    boolean attachmentSafe() {
        return workerAttachment == AttachmentState.CLOSED
                || workerAttachment == AttachmentState.NOT_STARTED
                && worker().safeFor(issuer, ContainmentResourceRole.WORKER,
                        revisions.get(ContainmentResourceRole.WORKER));
    }

    private ResourceDisposition disposition(ContainmentResourceRole role) {
        return dispositions.get(role);
    }
}
