package com.automationstudio.engine.selenium;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Cleanup-owned D2b network acquisition, identity ledger, and reconciliation authority. */
final class DockerNetworkAuthority {
    enum AcquisitionStatus { ACQUIRED, DEFINITE_NO_SIDE_EFFECT, AMBIGUOUS,
        LATE_RETAINED, IDEMPOTENT, CONFLICT }
    enum ReconciliationStatus { ABSENT, DEPENDENCY_UNRESOLVED, UNRESOLVED }
    enum EntryState { VERIFIED_PRESENT, OWNED_ABSENT, UNRESOLVED }
    enum RemovalPhase { NOT_ATTEMPTED, AWAITING_ABSENCE, REMOVE_FAILED, ABSENT }

    record LedgerEntry(String immutableId, long registrationSequence, DockerNetworkFingerprint fingerprint,
            DockerControlPlane.DockerDaemonIdentity daemonIdentity, EntryState state, RemovalPhase removalPhase,
            boolean conflict, String removeResult, String absenceResult, String unresolvedReason) {}
    record ReconciliationResult(ReconciliationStatus status, List<LedgerEntry> entries) {
        ReconciliationResult { entries = List.copyOf(entries); }
    }

    interface Boundaries {
        Boundaries NONE = new Boundaries() {};
        default void beforeCreateDispatch() {}
        default void afterCreateDispatch() {}
        default void afterPossibleSideEffect() {}
        default void afterIdBeforeInspect() {}
        default void afterInspectBeforeVerification() {}
        default void afterVerificationBeforeHandoff() {}
        default void duringHandoff() {}
        default void duringDependencyRegistration() {}
        default void duringRecovery() {}
        default void cleanupStartVsLateOwnership() {}
        default void beforeNetworkRemoveEligibility() {}
        default void removeVsAbsenceObservation() {}
        default void terminalPublicationVsLateEvidence() {}
    }

    static final class Attempt {
        private final SingleOwnerCleanup.ProofIssuer issuer;
        private final DockerNetworkSpec spec;
        private final ContainmentDeadline deadline;
        private boolean consumed;
        private boolean closed;
        private DockerControlPlane.DockerDaemonIdentity causalDaemon;
        private Attempt(SingleOwnerCleanup.ProofIssuer issuer, DockerNetworkSpec spec,
                ContainmentDeadline deadline) {
            this.issuer = issuer; this.spec = spec; this.deadline = deadline;
        }
        DockerNetworkSpec spec() { return spec; }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Object lock;
    private final SingleOwnerCleanup.ProofIssuer issuer;
    private final ContainmentDeadline deadline;
    private final DockerNetworkControlPlane docker;
    private final Boundaries boundaries;
    private final Map<SingleOwnerCleanup.DockerIdentityKey, Object> globalIdentities;
    private final Runnable compromise;
    private final LinkedHashMap<String, MutableEntry> ledger = new LinkedHashMap<>();
    private Attempt attempt;
    private long sequence;
    private boolean cleanupStarted;
    private boolean dependencyRegistered;
    private boolean definitelyNotDispatched;
    private boolean ambiguous;
    private boolean workerAbsentForCleanup;
    private DockerControlPlane.DockerTransportOutcome closureOutcome;

    DockerNetworkAuthority(Object lock, SingleOwnerCleanup.ProofIssuer issuer,
            ContainmentDeadline deadline, DockerNetworkControlPlane docker, Boundaries boundaries,
            Map<SingleOwnerCleanup.DockerIdentityKey, Object> globalIdentities, Runnable compromise) {
        this.lock = Objects.requireNonNull(lock); this.issuer = Objects.requireNonNull(issuer);
        this.deadline = Objects.requireNonNull(deadline); this.docker = Objects.requireNonNull(docker);
        this.boundaries = Objects.requireNonNull(boundaries); this.globalIdentities = Objects.requireNonNull(globalIdentities);
        this.compromise = Objects.requireNonNull(compromise);
    }

    Attempt begin(SingleOwnerCleanup.ProofIssuer authority, UUID executionId, long revision) {
        synchronized (lock) {
            requireIssuer(authority); if (cleanupStarted || attempt != null)
                throw new IllegalStateException("Network acquisition unavailable");
            String nonce = token(), correlation = token();
            attempt = new Attempt(issuer, DockerNetworkSpec.create(executionId, revision, nonce, correlation), deadline);
            return attempt;
        }
    }

