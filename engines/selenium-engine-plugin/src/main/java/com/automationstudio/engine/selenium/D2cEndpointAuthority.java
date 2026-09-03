package com.automationstudio.engine.selenium;

import static com.automationstudio.engine.selenium.D2cTopologyModel.EndpointInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Cleanup-owned one-shot connect/detach authority for exact container/network pairs. */
final class D2cEndpointAuthority {
    enum Phase { NOT_ATTEMPTED, CONNECT_DISPATCHED, VERIFIED_PRESENT, DETACH_DISPATCHED,
        AWAITING_ABSENCE, VERIFIED_ABSENT, FAILED, AMBIGUOUS }
    record Key(EndpointInstance instance, String containerId, String networkId, long generation) {
        Key { Objects.requireNonNull(instance); id(containerId); id(networkId); if (generation <= 0) throw new IllegalArgumentException(); }
    }
    record Evidence(String endpointId, String ipv4, String prefix, String mac,
            DockerControlPlane.DockerDaemonIdentity daemon) {
        Evidence {
            id(endpointId); Objects.requireNonNull(daemon);
            if (ipv4 == null || !ipv4.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException();
            if (!"28".equals(prefix) || mac == null || !mac.matches("[a-f0-9]{2}(?::[a-f0-9]{2}){5}")) throw new IllegalArgumentException();
        }
    }
    record Snapshot(Key key, long sequence, Phase phase, Evidence evidence, String failure) {}
    enum MembershipState { PRESENT, ABSENT, UNKNOWN }
    record Membership(MembershipState state, Evidence evidence,
            DockerControlPlane.DockerDaemonIdentity daemon, long observationRevision) {
        Membership {
            Objects.requireNonNull(state); Objects.requireNonNull(daemon);
            if (observationRevision <= 0 || state == MembershipState.PRESENT != (evidence != null))
                throw new IllegalArgumentException("Invalid endpoint membership");
            if (evidence != null && !daemon.equals(evidence.daemon())) throw new IllegalArgumentException("Daemon mismatch");
        }
    }
    interface Operations {
        DockerControlPlane.DockerTransportOutcome connect(Key key, String ipv4, ContainmentDeadline deadline);
        Membership inspectMembership(Key key, ContainmentDeadline deadline);
        DockerControlPlane.DockerTransportOutcome disconnect(Key key, ContainmentDeadline deadline);
    }

    private final Object lock; private final SingleOwnerCleanup.ProofIssuer issuer;
    private final ContainmentDeadline deadline; private final DockerControlPlane.DockerDaemonIdentity daemon;
    private final Operations operations; private final Map<Key, Mutable> entries = new LinkedHashMap<>();
    private long sequence;

    D2cEndpointAuthority(Object lock, SingleOwnerCleanup.ProofIssuer issuer, ContainmentDeadline deadline,
            DockerControlPlane.DockerDaemonIdentity daemon, Operations operations) {
        this.lock = Objects.requireNonNull(lock); this.issuer = Objects.requireNonNull(issuer);
        this.deadline = Objects.requireNonNull(deadline); this.daemon = Objects.requireNonNull(daemon);
        this.operations = Objects.requireNonNull(operations);
    }
    void register(SingleOwnerCleanup.ProofIssuer authority, Key key, String expectedIpv4) {
        require(authority); synchronized (lock) {
            if (entries.putIfAbsent(key, new Mutable(++sequence, expectedIpv4)) != null) throw new IllegalStateException("Duplicate endpoint");
        }
    }
    Phase connectAndVerify(SingleOwnerCleanup.ProofIssuer authority, Key key) {
        require(authority); synchronized (lock) {
            Mutable entry = entry(key); if (entry.phase != Phase.NOT_ATTEMPTED) throw new IllegalStateException("Connect already attempted");
            if (deadline.expired()) return entry.fail(Phase.FAILED, "deadline-before-connect");
            var result = operations.connect(key, entry.expectedIpv4, deadline);
            if (result.dispatch() == DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED)
                return entry.fail(Phase.FAILED, "connect-not-dispatched");
            entry.phase = Phase.CONNECT_DISPATCHED;
            Membership membership = operations.inspectMembership(key, deadline);
            Evidence observed = membership == null ? null : membership.evidence();
            if (membership == null || membership.state()!=MembershipState.PRESENT || !membership.daemon().equals(daemon)
                    || observed == null || !observed.ipv4().equals(entry.expectedIpv4))
                return entry.fail(Phase.AMBIGUOUS, "connect-unverified");
            entry.evidence = observed; entry.phase = Phase.VERIFIED_PRESENT; return entry.phase;
        }
    }
    Phase reconcileDetach(SingleOwnerCleanup.ProofIssuer authority, Key key) {
        require(authority); synchronized (lock) {
            Mutable entry = entry(key);
            if (entry.phase == Phase.VERIFIED_ABSENT) return entry.phase;
            if (entry.phase == Phase.FAILED && entry.failure != null && entry.failure.equals("connect-not-dispatched")) {
                entry.phase=Phase.VERIFIED_ABSENT;entry.failure=null;return entry.phase;
            }
            if (entry.phase == Phase.AMBIGUOUS || entry.phase == Phase.CONNECT_DISPATCHED) {
                Membership membership=deadline.expired()?null:operations.inspectMembership(key,deadline);
                if(membership==null||!daemon.equals(membership.daemon())||membership.state()==MembershipState.UNKNOWN){entry.failure="possible-connect-unresolved";return entry.phase=Phase.AMBIGUOUS;}
                if(membership.state()==MembershipState.ABSENT){entry.phase=Phase.VERIFIED_ABSENT;entry.failure=null;return entry.phase;}
                Evidence observed=membership.evidence();if(observed==null||!observed.ipv4().equals(entry.expectedIpv4)){entry.failure="late-membership-mismatch";return entry.phase=Phase.AMBIGUOUS;}
                entry.evidence=observed;entry.phase=Phase.VERIFIED_PRESENT;
            }
            if (entry.phase == Phase.FAILED) return entry.phase;
            if (entry.phase == Phase.VERIFIED_PRESENT) {
                if (deadline.expired()) return entry.fail(Phase.AMBIGUOUS, "deadline-before-detach");
                entry.phase = Phase.DETACH_DISPATCHED;
                var result = operations.disconnect(key, deadline);
                if (result.dispatch() == DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED || !result.successful())
                    return entry.fail(Phase.FAILED, "detach-not-authoritative");
                entry.phase = Phase.AWAITING_ABSENCE;
            }
            if (entry.phase == Phase.AWAITING_ABSENCE) {
                Membership membership=deadline.expired()?null:operations.inspectMembership(key,deadline);
                if (membership==null || membership.state()!=MembershipState.ABSENT || !daemon.equals(membership.daemon())) {
                    entry.failure = "absence-unproved"; return entry.phase;
                }
                entry.phase = Phase.VERIFIED_ABSENT;
                entry.failure = null;
            }
            return entry.phase;
        }
    }
    List<Snapshot> ledger(SingleOwnerCleanup.ProofIssuer authority) {
        require(authority); synchronized (lock) { return entries.entrySet().stream()
                .map(e -> e.getValue().snapshot(e.getKey())).sorted(Comparator.comparingLong(Snapshot::sequence)).toList(); }
    }
    DockerControlPlane.DockerDaemonIdentity daemon(SingleOwnerCleanup.ProofIssuer authority){require(authority);return daemon;}
    boolean freshPresent(SingleOwnerCleanup.ProofIssuer authority){require(authority);synchronized(lock){if(entries.isEmpty())return false;for(var e:entries.entrySet()){
        Membership observed=operations.inspectMembership(e.getKey(),deadline);if(observed==null||observed.state()!=MembershipState.PRESENT||!daemon.equals(observed.daemon())
                ||observed.evidence()==null||!observed.evidence().ipv4().equals(e.getValue().expectedIpv4))return false;}return true;}}
    private void require(SingleOwnerCleanup.ProofIssuer authority) { if (authority != issuer) throw new SecurityException("Foreign endpoint authority"); }
    private Mutable entry(Key key) { Mutable result = entries.get(key); if (result == null) throw new IllegalArgumentException("Unknown endpoint"); return result; }
    private static void id(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid immutable ID"); }
    private static final class Mutable {
        final long sequence; final String expectedIpv4; Phase phase = Phase.NOT_ATTEMPTED; Evidence evidence; String failure;
        Mutable(long sequence, String expectedIpv4) { this.sequence=sequence; this.expectedIpv4=Objects.requireNonNull(expectedIpv4); }
        Phase fail(Phase value, String reason) { phase=value; failure=reason; return value; }
        Snapshot snapshot(Key key) { return new Snapshot(key,sequence,phase,evidence,failure); }
    }
}
