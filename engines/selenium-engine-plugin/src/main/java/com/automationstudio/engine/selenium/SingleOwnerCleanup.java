package com.automationstudio.engine.selenium;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/** Serializes cleanup ownership, acquisition closure, mutation, and terminal publication. */
final class SingleOwnerCleanup {
    private static final ContainmentEvidence OWNER_FAILURE =
            new ContainmentEvidence("owner-cleanup-failed");
    private final ContainmentDeadline deadline;
    private final ProofIssuer proofIssuer = new ProofIssuer();
    private final AuthoritativeAbsenceSource absenceSource;
    private final AwaitBoundary awaitBoundary;
    private final DockerControlPlane docker;
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
    private final Map<String, VerifiedHandoff> dockerOwnership = new HashMap<>();

    SingleOwnerCleanup(ContainmentDeadline deadline) {
        this(deadline, AwaitBoundary.NONE, false, null);
    }

    SingleOwnerCleanup(ContainmentDeadline deadline, AwaitBoundary awaitBoundary) {
        this(deadline, awaitBoundary, false, null);
    }

    static SingleOwnerCleanup docker(ContainmentDeadline deadline) {
        return new SingleOwnerCleanup(deadline, AwaitBoundary.NONE, false, null, true);
    }

    private SingleOwnerCleanup(ContainmentDeadline deadline, AwaitBoundary awaitBoundary,
            boolean trustedAbsenceEnabled) {
        this(deadline, awaitBoundary, trustedAbsenceEnabled, null);
    }

    private SingleOwnerCleanup(ContainmentDeadline deadline, AwaitBoundary awaitBoundary,
            boolean trustedAbsenceEnabled, DockerControlPlane docker) {
        this(deadline, awaitBoundary, trustedAbsenceEnabled, docker, false);
    }

