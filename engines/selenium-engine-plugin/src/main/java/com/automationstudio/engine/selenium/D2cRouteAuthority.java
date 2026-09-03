package com.automationstudio.engine.selenium;

import java.util.Objects;

/** One-shot, inspect-only-after-dispatch worker route authority. */
final class D2cRouteAuthority {
    enum Phase { NOT_ATTEMPTED, DISPATCHED, VERIFY_ONLY, VERIFIED, DEFINITE_PRE_DISPATCH_FAILURE, AMBIGUOUS, UNSAFE }
    record Binding(String workerId, long pid, long processStartIdentity, long namespaceDevice,
            long namespaceInode, String networkId, String workerIpv4, String gatewayIpv4,
            String interfaceName, long generation, String helperDigest,
            DockerControlPlane.DockerDaemonIdentity daemon) {
        Binding {
            id(workerId); id(networkId); digest(helperDigest); Objects.requireNonNull(daemon);
            if (pid <= 0 || processStartIdentity <= 0 || namespaceDevice <= 0 || namespaceInode <= 0 || generation <= 0)
                throw new IllegalArgumentException("Invalid namespace binding");
            if (!interfaceName.matches("[a-zA-Z0-9_.-]{1,15}")) throw new IllegalArgumentException("Invalid interface");
        }
    }
    record Result(DockerControlPlane.Dispatch dispatch, boolean canonicalRoute) { Result { Objects.requireNonNull(dispatch); } }
    interface Helper {
        Result replaceDefaultRoute(Binding binding, ContainmentDeadline deadline);
        boolean inspectCanonicalRoute(Binding binding, ContainmentDeadline deadline);
    }
    private final Object lock; private final SingleOwnerCleanup.ProofIssuer issuer; private final ContainmentDeadline deadline;
    private final Helper helper; private final Binding binding; private Phase phase=Phase.NOT_ATTEMPTED;
    D2cRouteAuthority(Object lock, SingleOwnerCleanup.ProofIssuer issuer, ContainmentDeadline deadline, Helper helper, Binding binding) {
        this.lock=Objects.requireNonNull(lock); this.issuer=Objects.requireNonNull(issuer); this.deadline=Objects.requireNonNull(deadline);
        this.helper=Objects.requireNonNull(helper); this.binding=Objects.requireNonNull(binding);
    }
    Phase establish(SingleOwnerCleanup.ProofIssuer authority) {
        require(authority); synchronized(lock) {
            if (phase != Phase.NOT_ATTEMPTED && phase != Phase.DEFINITE_PRE_DISPATCH_FAILURE) throw new IllegalStateException("Mutation already dispatched");
            if (deadline.expired()) return phase=Phase.DEFINITE_PRE_DISPATCH_FAILURE;
            Result result=helper.replaceDefaultRoute(binding,deadline);
            if (result.dispatch()==DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED) return phase=Phase.DEFINITE_PRE_DISPATCH_FAILURE;
            phase=Phase.DISPATCHED; phase=Phase.VERIFY_ONLY;
            return phase=helper.inspectCanonicalRoute(binding,deadline)?Phase.VERIFIED:Phase.AMBIGUOUS;
        }
    }
    Phase reconcile(SingleOwnerCleanup.ProofIssuer authority) {
        require(authority); synchronized(lock) {
            if (phase==Phase.VERIFIED) return phase;
            if (phase!=Phase.VERIFY_ONLY && phase!=Phase.AMBIGUOUS) return phase;
            return phase=helper.inspectCanonicalRoute(binding,deadline)?Phase.VERIFIED:Phase.AMBIGUOUS;
        }
    }
    Phase phase(SingleOwnerCleanup.ProofIssuer authority) { require(authority); synchronized(lock){return phase;} }
    boolean freshCanonical(SingleOwnerCleanup.ProofIssuer authority){require(authority);synchronized(lock){return phase==Phase.VERIFIED&&!deadline.expired()&&helper.inspectCanonicalRoute(binding,deadline);}}
    ContainmentDeadline deadline() { return deadline; }
    private void require(SingleOwnerCleanup.ProofIssuer authority){if(authority!=issuer)throw new SecurityException("Foreign route authority");}
    private static void id(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid ID");}
    private static void digest(String value){if(value==null||!value.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid digest");}
}