    AcquisitionStatus acquire(SingleOwnerCleanup.ProofIssuer authority, Attempt expected) {
        synchronized (lock) {
            requireAttempt(authority, expected); if (cleanupStarted || expected.consumed)
                throw new IllegalStateException("Network acquisition already consumed or closed");
            expected.consumed = true;
        }
        boundaries.beforeCreateDispatch();
        var created = docker.create(new DockerNetworkControlPlane.NetworkCreateRequest(issuer, expected.spec), deadline);
        boundaries.afterCreateDispatch(); boundaries.afterPossibleSideEffect();
        expected.causalDaemon = created.daemonIdentity();
        if (!created.successful()) {
            if (created.definitelyNotDispatched()) {
                synchronized (lock) { closureOutcome = created; definitelyNotDispatched = true; }
                return close(expected, AcquisitionStatus.DEFINITE_NO_SIDE_EFFECT);
            }
            return recoverOrAmbiguous(expected);
        }
        String id = created.response() == null ? "" : created.response().strip();
        if (!validId(id)) return recoverOrAmbiguous(expected);
        boundaries.afterIdBeforeInspect();
        if (deadline.expired()) return close(expected, AcquisitionStatus.AMBIGUOUS);
        return inspectAndHandoff(expected, id, false, created.daemonIdentity());
    }

    AcquisitionStatus recordLate(SingleOwnerCleanup.ProofIssuer authority, Attempt expected, String id) {
        synchronized (lock) { requireAttempt(authority, expected); }
        boundaries.cleanupStartVsLateOwnership();
        AcquisitionStatus status = inspectAndHandoff(expected, id, true, expected.causalDaemon);
        boolean reconcileNow;
        synchronized (lock) { reconcileNow = cleanupStarted && workerAbsentForCleanup
                && (status == AcquisitionStatus.LATE_RETAINED || status == AcquisitionStatus.CONFLICT); }
        if (reconcileNow) reconcile(authority, expected, true);
        return status;
    }

    private AcquisitionStatus recoverOrAmbiguous(Attempt expected) {
        boundaries.duringRecovery();
        var lookup = docker.lookupAttempt(new DockerNetworkControlPlane.NetworkAttemptLookup(
                expected.spec.executionId().toString(), Long.toUnsignedString(expected.spec.acquisitionRevision()),
                expected.spec.attemptNonce(), expected.spec.deadlineCorrelation()), deadline);
        if (!lookup.successful() || lookup.response() == null
                || expected.causalDaemon != null && !expected.causalDaemon.equals(lookup.daemonIdentity()))
            return close(expected, AcquisitionStatus.AMBIGUOUS);
        List<String> ids = lookup.response().lines().filter(value -> !value.isBlank()).toList();
        if (ids.size() != 1 || !validId(ids.get(0))) return close(expected, AcquisitionStatus.AMBIGUOUS);
        return inspectAndHandoff(expected, ids.get(0), false, lookup.daemonIdentity());
    }

    private AcquisitionStatus inspectAndHandoff(Attempt expected, String id, boolean late,
            DockerControlPlane.DockerDaemonIdentity causalDaemon) {
        if (!validId(id)) return late ? conflict() : close(expected, AcquisitionStatus.AMBIGUOUS);
        var inspected = docker.inspectExactId(id, deadline); boundaries.afterInspectBeforeVerification();
        if (!inspected.successful() || inspected.dispatch() != DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                || inspected.response() == null || causalDaemon != null
                && !causalDaemon.equals(inspected.daemonIdentity()))
            return late ? conflict() : close(expected, AcquisitionStatus.AMBIGUOUS);
        if (deadline.expired()) return late ? conflict() : close(expected, AcquisitionStatus.AMBIGUOUS);
        final DockerNetworkFingerprint fingerprint;
        try { fingerprint = DockerNetworkInspectParser.parse(inspected.response(), expected.spec, id); }
        catch (RuntimeException failure) { return late ? conflict() : close(expected, AcquisitionStatus.AMBIGUOUS); }
        boundaries.afterVerificationBeforeHandoff();
        return accept(expected, fingerprint, inspected.daemonIdentity(), late || deadline.expired());
    }

