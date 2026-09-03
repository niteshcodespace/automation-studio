package com.automationstudio.engine.selenium;

import static com.automationstudio.engine.selenium.D2cTopologyModel.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Cleanup-owned orchestration boundary. D2c deliberately never grants browser eligibility. */
final class D2cTopologyAuthority {
    enum Node { WORKER, WORKER_ENDPOINT, WORKER_NETWORK, GATEWAY,
        GATEWAY_WORKER_ENDPOINT, GATEWAY_EGRESS_ENDPOINT, EGRESS_NETWORK }
    record Readiness(long generation, String gatewayId, String workerNetworkId, String egressNetworkId,
            DockerControlPlane.DockerDaemonIdentity daemon, ContainmentDeadline deadline,
            boolean forwarding, boolean exactInterfaces, boolean exactRoutes, boolean ipv6Absent,
            boolean dnsExecutionEligible, boolean browserExecutionEligible) {
        Readiness {
            id(gatewayId); id(workerNetworkId); id(egressNetworkId); Objects.requireNonNull(daemon); Objects.requireNonNull(deadline);
            if (generation<=0 || dnsExecutionEligible || browserExecutionEligible) throw new IllegalArgumentException("D2c eligibility must remain closed");
        }
        boolean authoritative() { return forwarding && exactInterfaces && exactRoutes && ipv6Absent
                && !dnsExecutionEligible && !browserExecutionEligible && !deadline.expired(); }
    }
    private final Object lock = new Object(); private final SingleOwnerCleanup.ProofIssuer issuer;
    private final ContainmentDeadline deadline; private final long generation;
    private final Runnable compromise;
    private final BiConsumer<String,DockerControlPlane.DockerDaemonIdentity> gatewayRegistrar;
    private final D2cTopologyAllocator allocator = new D2cTopologyAllocator();
    private boolean compromised; private boolean active; private Readiness readiness; private DockerControlPlane.DockerDaemonIdentity daemon;
    private List<Allocation> allocations=List.of();private D2cEndpointAuthority endpointAuthority;private D2cGatewayAuthority gatewayAuthority;
    private D2cRouteAuthority routeAuthority;
    private D2cDockerTopologyAdapter networkAuthority;

