package com.automationstudio.engine.selenium;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Serializes cleanup ownership, acquisition closure, mutation, and terminal publication. */
final class SingleOwnerCleanup {
    private static final ContainmentEvidence OWNER_FAILURE =
            new ContainmentEvidence("owner-cleanup-failed");
    private final ContainmentDeadline deadline;
    private final ProofIssuer proofIssuer = new ProofIssuer();
    private final AuthoritativeAbsenceSource absenceSource;
    private final AwaitBoundary awaitBoundary;
    private final EnumMap<ContainmentResourceRole, ResourceTransition> resources =
            new EnumMap<>(ContainmentResourceRole.class);
    private final CleanupCompletion completion = new CleanupCompletion();
    private final List<OwnershipEvidence> ownershipEvidence = new ArrayList<>();
    private final List<OwnershipEvidence> lateEvidence = new ArrayList<>();
    private Claim owner;
    private boolean acquisitionsClosed;
    private boolean ownerExecutionStarted;
    private boolean terminalCompromised;
    private AuthoritativeContainmentState terminalState;
    private long resourceRevision;
    private long cleanupRevision;
    private long observationRevision;

    SingleOwnerCleanup(ContainmentDeadline deadline) {
        this(deadline, AwaitBoundary.NONE, false);
    }

    SingleOwnerCleanup(ContainmentDeadline deadline, AwaitBoundary awaitBoundary) {
        this(deadline, awaitBoundary, false);
    }