    private AcquisitionStatus accept(Attempt expected, DockerNetworkFingerprint fingerprint,
            DockerControlPlane.DockerDaemonIdentity daemon, boolean late) {
        synchronized (lock) {
            boundaries.duringHandoff(); requireAttempt(issuer, expected);
            late = late || deadline.expired();
            var identityKey = new SingleOwnerCleanup.DockerIdentityKey(
                    ContainmentResourceRole.NETWORK, fingerprint.networkId());
            Object existingGlobal = globalIdentities.get(identityKey);
            if (existingGlobal instanceof Handoff handoff) {
                if (handoff.same(expected, fingerprint, daemon)) return AcquisitionStatus.IDEMPOTENT;
                markConflict(fingerprint.networkId()); compromise.run(); return AcquisitionStatus.CONFLICT;
            }
            if (existingGlobal != null) { compromise.run(); return AcquisitionStatus.CONFLICT; }
            var handoff = new Handoff(issuer, expected, fingerprint, daemon, deadline);
            boolean crossRoleConflict = globalIdentities.keySet().stream().anyMatch(key ->
                    key.immutableId().equals(fingerprint.networkId())
                            && key.role() != ContainmentResourceRole.NETWORK);
            globalIdentities.put(identityKey, handoff);
            MutableEntry entry = ledger.get(fingerprint.networkId());
            if (entry == null) {
                entry = new MutableEntry(fingerprint, daemon, ++sequence); ledger.put(fingerprint.networkId(), entry);
            }
            boundaries.duringDependencyRegistration(); dependencyRegistered = true;
            late = late || deadline.expired();
            boolean different = ledger.size() > 1 || crossRoleConflict;
            if (different) { ledger.values().forEach(value -> value.conflict = true); compromise.run(); }
            if (late || cleanupStarted || expected.closed) {
                boundaries.terminalPublicationVsLateEvidence(); compromise.run();
                return different ? AcquisitionStatus.CONFLICT : AcquisitionStatus.LATE_RETAINED;
            }
            expected.closed = true; return different ? AcquisitionStatus.CONFLICT : AcquisitionStatus.ACQUIRED;
        }
    }

    ReconciliationResult reconcile(SingleOwnerCleanup.ProofIssuer authority, Attempt expected,
            boolean workerAuthoritativelyAbsent) {
        synchronized (lock) {
            requireAttempt(authority, expected); cleanupStarted = true;
            workerAbsentForCleanup = workerAuthoritativelyAbsent;
            boundaries.beforeNetworkRemoveEligibility();
            if (ledger.isEmpty() && definitelyNotDispatched)
                return result(ReconciliationStatus.ABSENT);
            if (!workerAuthoritativelyAbsent || !dependencyRegistered) {
                ledger.values().forEach(entry -> entry.unresolved("worker-dependency-unresolved"));
                compromise.run(); return result(ReconciliationStatus.DEPENDENCY_UNRESOLVED);
            }
            List<MutableEntry> work = ledger.values().stream()
                    .filter(value -> value.state != EntryState.OWNED_ABSENT)
                    .sorted(Comparator.comparingLong(
                    (MutableEntry value) -> value.sequence).reversed()).toList();
            boolean unresolved = false;
            for (MutableEntry entry : work) {
            if (entry.removalPhase == RemovalPhase.REMOVE_FAILED) { unresolved = true; continue; }
            if (entry.removalPhase == RemovalPhase.NOT_ATTEMPTED) {
                if (deadline.expired()) { entry.unresolved("deadline-exhausted"); unresolved = true; continue; }
                var removal = docker.removeExactId(entry.fingerprint.networkId(), deadline);
                if (!removal.successful() || removal.dispatch() != DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                        || !entry.daemon.equals(removal.daemonIdentity())) {
                    entry.removalPhase = RemovalPhase.REMOVE_FAILED;
                    entry.removeResult = "failed"; entry.unresolved("remove-not-authoritative");
                    unresolved = true; continue;
                }
                entry.removalPhase = RemovalPhase.AWAITING_ABSENCE;
                entry.removeResult = "removed";
            }
            boundaries.removeVsAbsenceObservation();
            if (deadline.expired()) { entry.unresolved("deadline-exhausted-before-absence"); unresolved = true; continue; }
            var absence = docker.inspectExactIdAbsence(entry.fingerprint.networkId(), deadline);
            if (absence.dispatch() != DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                    || absence.completion() != DockerControlPlane.Completion.COMPLETED
                    || absence.semantic() != DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND
                    || !absence.responseComplete() || !entry.daemon.equals(absence.daemonIdentity())) {
                if (entry.absenceResult.equals("not-observed")) entry.absenceResult = "unproved";
                entry.unresolved("absence-not-authoritative"); unresolved = true; continue;
            }
            entry.absenceResult = entry.absenceResult.equals("unproved")
                    ? "unproved;later-exact-id-absent" : "exact-id-absent";
            entry.removalPhase = RemovalPhase.ABSENT; entry.state = EntryState.OWNED_ABSENT;
            entry.unresolvedReason = null;
            }
            boolean remaining = ledger.values().stream()
                    .anyMatch(value -> value.state != EntryState.OWNED_ABSENT);
            if (unresolved || remaining) {
                compromise.run(); return result(ReconciliationStatus.UNRESOLVED);
            }
            return result(ReconciliationStatus.ABSENT);
        }
    }