    D2cTopologyAuthority(SingleOwnerCleanup.ProofIssuer issuer, ContainmentDeadline deadline, long generation,Runnable compromise,
            BiConsumer<String,DockerControlPlane.DockerDaemonIdentity> gatewayRegistrar) {
        this.issuer=Objects.requireNonNull(issuer); this.deadline=Objects.requireNonNull(deadline);
        this.compromise=Objects.requireNonNull(compromise);
        this.gatewayRegistrar=Objects.requireNonNull(gatewayRegistrar);
        if(generation<=0)throw new IllegalArgumentException("Invalid generation"); this.generation=generation;
    }
    List<Allocation> reserve(UUID executionId, D2cTopologyAllocator.Inventory inventory) {
        synchronized(lock){ if(compromised)throw new IllegalStateException("Topology compromised");
            if(daemon!=null&&!daemon.equals(inventory.daemon()))throw new IllegalStateException("Docker continuity changed");
            daemon=inventory.daemon(); var result=allocator.reserve(executionId,generation,inventory);allocations=result;active=true;return result; }
    }
    void bindNetworkAuthority(D2cDockerTopologyAdapter value){synchronized(lock){if(networkAuthority!=null)throw new IllegalStateException("Network authority already bound");networkAuthority=Objects.requireNonNull(value);}}
    void release(){reconcileForPublication();}
    D2cEndpointAuthority endpoints(DockerControlPlane.DockerDaemonIdentity daemon, D2cEndpointAuthority.Operations operations) {
        continuity(daemon);synchronized(lock){if(endpointAuthority!=null)throw new IllegalStateException("Endpoint authority already exists");
        return endpointAuthority=new D2cEndpointAuthority(lock,issuer,deadline,daemon,operations);}
    }
    D2cGatewayAuthority gateway(DockerControlPlane.DockerDaemonIdentity expectedDaemon,D2cGatewaySpec spec,D2cGatewayAuthority.Adapter adapter){continuity(expectedDaemon);synchronized(lock){if(gatewayAuthority!=null)throw new IllegalStateException("Gateway authority already exists");return gatewayAuthority=new D2cGatewayAuthority(issuer,deadline,spec,adapter,expectedDaemon,gatewayRegistrar);}}
    D2cRouteAuthority route(D2cRouteAuthority.Helper helper, D2cRouteAuthority.Binding binding) {
        continuity(binding.daemon());
        synchronized(lock){if(routeAuthority!=null)throw new IllegalStateException("Route authority already exists");return routeAuthority=new D2cRouteAuthority(lock,issuer,deadline,helper,binding);}
    }
    Readiness verifyReadiness(D2cReadinessAdapter inspector) {
        Readiness result;boolean notify=false;synchronized(lock){ Readiness observed=Objects.requireNonNull(inspector).inspect(deadline);
            if(observed.generation()!=generation || observed.deadline()!=deadline || daemon==null
                    || !daemon.equals(observed.daemon()) || !observed.authoritative()) { notify=markCompromised();result=null; }
            else {readiness=observed;result=readiness;} }if(notify)compromise.run();return result;
    }
    void readinessLost() { boolean notify;synchronized(lock){ readiness=null;notify=markCompromised(); }if(notify)compromise.run(); }
    boolean browserExecutionEligible(){return false;}
    boolean compromised(){synchronized(lock){return compromised;}}
    void requireClosedForPublication(){reconcileForPublication();boolean notify;synchronized(lock){notify=(active||readiness!=null||compromised)&&markCompromised();}if(notify)compromise.run();}
    ContainmentDeadline deadline(){return deadline;}
    SingleOwnerCleanup.ProofIssuer issuer(){return issuer;}
    private void continuity(DockerControlPlane.DockerDaemonIdentity value){synchronized(lock){
        if(daemon==null)daemon=Objects.requireNonNull(value);else if(!daemon.equals(value))throw new IllegalStateException("Docker continuity changed");}}
    private boolean markCompromised(){boolean first=!compromised;compromised=true;return first;}
    private void reconcileForPublication(){boolean resolved=true;
        D2cEndpointAuthority endpoints;D2cGatewayAuthority gateway;D2cDockerTopologyAdapter networks;List<Allocation> reserved;
        synchronized(lock){endpoints=endpointAuthority;gateway=gatewayAuthority;networks=networkAuthority;reserved=allocations;readiness=null;}
        var dependency=new java.util.EnumMap<Node,DependencyEvidence>(Node.class);dependency.put(Node.WORKER,DependencyEvidence.SATISFIED);dependency.put(Node.GATEWAY,DependencyEvidence.SATISFIED);
        if(endpoints==null){dependency.put(Node.WORKER_ENDPOINT,DependencyEvidence.SATISFIED);dependency.put(Node.GATEWAY_WORKER_ENDPOINT,DependencyEvidence.SATISFIED);dependency.put(Node.GATEWAY_EGRESS_ENDPOINT,DependencyEvidence.SATISFIED);}
        if(endpoints!=null)for(var snapshot:endpoints.ledger(issuer)){boolean absent=false;try{absent=endpoints.reconcileDetach(issuer,snapshot.key())==D2cEndpointAuthority.Phase.VERIFIED_ABSENT;}catch(RuntimeException failure){resolved=false;}
            dependency.put(switch(snapshot.key().instance()){case WORKER_ENDPOINT->Node.WORKER_ENDPOINT;case GATEWAY_WORKER_ENDPOINT->Node.GATEWAY_WORKER_ENDPOINT;case GATEWAY_EGRESS_ENDPOINT->Node.GATEWAY_EGRESS_ENDPOINT;},absent?DependencyEvidence.SATISFIED:DependencyEvidence.DISPROVED);if(!absent)resolved=false;}
        if(gateway!=null)try{if(gateway.cleanup(issuer)!=D2cGatewayAuthority.Phase.ABSENT)resolved=false;}catch(RuntimeException failure){resolved=false;}
        var dag=dependencyDag();if(!reserved.isEmpty()){if(networks==null)resolved=false;else for(Allocation value:reserved){Node node=value.key().network()==NetworkInstance.WORKER_NETWORK?Node.WORKER_NETWORK:Node.EGRESS_NETWORK;
            if(dag.eligibility(node,new DependencyEvidenceSnapshot<>(dependency.size(),dependency)).status()!=DependencyEvidence.SATISFIED){resolved=false;continue;}
            try{var disposition=networks.reconcileNetworkAbsence(issuer,value,deadline);if(disposition==null||disposition.phase()!=D2cDockerTopologyAdapter.NetworkPhase.ABSENT)resolved=false;}catch(RuntimeException failure){resolved=false;}}}
        boolean notify=false;synchronized(lock){if(resolved){for(Allocation value:reserved)allocator.release(value.key(),true,true);allocations=List.of();active=false;}else notify=markCompromised();}if(notify)compromise.run();}
    D2cTerminalEvidence terminalEvidence(){synchronized(lock){var result=new java.util.ArrayList<D2cTerminalEvidence.Instance>();
        if(networkAuthority!=null)for(var value:networkAuthority.networkLedger(issuer)){if(value.retainedIds().isEmpty())result.add(new D2cTerminalEvidence.Instance(D2cTerminalEvidence.Kind.NETWORK,
                value.allocation().key().toString(),D2cTerminalEvidence.State.UNRESOLVED,value.daemon(),value.observationRevision()));else for(String id:value.retainedIds())result.add(new D2cTerminalEvidence.Instance(D2cTerminalEvidence.Kind.NETWORK,
                id,value.phase()==D2cDockerTopologyAdapter.NetworkPhase.ABSENT?D2cTerminalEvidence.State.VERIFIED_ABSENT:D2cTerminalEvidence.State.UNRESOLVED,value.daemon(),value.observationRevision()));}
        if(endpointAuthority!=null)for(var value:endpointAuthority.ledger(issuer))result.add(new D2cTerminalEvidence.Instance(D2cTerminalEvidence.Kind.ENDPOINT,
                value.key().containerId()+"@"+value.key().networkId(),value.phase()==D2cEndpointAuthority.Phase.VERIFIED_ABSENT?D2cTerminalEvidence.State.VERIFIED_ABSENT:D2cTerminalEvidence.State.UNRESOLVED,endpointAuthority.daemon(issuer),value.sequence()));
        if(gatewayAuthority!=null)for(String id:gatewayAuthority.retainedIds())result.add(new D2cTerminalEvidence.Instance(D2cTerminalEvidence.Kind.GATEWAY,id,
                gatewayAuthority.phase()==D2cGatewayAuthority.Phase.ABSENT?D2cTerminalEvidence.State.VERIFIED_ABSENT:D2cTerminalEvidence.State.UNRESOLVED,gatewayAuthority.daemon(),1));return new D2cTerminalEvidence(result);}}
    static ContainmentDependencyDag<Node> dependencyDag() {
        return new ContainmentDependencyDag<>(Map.of(
                Node.WORKER, Set.of(),
                Node.WORKER_ENDPOINT, Set.of(Node.WORKER),
                Node.WORKER_NETWORK, Set.of(Node.WORKER_ENDPOINT, Node.GATEWAY_WORKER_ENDPOINT),
                Node.GATEWAY, Set.of(),
                Node.GATEWAY_WORKER_ENDPOINT, Set.of(Node.GATEWAY),
                Node.GATEWAY_EGRESS_ENDPOINT, Set.of(Node.GATEWAY),
                Node.EGRESS_NETWORK, Set.of(Node.GATEWAY_EGRESS_ENDPOINT)));
    }
    private static void id(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid ID");}
}
