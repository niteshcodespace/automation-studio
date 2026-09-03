package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/** One-shot gateway acquisition and exact-ID ownership handoff. */
final class D2cGatewayAuthority {
    enum Phase { NOT_ATTEMPTED, DISPATCHED, VERIFIED, AMBIGUOUS, ABSENT }
    record Evidence(DockerResourceFingerprint fingerprint,Map<String,String> labels,String executableDigest,
            DockerControlPlane.DockerDaemonIdentity daemon){Evidence{Objects.requireNonNull(fingerprint);labels=Map.copyOf(labels);Objects.requireNonNull(daemon);}}
    record Absence(boolean absent,DockerControlPlane.DockerDaemonIdentity daemon,long observationRevision){Absence{Objects.requireNonNull(daemon);if(observationRevision<=0)throw new IllegalArgumentException();}}
    interface Adapter {
        DockerControlPlane.DockerTransportOutcome create(SingleOwnerCleanup.ProofIssuer issuer,D2cGatewaySpec spec,ContainmentDeadline deadline);
        List<String> lookup(SingleOwnerCleanup.ProofIssuer issuer,D2cGatewaySpec spec,ContainmentDeadline deadline);
        Evidence inspect(SingleOwnerCleanup.ProofIssuer issuer,String id,ContainmentDeadline deadline);
        DockerControlPlane.DockerTransportOutcome remove(SingleOwnerCleanup.ProofIssuer issuer,String id,ContainmentDeadline deadline);
        Absence absent(SingleOwnerCleanup.ProofIssuer issuer,String id,ContainmentDeadline deadline);
    }
    private final SingleOwnerCleanup.ProofIssuer issuer;private final ContainmentDeadline deadline;private final Adapter adapter;
    private final D2cGatewaySpec spec;private final DockerControlPlane.DockerDaemonIdentity daemon;private final BiConsumer<String,DockerControlPlane.DockerDaemonIdentity> registrar;
    private Phase phase=Phase.NOT_ATTEMPTED;private String id;private final List<String> retained=new ArrayList<>();
    D2cGatewayAuthority(SingleOwnerCleanup.ProofIssuer issuer,ContainmentDeadline deadline,D2cGatewaySpec spec,Adapter adapter,
            DockerControlPlane.DockerDaemonIdentity daemon,BiConsumer<String,DockerControlPlane.DockerDaemonIdentity> registrar){
        this.issuer=Objects.requireNonNull(issuer);this.deadline=Objects.requireNonNull(deadline);this.spec=Objects.requireNonNull(spec);this.adapter=Objects.requireNonNull(adapter);
        this.daemon=Objects.requireNonNull(daemon);this.registrar=Objects.requireNonNull(registrar);}
    synchronized Phase acquire(SingleOwnerCleanup.ProofIssuer authority){require(authority);if(phase!=Phase.NOT_ATTEMPTED)throw new IllegalStateException("Gateway already attempted");
        var result=adapter.create(issuer,spec,deadline);if(result.definitelyNotDispatched())return phase=Phase.AMBIGUOUS;phase=Phase.DISPATCHED;
        if(!daemon.equals(result.daemonIdentity()))return phase=Phase.AMBIGUOUS;
        String candidate=result.response()==null?null:result.response().strip();if(candidate!=null&&candidate.matches("[a-f0-9]{64}"))retained.add(candidate);
        List<String> lookup=adapter.lookup(issuer,spec,deadline);if(lookup==null)return phase=Phase.AMBIGUOUS;for(String recovered:lookup)if(!retained.contains(recovered))retained.add(recovered);
        if(retained.size()!=1)return phase=Phase.AMBIGUOUS;candidate=retained.getFirst();Evidence observed=adapter.inspect(issuer,candidate,deadline);
        if(observed==null||!daemon.equals(observed.daemon())||!observed.fingerprint().equals(spec.resourceSpec().fingerprint(candidate))||!observed.labels().equals(spec.labels())
                ||!spec.executableDigest().equals(observed.executableDigest()))return phase=Phase.AMBIGUOUS;
        id=candidate;registrar.accept(id,daemon);return phase=Phase.VERIFIED;}
    synchronized Phase cleanup(SingleOwnerCleanup.ProofIssuer authority){require(authority);if(phase==Phase.ABSENT)return phase;
        List<String> lookup=adapter.lookup(issuer,spec,deadline);if(lookup==null)return phase=Phase.AMBIGUOUS;for(String recovered:lookup)if(!retained.contains(recovered))retained.add(recovered);boolean allAbsent=!retained.isEmpty();
        for(String candidate:List.copyOf(retained)){Evidence observed=adapter.inspect(issuer,candidate,deadline);if(observed==null){Absence already=adapter.absent(issuer,candidate,deadline);if(already!=null&&already.absent()&&daemon.equals(already.daemon()))continue;allAbsent=false;continue;}
            if(!daemon.equals(observed.daemon())||!observed.labels().equals(spec.labels())||!observed.fingerprint().equals(spec.resourceSpec().fingerprint(candidate))){allAbsent=false;continue;}
            registrar.accept(candidate,daemon);var removed=adapter.remove(issuer,candidate,deadline);if(!removed.successful()||!daemon.equals(removed.daemonIdentity())){allAbsent=false;continue;}
            Absence absence=adapter.absent(issuer,candidate,deadline);if(absence==null||!absence.absent()||!daemon.equals(absence.daemon()))allAbsent=false;}
        return phase=allAbsent?Phase.ABSENT:Phase.AMBIGUOUS;}
    synchronized String immutableId(){return id;} synchronized Phase phase(){return phase;}
    synchronized boolean fresh(SingleOwnerCleanup.ProofIssuer authority){require(authority);if(phase!=Phase.VERIFIED||id==null)return false;Evidence observed=adapter.inspect(issuer,id,deadline);return observed!=null&&daemon.equals(observed.daemon())
            &&observed.labels().equals(spec.labels())&&observed.fingerprint().equals(spec.resourceSpec().fingerprint(id))&&spec.executableDigest().equals(observed.executableDigest());}
    synchronized List<String> retainedIds(){return List.copyOf(retained);}
    synchronized DockerControlPlane.DockerDaemonIdentity daemon(){return daemon;}
    private void require(SingleOwnerCleanup.ProofIssuer authority){if(authority!=issuer)throw new SecurityException("Foreign gateway authority");}
}
