package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.Objects;

/** Immutable per-instance D2c evidence carried into D2a1 terminal publication. */
record D2cTerminalEvidence(List<Instance> instances) {
    enum Kind { NETWORK, ENDPOINT, GATEWAY }
    enum State { VERIFIED_ABSENT, UNRESOLVED }
    record Instance(Kind kind,String identity,State state,
            DockerControlPlane.DockerDaemonIdentity daemon,long observationRevision) {
        Instance { Objects.requireNonNull(kind);Objects.requireNonNull(identity);Objects.requireNonNull(state);Objects.requireNonNull(daemon);if(observationRevision<=0)throw new IllegalArgumentException(); }
    }
    D2cTerminalEvidence { instances=List.copyOf(instances); }
    static D2cTerminalEvidence empty(){return new D2cTerminalEvidence(List.of());}
    boolean safe(){return instances.stream().allMatch(v->v.state()==State.VERIFIED_ABSENT);}
}