    private SingleOwnerCleanup(ContainmentDeadline deadline, AwaitBoundary awaitBoundary,
            boolean trustedAbsenceEnabled) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.awaitBoundary = Objects.requireNonNull(awaitBoundary, "awaitBoundary");
        this.absenceSource = trustedAbsenceEnabled
                ? new TrustedAbsenceSource(proofIssuer) : new UnavailableAbsenceSource();
        for (ContainmentResourceRole role : ContainmentResourceRole.values()) {
            resources.put(role, new ResourceTransition(role, Math.incrementExact(resourceRevision)));
        }
    }

    synchronized Claim claim() {
        if (owner == null) {
            owner = new Claim(this, true);
            return owner;
        }
        return new Claim(this, false);
    }

    synchronized boolean recordOwnership(OwnershipEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        ownershipEvidence.add(evidence);
        if (!acquisitionsClosed) return false;
        lateEvidence.add(evidence);
        terminalCompromised = true;
        if (terminalState != null) terminalState.compromise();
        return true;
    }

    synchronized List<OwnershipEvidence> ownershipEvidence() {
        return List.copyOf(ownershipEvidence);
    }

    synchronized List<OwnershipEvidence> lateEvidence() {
        return List.copyOf(lateEvidence);
    }

    synchronized boolean acquisitionsClosed() { return acquisitionsClosed; }
    ContainmentDeadline deadline() { return deadline; }

    <N> DependencyController<N> dependencies(ContainmentDependencyDag<N> dag) {
        return new DependencyController<>(Objects.requireNonNull(dag, "dag"));
    }

    synchronized ResourceTransition resource(Claim claim, ContainmentResourceRole role) {
        requireOwner(claim);
        if (acquisitionsClosed) throw new IllegalStateException("Acquisitions are closed");
        return resources.get(Objects.requireNonNull(role, "role"));
    }

    private AuthoritativeContainmentState executeOwner(Claim claim, OwnerCleanupWork work) {
        synchronized (this) {
            requireOwner(claim);
            if (ownerExecutionStarted) throw new IllegalStateException("Owner cleanup already started");
            ownerExecutionStarted = true;
            resources.values().forEach(ResourceTransition::closeUntouchedForCleanup);
            acquisitionsClosed = true;
            cleanupRevision = Math.incrementExact(cleanupRevision);
        }
        TerminalOutcome outcome;
        try {
            outcome = Objects.requireNonNull(work.run(), "owner cleanup outcome");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            outcome = ownerFailureOutcome();
        } catch (Throwable failure) {
            outcome = ownerFailureOutcome();
        }
        return publishOnce(outcome);
    }

    private synchronized AuthoritativeContainmentState publishOnce(TerminalOutcome outcome) {
        if (terminalState == null) {
            var dispositions = new EnumMap<ContainmentResourceRole, ResourceDisposition>(
                    ContainmentResourceRole.class);
            var revisions = new EnumMap<ContainmentResourceRole, Long>(ContainmentResourceRole.class);
            resources.forEach((role, transition) -> {
                dispositions.put(role, transition.terminalDisposition());
                revisions.put(role, transition.revision);
                transition.consumeForTerminalReport();
            });
            var report = ContainmentTerminalReport.issue(proofIssuer, outcome.executionOutcome(),
                    outcome.containmentCode(), dispositions, revisions, outcome.workerAttachment(),
                    outcome.dependencyClosure());
            if (ownershipEvidence.stream().anyMatch(evidence -> !report.reconciles(evidence))) {
                terminalCompromised = true;
            }
            terminalState = AuthoritativeContainmentState.issue(
                    proofIssuer, report, terminalCompromised);
            completion.complete(terminalState);
        }
        return terminalState;
    }

    private void requireOwner(Claim claim) {
        if (claim != owner || !claim.owner) throw new IllegalStateException("Cleanup caller is not owner");
    }

    private static TerminalOutcome ownerFailureOutcome() {
        return new TerminalOutcome(ExecutionOutcome.FAILED, ContainmentCode.UNSAFE,
                AttachmentState.UNRESOLVED, DependencyEvidence.UNKNOWN);
    }

    @FunctionalInterface
    interface OwnerCleanupWork { TerminalOutcome run() throws Exception; }

    record TerminalOutcome(ExecutionOutcome executionOutcome, ContainmentCode containmentCode,
            AttachmentState workerAttachment, DependencyEvidence dependencyClosure) {
        TerminalOutcome {
            Objects.requireNonNull(executionOutcome, "executionOutcome");
            Objects.requireNonNull(containmentCode, "containmentCode");
            Objects.requireNonNull(workerAttachment, "workerAttachment");
            Objects.requireNonNull(dependencyClosure, "dependencyClosure");
        }
    }

    interface AwaitBoundary {
        AwaitBoundary NONE = new AwaitBoundary() {};
        default void afterInitialCompletionRead() throws InterruptedException {}
        default void afterTimedTimeout() throws InterruptedException {}
    }

    /** Unforgeable outside this cleanup instance; proof factories require this exact capability. */
    static final class ProofIssuer { private ProofIssuer() {} }

    private interface AuthoritativeAbsenceSource {
        AuthoritativeAbsenceObservation observe(ProofIssuer issuer, ContainmentResourceRole role,
                long acquisitionRevision, long cleanupRevision, long observationRevision,
                ContainmentIdentity identity);
    }

    private static final class UnavailableAbsenceSource implements AuthoritativeAbsenceSource {
        @Override public AuthoritativeAbsenceObservation observe(ProofIssuer issuer,
                ContainmentResourceRole role, long acquisitionRevision, long cleanupRevision,
                long observationRevision, ContainmentIdentity identity) {
            throw new IllegalStateException("Authoritative absence source is unavailable");
        }
    }

    private static final class TrustedAbsenceSource implements AuthoritativeAbsenceSource {
        private final ProofIssuer authority;
        private TrustedAbsenceSource(ProofIssuer authority) { this.authority = authority; }
        @Override public AuthoritativeAbsenceObservation observe(ProofIssuer issuer,
                ContainmentResourceRole role, long acquisitionRevision, long cleanupRevision,
                long observationRevision, ContainmentIdentity identity) {
            if (issuer != authority) throw new IllegalArgumentException("Foreign cleanup authority");
            return new AuthoritativeAbsenceObservation(authority, role, acquisitionRevision,
                    cleanupRevision, observationRevision, identity,
                    new ContainmentEvidence("authoritative-absence-observed"));
        }
    }

    private record AuthoritativeAbsenceObservation(ProofIssuer issuer,
            ContainmentResourceRole role, long acquisitionRevision, long cleanupRevision,
            long observationRevision, ContainmentIdentity identity, ContainmentEvidence evidence) {
        private AuthoritativeAbsenceObservation {
            Objects.requireNonNull(issuer, "issuer"); Objects.requireNonNull(role, "role");
            Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(evidence, "evidence");
            if (acquisitionRevision <= 0 || cleanupRevision <= 0 || observationRevision <= 0) {
                throw new IllegalArgumentException("Observation revisions must be positive");
            }
        }
        private boolean matches(ProofIssuer expectedIssuer, ContainmentResourceRole expectedRole,
                long expectedAcquisition, long expectedCleanup, ContainmentIdentity expectedIdentity) {
            return issuer == expectedIssuer && role == expectedRole
                    && acquisitionRevision == expectedAcquisition
                    && cleanupRevision == expectedCleanup && identity.equals(expectedIdentity);
        }
    }

    final class ResourceTransition {
        private enum State { OPEN, ACQUIRING, OWNED, CLOSED }
        private final ContainmentResourceRole role;
        private final long revision;
        private State state = State.OPEN;
        private AcquisitionAttempt attempt;
        private ResourceDisposition.OwnedPresent owned;
        private ResourceDisposition disposition;
        private boolean consumed;

        private ResourceTransition(ContainmentResourceRole role, long revision) {
            this.role = role; this.revision = revision;
        }

        ResourceDisposition.NeverAcquired closeWithoutAttempt(Claim claim) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                requireBeforeCleanup();
                requireState(State.OPEN);
                state = State.CLOSED;
                disposition = ResourceDisposition.neverAcquired(proofIssuer, role, revision,
                        AcquisitionClosureProof.noAttemptClosed(proofIssuer, role, revision));
                return (ResourceDisposition.NeverAcquired) disposition;
            }
        }

        AcquisitionAttempt beginAcquisition(Claim claim) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                requireBeforeCleanup();
                requireState(State.OPEN);
                state = State.ACQUIRING;
                attempt = new AcquisitionAttempt(proofIssuer, revision);
                return attempt;
            }
        }

        ResourceDisposition recordAcquisition(Claim claim, AcquisitionAttempt expected,
                AcquisitionResult result) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                requireBeforeCleanup();
                requireState(State.ACQUIRING);
                if (expected != attempt || expected.issuer != proofIssuer
                        || expected.resourceRevision != revision) {
                    throw new IllegalArgumentException("Acquisition attempt does not match resource");
                }
                Objects.requireNonNull(result, "result");
                if (result instanceof AcquisitionResult.CreatedAndOwned created) {
                    owned = ResourceDisposition.OwnedPresent.issue(
                            proofIssuer, role, revision, created);
                    disposition = owned;
                    state = State.OWNED;
                    return owned;
                }
                state = State.CLOSED;
                disposition = ResourceDisposition.fromUnownedAcquisition(result);
                return disposition;
            }
        }

        ResourceDisposition.OwnedAbsent verifyAbsent(Claim claim,
                ResourceDisposition.OwnedPresent expected) throws Exception {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                if (!ownerExecutionStarted || !acquisitionsClosed || cleanupRevision <= 0) {
                    throw new IllegalStateException("Authoritative absence requires active cleanup");
                }
                requireState(State.OWNED);
                if (expected != owned || !expected.matches(proofIssuer, role, revision,
                        expected.identity())) {
                    throw new IllegalArgumentException("Ownership does not match resource transition");
                }
                long freshObservation = Math.incrementExact(observationRevision);
                var observation = absenceSource.observe(proofIssuer, role, revision,
                        cleanupRevision, freshObservation, expected.identity());
                if (!observation.matches(proofIssuer, role, revision, cleanupRevision,
                        expected.identity())) {
                    throw new IllegalStateException("Untrusted or stale absence observation");
                }
                var proof = AuthoritativeAbsenceProof.issue(proofIssuer, role, expected.identity(),
                        revision, cleanupRevision, freshObservation, observation.evidence());
                state = State.CLOSED;
                disposition = ResourceDisposition.ownedAbsent(
                        proofIssuer, role, revision, expected, proof);
                return (ResourceDisposition.OwnedAbsent) disposition;
            }
        }

        private ResourceDisposition terminalDisposition() {
            return disposition == null ? new ResourceDisposition.Unknown(OWNER_FAILURE) : disposition;
        }

        private void closeUntouchedForCleanup() {
            if (state == State.OPEN) {
                state = State.CLOSED;
                disposition = ResourceDisposition.neverAcquired(proofIssuer, role, revision,
                        AcquisitionClosureProof.noAttemptClosed(proofIssuer, role, revision));
            }
        }

        private void consumeForTerminalReport() {
            if (consumed) throw new IllegalStateException("Resource disposition already consumed");
            consumed = true;
        }

        private void requireBeforeCleanup() {
            if (acquisitionsClosed) throw new IllegalStateException("Acquisitions are closed");
        }
        private void requireState(State expected) {
            if (state != expected) throw new IllegalStateException(
                    "Expected resource state " + expected + " but was " + state);
        }
    }

    static final class AcquisitionAttempt {
        private final ProofIssuer issuer;
        private final long resourceRevision;
        private AcquisitionAttempt(ProofIssuer issuer, long resourceRevision) {
            this.issuer = issuer;
            this.resourceRevision = resourceRevision;
        }
    }

    final class Claim {
        private final SingleOwnerCleanup coordinator;
        private final boolean owner;
        private Claim(SingleOwnerCleanup coordinator, boolean owner) {
            this.coordinator = coordinator;
            this.owner = owner;
        }
        boolean owner() { return owner; }
        CleanupCompletion completion() { return completion; }
        ContainmentDeadline deadline() { return coordinator.deadline; }
        AuthoritativeContainmentState execute(OwnerCleanupWork work) {
            return coordinator.executeOwner(this, Objects.requireNonNull(work, "work"));
        }
    }

    final class DependencyController<N> {
        private final ContainmentDependencyDag<N> dag;
        private final HashMap<N, DependencyEvidence> evidence = new HashMap<>();
        private long revision;
        private boolean actionInProgress;

        private DependencyController(ContainmentDependencyDag<N> dag) { this.dag = dag; }

        void update(Claim claim, N node, DependencyEvidence value) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                if (actionInProgress) throw new IllegalStateException("Dependency action in progress");
                dag.prerequisites(node);
                evidence.put(Objects.requireNonNull(node, "node"),
                        Objects.requireNonNull(value, "value"));
                revision = Math.incrementExact(revision);
            }
        }

        DependencyEvidenceSnapshot<N> snapshot() {
            synchronized (SingleOwnerCleanup.this) {
                return new DependencyEvidenceSnapshot<>(revision, evidence);
            }
        }

        EligibilityDecision<N> performIfEligible(Claim claim, N node, Runnable action) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                var snapshot = new DependencyEvidenceSnapshot<N>(revision, evidence);
                var decision = dag.eligibility(node, snapshot);
                if (decision.status() == DependencyEvidence.SATISFIED) {
                    performDecisionLocked(decision, Objects.requireNonNull(action, "action"));
                }
                return decision;
            }
        }

        void performDecision(Claim claim, EligibilityDecision<N> decision, Runnable action) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                performDecisionLocked(Objects.requireNonNull(decision, "decision"),
                        Objects.requireNonNull(action, "action"));
            }
        }

        private void performDecisionLocked(EligibilityDecision<N> decision, Runnable action) {
            if (decision.revision() != revision) throw new IllegalStateException("Stale eligibility decision");
            var current = dag.eligibility(decision.node(),
                    new DependencyEvidenceSnapshot<N>(revision, evidence));
            if (current.status() != DependencyEvidence.SATISFIED) {
                throw new IllegalStateException("Dependency is not satisfied");
            }
            actionInProgress = true;
            try { action.run(); } finally { actionInProgress = false; }
        }
    }

    final class CleanupCompletion {
        private final CompletableFuture<AuthoritativeContainmentState> future = new CompletableFuture<>();
        private boolean complete(AuthoritativeContainmentState state) { return future.complete(state); }
        boolean isDone() { return future.isDone(); }
        AuthoritativeContainmentState await()
                throws InterruptedException, ExecutionException, TimeoutException {
            AuthoritativeContainmentState published = future.getNow(null);
            if (published != null) return published;
            awaitBoundary.afterInitialCompletionRead();
            long remaining = deadline.remainingNanos();
            if (remaining == 0) {
                published = future.getNow(null);
                if (published != null) return published;
                throw new TimeoutException("Containment deadline expired");
            }
            try {
                return future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException failure) {
                awaitBoundary.afterTimedTimeout();
                published = future.getNow(null);
                if (published != null) return published;
                throw failure;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw failure;
            }
        }
    }
}
