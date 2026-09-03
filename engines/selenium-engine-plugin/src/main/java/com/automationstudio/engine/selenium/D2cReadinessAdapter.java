package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.Objects;

/** Closed readiness inspector; only complete canonical namespace facts can issue readiness. */
final class D2cReadinessAdapter {
    record Expected(long generation,String gatewayId,String workerNetworkId,String egressNetworkId,
            DockerControlPlane.DockerDaemonIdentity daemon,List<String> interfaces,List<String> routes) {
        Expected{Objects.requireNonNull(daemon);interfaces=List.copyOf(interfaces);routes=List.copyOf(routes);}
    }
    record Observation(boolean complete,boolean forwarding,List<String> interfaces,List<String> routes,
            boolean ipv6Present,DockerControlPlane.DockerDaemonIdentity daemon) {
        Observation{interfaces=List.copyOf(interfaces);routes=List.copyOf(routes);Objects.requireNonNull(daemon);}
    }
    interface Inspector { Observation inspect(Expected expected,ContainmentDeadline deadline); }
    interface ProductionInspector { Observation inspect(Expected expected,ContainmentDeadline deadline); }
    private final Expected expected;private final Inspector inspector;
    D2cReadinessAdapter(Expected expected,Inspector inspector){this.expected=Objects.requireNonNull(expected);this.inspector=Objects.requireNonNull(inspector);}
    static D2cReadinessAdapter production(Expected expected,SingleOwnerCleanup.ProofIssuer issuer,
            D2cGatewayAuthority gateway,D2cEndpointAuthority endpoints,D2cRouteAuthority route,ProductionInspector production){
        Objects.requireNonNull(issuer);Objects.requireNonNull(gateway);Objects.requireNonNull(endpoints);Objects.requireNonNull(route);Objects.requireNonNull(production);
        return new D2cReadinessAdapter(expected,(e,d)->{Observation observed=production.inspect(e,d);if(observed==null||d.expired()||!gateway.fresh(issuer)
                    ||!endpoints.freshPresent(issuer)||!route.freshCanonical(issuer))return new Observation(false,false,List.of(),List.of(),true,e.daemon());
            return observed;});}
    D2cTopologyAuthority.Readiness inspect(ContainmentDeadline deadline){Observation value=inspector.inspect(expected,deadline);
        boolean exact=value.complete()&&value.forwarding()&&!value.ipv6Present()&&value.daemon().equals(expected.daemon())
                &&value.interfaces().equals(expected.interfaces())&&value.routes().equals(expected.routes());
        return new D2cTopologyAuthority.Readiness(expected.generation(),expected.gatewayId(),expected.workerNetworkId(),
                expected.egressNetworkId(),expected.daemon(),deadline,value.forwarding(),exact,exact,!value.ipv6Present(),false,false);}
}