    List<LedgerEntry> ledger(SingleOwnerCleanup.ProofIssuer authority) {
        synchronized (lock) { requireIssuer(authority); return snapshots(); }
    }
    ContainmentDeadline deadline() { return deadline; }
    DockerControlPlane.DockerTransportOutcome closureOutcome() { synchronized (lock) { return closureOutcome; } }

    private ReconciliationResult result(ReconciliationStatus status) {
        synchronized (lock) { return new ReconciliationResult(status, snapshots()); }
    }
    private List<LedgerEntry> snapshots() {
        var result = new ArrayList<LedgerEntry>(); ledger.values().forEach(value -> result.add(value.snapshot()));
        return List.copyOf(result);
    }
    private AcquisitionStatus close(Attempt expected, AcquisitionStatus status) {
        synchronized (lock) { expected.closed = true; if (status == AcquisitionStatus.AMBIGUOUS) {
            ambiguous = true; compromise.run(); } return status; }
    }
    private AcquisitionStatus conflict() { synchronized (lock) { compromise.run(); return AcquisitionStatus.CONFLICT; } }
    private void markConflict(String id) { MutableEntry entry = ledger.get(id); if (entry != null) entry.conflict = true; }
    private void requireAttempt(SingleOwnerCleanup.ProofIssuer authority, Attempt expected) {
        requireIssuer(authority); if (expected == null || expected != attempt || expected.issuer != issuer
                || expected.deadline != deadline) throw new IllegalArgumentException("Foreign network attempt");
    }
    private void requireIssuer(SingleOwnerCleanup.ProofIssuer authority) {
        if (authority != issuer) throw new SecurityException("Foreign cleanup authority");
    }
    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{64}"); }
    private static String token() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }

    private record Handoff(SingleOwnerCleanup.ProofIssuer issuer, Attempt attempt,
            DockerNetworkFingerprint fingerprint, DockerControlPlane.DockerDaemonIdentity daemon,
            ContainmentDeadline deadline) {
        private Handoff { Objects.requireNonNull(issuer); Objects.requireNonNull(attempt);
            Objects.requireNonNull(fingerprint); Objects.requireNonNull(daemon); Objects.requireNonNull(deadline); }
        boolean same(Attempt candidate, DockerNetworkFingerprint value,
                DockerControlPlane.DockerDaemonIdentity daemonIdentity) {
            return attempt == candidate && fingerprint.equals(value) && daemon.equals(daemonIdentity)
                    && deadline == candidate.deadline;
        }
    }
    private static final class MutableEntry {
        final DockerNetworkFingerprint fingerprint; final DockerControlPlane.DockerDaemonIdentity daemon;
        final long sequence; EntryState state = EntryState.VERIFIED_PRESENT; boolean conflict;
        RemovalPhase removalPhase = RemovalPhase.NOT_ATTEMPTED;
        String removeResult = "not-attempted", absenceResult = "not-observed", unresolvedReason;
        MutableEntry(DockerNetworkFingerprint fingerprint, DockerControlPlane.DockerDaemonIdentity daemon,
                long sequence) { this.fingerprint = fingerprint; this.daemon = daemon; this.sequence = sequence; }
        void unresolved(String reason) { state = EntryState.UNRESOLVED; unresolvedReason = reason; }
        LedgerEntry snapshot() { return new LedgerEntry(fingerprint.networkId(), sequence, fingerprint,
                daemon, state, removalPhase, conflict, removeResult, absenceResult, unresolvedReason); }
    }
}