    private SingleOwnerCleanup(ContainmentDeadline deadline, AwaitBoundary awaitBoundary,
            boolean trustedAbsenceEnabled, DockerControlPlane docker, boolean productionDocker) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.awaitBoundary = Objects.requireNonNull(awaitBoundary, "awaitBoundary");
        this.docker = productionDocker ? DockerCliControlPlane.trusted(proofIssuer) : docker;
        this.absenceSource = this.docker != null
                ? new DockerAbsenceSource(proofIssuer, this.docker, deadline)
                : trustedAbsenceEnabled ? new TrustedAbsenceSource(proofIssuer)
                : new UnavailableAbsenceSource();
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
        default void c1BeforeCreateDispatch() {}
        default void c2AfterCreateDispatch() {}
        default void c3AfterPossibleSideEffect() {}
        default void c4AfterIdBeforeInspect() {}
        default void c5AfterInspect() {}
        default void c6AfterVerification() {}
        default void c7DuringHandoff() {}
        default void c8AfterOwnershipRegistration() {}
        default void c9DuringRecovery() {}
        default void c10DuringAbsenceInspection() {}
    }

    /** Unforgeable outside this cleanup instance; proof factories require this exact capability. */
    static final class ProofIssuer { private ProofIssuer() {} }

    private interface AuthoritativeAbsenceSource {
        AuthoritativeAbsenceObservation observe(ProofIssuer issuer, ContainmentResourceRole role,
                long acquisitionRevision, long cleanupRevision, long observationRevision,
                ContainmentIdentity identity, DockerControlPlane.DockerDaemonIdentity expectedDaemon);
    }

    private static final class UnavailableAbsenceSource implements AuthoritativeAbsenceSource {
        @Override public AuthoritativeAbsenceObservation observe(ProofIssuer issuer,
                ContainmentResourceRole role, long acquisitionRevision, long cleanupRevision,
                long observationRevision, ContainmentIdentity identity,
                DockerControlPlane.DockerDaemonIdentity expectedDaemon) {
            throw new IllegalStateException("Authoritative absence source is unavailable");
        }
    }

    private static final class TrustedAbsenceSource implements AuthoritativeAbsenceSource {
        private final ProofIssuer authority;
        private TrustedAbsenceSource(ProofIssuer authority) { this.authority = authority; }
        @Override public AuthoritativeAbsenceObservation observe(ProofIssuer issuer,
                ContainmentResourceRole role, long acquisitionRevision, long cleanupRevision,
                long observationRevision, ContainmentIdentity identity,
                DockerControlPlane.DockerDaemonIdentity expectedDaemon) {
            if (issuer != authority) throw new IllegalArgumentException("Foreign cleanup authority");
            return new AuthoritativeAbsenceObservation(authority, role, acquisitionRevision,
                    cleanupRevision, observationRevision, identity,
                    new ContainmentEvidence("authoritative-absence-observed"));
        }
    }

    private static final class DockerAbsenceSource implements AuthoritativeAbsenceSource {
        private final ProofIssuer authority;
        private final DockerControlPlane docker;
        private final ContainmentDeadline deadline;
        private DockerAbsenceSource(ProofIssuer authority, DockerControlPlane docker,
                ContainmentDeadline deadline) {
            this.authority = authority; this.docker = docker; this.deadline = deadline;
        }
        @Override public AuthoritativeAbsenceObservation observe(ProofIssuer issuer,
                ContainmentResourceRole role, long acquisitionRevision, long cleanupRevision,
                long observationRevision, ContainmentIdentity identity,
                DockerControlPlane.DockerDaemonIdentity expectedDaemon) {
            if (issuer != authority) throw new IllegalArgumentException("Foreign cleanup authority");
            var outcome = docker.inspectExactIdAbsence(identity.value(), deadline);
            if (outcome.dispatch() != DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                    || outcome.completion() != DockerControlPlane.Completion.COMPLETED
                    || outcome.semantic() != DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND
                    || !outcome.responseComplete() || expectedDaemon == null
                    || !expectedDaemon.equals(outcome.daemonIdentity())) {
                throw new IllegalStateException("Exact-ID absence was not authoritative");
            }
            return new AuthoritativeAbsenceObservation(authority, role, acquisitionRevision,
                    cleanupRevision, observationRevision, identity,
                    new ContainmentEvidence("docker-exact-id-not-found:" + outcome.observationRevision()));
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
        private enum State { OPEN, ACQUIRING, DISPATCH_IN_PROGRESS, OWNED, CLOSED }
        private final ContainmentResourceRole role;
        private final long revision;
        private State state = State.OPEN;
        private AcquisitionAttempt attempt;
        private ResourceDisposition.OwnedPresent owned;
        private final Map<String, RetainedDockerOwnership> retainedLateOwnership =
                new LinkedHashMap<>();
        private DockerControlPlane.DockerDaemonIdentity ownershipDaemon;
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
                if (docker != null)
                    throw new IllegalStateException("Docker ownership requires verified acquisition");
                state = State.ACQUIRING;
                attempt = new AcquisitionAttempt(proofIssuer, revision);
                return attempt;
            }
        }

        AcquisitionAttempt beginDockerAcquisition(Claim claim, UUID executionId,
                DockerResourceTemplate template) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim); requireBeforeCleanup(); requireState(State.OPEN);
                if (docker == null) throw new IllegalStateException("Docker authority is unavailable");
                byte[] random = new byte[32]; NonceHolder.RANDOM.nextBytes(random);
                String nonce = HexFormat.of().formatHex(random);
                DockerResourceSpec spec = Objects.requireNonNull(template, "template")
                        .bind(Objects.requireNonNull(executionId, "executionId"), role, nonce);
                state = State.ACQUIRING;
                attempt = new AcquisitionAttempt(proofIssuer, role, revision, executionId, spec,
                        template.limits(), deadline);
                return attempt;
            }
        }

        DockerAcquisitionStatus acquireDocker(Claim claim, AcquisitionAttempt expected) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim); requireBeforeCleanup(); requireState(State.ACQUIRING);
                requireAttempt(expected);
                if (expected.consumed) throw new IllegalStateException("Acquisition attempt already dispatched");
                expected.consumed = true;
                state = State.DISPATCH_IN_PROGRESS;
            }
            awaitBoundary.c1BeforeCreateDispatch();
            if (!expected.spec.imageReference().equals(expected.spec.imageIdentity())) {
                var resolution = docker.resolveImage(expected.spec.imageReference(), deadline);
                if (!resolution.successful() || resolution.response() == null
                        || !resolution.response().strip().matches("sha256:[a-f0-9]{64}"))
                    return closeAmbiguous(claim, expected, "docker-image-resolution-failed");
                expected.spec = expected.spec.withResolvedImage(resolution.response().strip());
                expected.resolutionDaemon = resolution.daemonIdentity();
            }
            var create = docker.create(new DockerControlPlane.DockerCreateRequest(
                    proofIssuer, expected.spec, expected.limits), deadline);
            awaitBoundary.c2AfterCreateDispatch();
            awaitBoundary.c3AfterPossibleSideEffect();
            expected.creationDaemon = create.daemonIdentity();
            if (expected.resolutionDaemon != null && create.daemonIdentity() != null
                    && !expected.resolutionDaemon.equals(create.daemonIdentity()))
                return closeAmbiguous(claim, expected, "docker-daemon-continuity-changed");
            if (!create.successful()) {
                if (create.definitelyNotDispatched()) return closeNotDispatched(claim, expected, create);
                return recoverOrAmbiguous(claim, expected);
            }
            String id = create.response() == null ? "" : create.response().strip();
            if (!id.matches("[a-f0-9]{64}")) return recoverOrAmbiguous(claim, expected);
            awaitBoundary.c4AfterIdBeforeInspect();
            return inspectAndHandoff(claim, expected, id, false, create.daemonIdentity());
        }

        DockerAcquisitionStatus recordLateDockerAcquisition(Claim claim,
                AcquisitionAttempt expected, String immutableId) {
            requireDockerAttempt(claim, expected, false);
            return inspectAndHandoff(claim, expected, immutableId, true, expected.creationDaemon);
        }

        private DockerAcquisitionStatus recoverOrAmbiguous(Claim claim, AcquisitionAttempt expected) {
            awaitBoundary.c9DuringRecovery();
            var lookup = docker.lookupAttempt(new DockerControlPlane.DockerAttemptLookup(
                    expected.executionId, role, expected.spec.attemptNonce()), deadline);
            if (!lookup.successful() || lookup.response() == null)
                return closeAmbiguous(claim, expected, "docker-recovery-incomplete");
            if (expected.creationDaemon != null
                    && !expected.creationDaemon.equals(lookup.daemonIdentity()))
                return closeAmbiguous(claim, expected, "docker-recovery-daemon-changed");
            List<String> ids = lookup.response().lines().filter(line -> !line.isBlank()).toList();
            if (ids.size() != 1 || !ids.get(0).matches("[a-f0-9]{64}"))
                return closeAmbiguous(claim, expected, "docker-recovery-ambiguous");
            return inspectAndHandoff(claim, expected, ids.get(0), false, lookup.daemonIdentity());
        }

        private DockerAcquisitionStatus inspectAndHandoff(Claim claim, AcquisitionAttempt expected,
                String immutableId, boolean late, DockerControlPlane.DockerDaemonIdentity causalDaemon) {
            if (immutableId == null || !immutableId.matches("[a-f0-9]{64}"))
                return late ? conflict() : closeAmbiguous(claim, expected, "invalid-docker-id");
            var inspected = docker.inspectExactId(immutableId, deadline);
            awaitBoundary.c5AfterInspect();
            if (!inspected.successful()
                    || inspected.dispatch() != DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                    || inspected.response() == null)
                return late ? conflict() : closeAmbiguous(claim, expected, "docker-inspection-failed");
            final DockerResourceFingerprint fingerprint;
            try { fingerprint = DockerInspectParser.parse(inspected.response()); }
            catch (RuntimeException failure) {
                return late ? conflict() : closeAmbiguous(claim, expected, "docker-inspection-malformed");
            }
            if (!expected.spec.fingerprint(immutableId).equals(fingerprint))
                return late ? conflict() : closeAmbiguous(claim, expected, "docker-fingerprint-mismatch");
            if (causalDaemon != null && !causalDaemon.equals(inspected.daemonIdentity()))
                return late ? conflict() : closeAmbiguous(claim, expected, "docker-daemon-continuity-changed");
            awaitBoundary.c6AfterVerification();
            return acceptVerified(claim, expected, new VerifiedHandoff(proofIssuer, role, revision,
                    expected, fingerprint, inspected.daemonIdentity()), late);
        }

        private DockerAcquisitionStatus acceptVerified(Claim claim, AcquisitionAttempt expected,
                VerifiedHandoff handoff, boolean late) {
            synchronized (SingleOwnerCleanup.this) {
                awaitBoundary.c7DuringHandoff();
                requireOwner(claim); requireAttempt(expected);
                VerifiedHandoff existing = dockerOwnership.get(handoff.fingerprint.containerId());
                if (existing != null) {
                    if (existing.sameProvenance(handoff)) return DockerAcquisitionStatus.IDEMPOTENT;
                    compromiseLocked(); return DockerAcquisitionStatus.CONFLICT;
                }
                dockerOwnership.put(handoff.fingerprint.containerId(), handoff);
                if (state == State.DISPATCH_IN_PROGRESS && !acquisitionsClosed && !late) {
                    owned = ResourceDisposition.OwnedPresent.issueVerified(proofIssuer, role, revision,
                            new ContainmentIdentity(handoff.fingerprint.containerId()),
                            new ContainmentEvidence("verified-docker-inspection"), handoff);
                    disposition = owned; state = State.OWNED; ownershipDaemon = handoff.daemonIdentity;
                    awaitBoundary.c8AfterOwnershipRegistration();
                    return DockerAcquisitionStatus.ACQUIRED;
                }
                var retained = ResourceDisposition.OwnedPresent.issueVerified(proofIssuer, role,
                        revision, new ContainmentIdentity(handoff.fingerprint.containerId()),
                        new ContainmentEvidence("verified-late-docker-inspection"), handoff);
                boolean differentIdConflict = !retainedLateOwnership.isEmpty();
                retainedLateOwnership.put(handoff.fingerprint.containerId(),
                        new RetainedDockerOwnership(retained, handoff.daemonIdentity));
                compromiseLocked();
                return differentIdConflict
                        ? DockerAcquisitionStatus.CONFLICT : DockerAcquisitionStatus.LATE_RETAINED;
            }
        }

        private DockerAcquisitionStatus closeNotDispatched(Claim claim, AcquisitionAttempt expected,
                DockerControlPlane.DockerTransportOutcome outcome) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim); requireState(State.DISPATCH_IN_PROGRESS);
                requireAttempt(expected);
                var proof = AcquisitionClosureProof.definitelyNotDispatched(proofIssuer, role,
                        revision, expected, outcome);
                state = State.CLOSED;
                disposition = ResourceDisposition.neverAcquired(proofIssuer, role, revision, proof);
                return DockerAcquisitionStatus.DEFINITE_NO_SIDE_EFFECT;
            }
        }

        private DockerAcquisitionStatus closeAmbiguous(Claim claim, AcquisitionAttempt expected,
                String evidence) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim); requireState(State.DISPATCH_IN_PROGRESS);
                requireAttempt(expected); state = State.CLOSED;
                disposition = new ResourceDisposition.Ambiguous(new ContainmentEvidence(evidence));
                return DockerAcquisitionStatus.AMBIGUOUS;
            }
        }

        private DockerAcquisitionStatus conflict() {
            synchronized (SingleOwnerCleanup.this) {
                compromiseLocked(); return DockerAcquisitionStatus.CONFLICT;
            }
        }

        private void requireDockerAttempt(Claim claim, AcquisitionAttempt expected,
                boolean requireOpenAcquisition) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                if (requireOpenAcquisition) { requireBeforeCleanup(); requireState(State.ACQUIRING); }
                requireAttempt(expected);
            }
        }
        private void requireAttempt(AcquisitionAttempt expected) {
            if (expected != attempt || expected.issuer != proofIssuer || expected.role != role
                    || expected.resourceRevision != revision || expected.deadline != deadline
                    || expected.spec == null)
                throw new IllegalArgumentException("Acquisition attempt does not match resource");
        }

        ResourceDisposition recordAcquisition(Claim claim, AcquisitionAttempt expected,
                AcquisitionResult result) {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                requireBeforeCleanup();
                requireState(State.ACQUIRING);
                if (docker != null)
                    throw new IllegalStateException("Caller-created Docker acquisition is not authoritative");
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
                awaitBoundary.c10DuringAbsenceInspection();
                var observation = absenceSource.observe(proofIssuer, role, revision,
                        cleanupRevision, freshObservation, expected.identity(), ownershipDaemon);
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

        ResourceDisposition.OwnedAbsent removeAndVerifyDockerAbsent(Claim claim,
                ResourceDisposition.OwnedPresent expected) throws Exception {
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim);
                if (docker == null || !ownerExecutionStarted || !acquisitionsClosed)
                    throw new IllegalStateException("Docker cleanup requires active owner cleanup");
                requireState(State.OWNED);
                if (expected != owned || !expected.matches(proofIssuer, role, revision,
                        expected.identity()))
                    throw new IllegalArgumentException("Ownership does not match resource transition");
            }
            var removal = docker.removeExactId(expected.identity().value(), deadline);
            if (!removal.successful()
                    || removal.dispatch() != DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                    || ownershipDaemon == null || !ownershipDaemon.equals(removal.daemonIdentity()))
                throw new IllegalStateException("Exact-ID removal was not authoritative");
            return verifyAbsent(claim, expected);
        }

        ResourceDisposition.OwnedAbsent removeAndVerifyRetainedDockerAbsent(Claim claim,
                AcquisitionAttempt expected) throws Exception {
            final List<RetainedDockerOwnership> retainedOwnership;
            synchronized (SingleOwnerCleanup.this) {
                requireOwner(claim); requireAttempt(expected);
                if (!ownerExecutionStarted || !acquisitionsClosed || retainedLateOwnership.isEmpty())
                    throw new IllegalStateException("No retained late Docker ownership");
                retainedOwnership = List.copyOf(retainedLateOwnership.values());
            }
            ResourceDisposition.OwnedAbsent lastAbsent = null;
            for (RetainedDockerOwnership retainedOwnershipRecord : retainedOwnership) {
                var retained = retainedOwnershipRecord.owned();
                var retainedDaemon = retainedOwnershipRecord.daemonIdentity();
                var removal = docker.removeExactId(retained.identity().value(), deadline);
                if (!removal.successful()
                        || removal.dispatch() != DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                        || !Objects.equals(retainedDaemon, removal.daemonIdentity()))
                    throw new IllegalStateException("Late exact-ID removal was not authoritative");
                var outcome = docker.inspectExactIdAbsence(retained.identity().value(), deadline);
                awaitBoundary.c10DuringAbsenceInspection();
                if (outcome.completion() != DockerControlPlane.Completion.COMPLETED
                        || outcome.semantic() != DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND
                        || !Objects.equals(retainedDaemon, outcome.daemonIdentity()))
                    throw new IllegalStateException("Late exact-ID absence was not authoritative");
                var proof = AuthoritativeAbsenceProof.issue(proofIssuer, role, retained.identity(), revision,
                        Math.max(1, cleanupRevision), Math.incrementExact(observationRevision),
                        new ContainmentEvidence("docker-late-exact-id-not-found:"
                                + outcome.observationRevision()));
                lastAbsent = ResourceDisposition.ownedAbsent(
                        proofIssuer, role, revision, retained, proof);
            }
            return lastAbsent;
        }

        private record RetainedDockerOwnership(ResourceDisposition.OwnedPresent owned,
                DockerControlPlane.DockerDaemonIdentity daemonIdentity) {
            private RetainedDockerOwnership {
                Objects.requireNonNull(owned, "owned");
                Objects.requireNonNull(daemonIdentity, "daemonIdentity");
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
        private final ContainmentResourceRole role;
        private final long resourceRevision;
        private final UUID executionId;
        private DockerResourceSpec spec;
        private final SeleniumContainmentLimits limits;
        private final ContainmentDeadline deadline;
        private boolean consumed;
        private DockerControlPlane.DockerDaemonIdentity resolutionDaemon;
        private DockerControlPlane.DockerDaemonIdentity creationDaemon;
        private AcquisitionAttempt(ProofIssuer issuer, long resourceRevision) {
            this(issuer, null, resourceRevision, null, null, null, null);
        }
        private AcquisitionAttempt(ProofIssuer issuer, ContainmentResourceRole role,
                long resourceRevision, UUID executionId, DockerResourceSpec spec,
                SeleniumContainmentLimits limits, ContainmentDeadline deadline) {
            this.issuer = issuer;
            this.role = role;
            this.resourceRevision = resourceRevision;
            this.executionId = executionId;
            this.spec = spec;
            this.limits = limits;
            this.deadline = deadline;
        }
        String attemptNonce() { return spec == null ? null : spec.attemptNonce(); }
    }

    enum DockerAcquisitionStatus { ACQUIRED, DEFINITE_NO_SIDE_EFFECT, AMBIGUOUS,
        LATE_RETAINED, IDEMPOTENT, CONFLICT }

    private record VerifiedHandoff(ProofIssuer issuer, ContainmentResourceRole role, long revision,
            AcquisitionAttempt attempt, DockerResourceFingerprint fingerprint,
            DockerControlPlane.DockerDaemonIdentity daemonIdentity) {
        private boolean sameProvenance(VerifiedHandoff other) {
            return issuer == other.issuer && role == other.role && revision == other.revision
                    && attempt == other.attempt && fingerprint.equals(other.fingerprint);
        }
    }
    private static final class NonceHolder { private static final SecureRandom RANDOM = new SecureRandom(); }

    private void compromiseLocked() {
        terminalCompromised = true;
        if (terminalState != null) terminalState.compromise();
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
