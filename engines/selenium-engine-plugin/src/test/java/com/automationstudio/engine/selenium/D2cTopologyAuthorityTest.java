package com.automationstudio.engine.selenium;

import static com.automationstudio.engine.selenium.D2cTopologyModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayDeque;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class D2cTopologyAuthorityTest {
    private static final String WORKER="a".repeat(64), GATEWAY="b".repeat(64), WNET="c".repeat(64), ENET="d".repeat(64);
    private static final DockerControlPlane.DockerDaemonIdentity DAEMON=
            new DockerControlPlane.DockerDaemonIdentity("npipe://engine","default","engine-a");
    @AfterEach void clear(){D2cTopologyAllocator.clearForTesting();}

    @Test void allocatorUsesFrozenPoolOrderAndRejectsOverlapAndIncompleteInventory(){
        Fixture f=fixture(); var inventory=f.inventory(Set.of(Ipv4Cidr.parse("172.30.0.0/28")));
        var values=f.topology.reserve(f.executionId,inventory);
        assertEquals("172.30.0.16/28",values.get(0).subnet().toString());
        assertEquals("172.30.0.32/28",values.get(1).subnet().toString());
        assertEquals("172.30.0.17",values.get(0).bridgeGateway());
        assertEquals(List.of("172.30.0.18","172.30.0.19","172.30.0.20"),values.get(0).endpointAddresses());
        assertThrows(IllegalStateException.class,()->f.topology.reserve(UUID.randomUUID(),
                new D2cTopologyAllocator.Inventory(DAEMON,2,1,f.deadline,Set.of(),false)));
    }

    @Test void concurrentGenerationsCannotReserveSameCidrs() throws Exception {
        var start=new CountDownLatch(1); var results=new ArrayList<List<Allocation>>();
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{Fixture f=fixture();start.await();return f.topology.reserve(f.executionId,f.inventory(Set.of()));});
            var b=pool.submit(()->{Fixture f=fixture();start.await();return f.topology.reserve(f.executionId,f.inventory(Set.of()));});
            start.countDown();results.add(a.get());results.add(b.get());
        }
        var cidrs=results.stream().flatMap(List::stream).map(Allocation::subnet).toList();
        assertEquals(4,cidrs.stream().distinct().count());
    }

    @Test void gatewayFingerprintIsSingularAndRejectsMutableIdentity(){
        UUID id=UUID.randomUUID(); String token="e".repeat(64), digest="sha256:"+"f".repeat(64);
        var labels=D2cGatewaySpec.expectedLabels(id,1,2,token,token,digest,digest);
        var spec=new D2cGatewaySpec(id,1,2,token,token,digest,digest,labels);
        assertEquals("65532:65532",spec.user()); assertEquals(List.of("cap-drop=ALL","cap-add=NET_ADMIN"),spec.capabilities());
        assertEquals("none",spec.networkMode()); assertEquals("private",spec.ipcMode()); assertEquals("",spec.pidMode());
        assertEquals(ContainmentResourceRole.GATEWAY,spec.resourceSpec().role());
        assertEquals("",spec.resourceSpec().fingerprint(GATEWAY).pidMode());
        assertThrows(IllegalArgumentException.class,()->new D2cGatewaySpec(id,1,2,token,token,"gateway:latest",digest,labels));
    }

    @Test void endpointConnectAndDetachAreOneShotAndAbsenceOnlyResumes(){
        Fixture f=fixture(); FakeEndpoints ops=new FakeEndpoints(); var authority=f.topology.endpoints(DAEMON,ops);
        var key=new D2cEndpointAuthority.Key(EndpointInstance.WORKER_ENDPOINT,WORKER,WNET,1);
        ops.evidence=new D2cEndpointAuthority.Evidence("e".repeat(64),"172.30.0.2","28","02:00:00:00:00:02",DAEMON);
        authority.register(f.topology.issuer(),key,"172.30.0.2");
        assertEquals(D2cEndpointAuthority.Phase.VERIFIED_PRESENT,authority.connectAndVerify(f.topology.issuer(),key));
        assertThrows(IllegalStateException.class,()->authority.connectAndVerify(f.topology.issuer(),key));
        ops.absent=false; assertEquals(D2cEndpointAuthority.Phase.AWAITING_ABSENCE,authority.reconcileDetach(f.topology.issuer(),key));
        assertEquals(1,ops.disconnects.get()); assertEquals(1,ops.connects.get());
        ops.absent=true; assertEquals(D2cEndpointAuthority.Phase.VERIFIED_ABSENT,authority.reconcileDetach(f.topology.issuer(),key));
        assertEquals(1,ops.disconnects.get());
    }

    @Test void possibleConnectWithoutExactEvidenceReconcilesFromFreshMembership(){
        Fixture f=fixture();FakeEndpoints ops=new FakeEndpoints();ops.connectResult=timeout();
        var authority=f.topology.endpoints(DAEMON,ops);var key=new D2cEndpointAuthority.Key(EndpointInstance.GATEWAY_EGRESS_ENDPOINT,GATEWAY,ENET,1);
        authority.register(f.topology.issuer(),key,"172.30.0.18");
        assertEquals(D2cEndpointAuthority.Phase.AMBIGUOUS,authority.connectAndVerify(f.topology.issuer(),key));
        ops.evidence=new D2cEndpointAuthority.Evidence("e".repeat(64),"172.30.0.18","28","02:00:00:00:00:02",DAEMON);
        assertEquals(D2cEndpointAuthority.Phase.AWAITING_ABSENCE,authority.reconcileDetach(f.topology.issuer(),key));
        ops.absent=true;assertEquals(D2cEndpointAuthority.Phase.VERIFIED_ABSENT,authority.reconcileDetach(f.topology.issuer(),key));assertEquals(1,ops.disconnects.get());
    }

    @Test void routeMutationDispatchesOnceThenReconcilesInspectOnly(){
        Fixture f=fixture();FakeHelper helper=new FakeHelper();helper.firstCanonical=false;
        var route=f.topology.route(helper,binding());
        assertEquals(D2cRouteAuthority.Phase.AMBIGUOUS,route.establish(f.topology.issuer()));
        helper.canonical=true;assertEquals(D2cRouteAuthority.Phase.VERIFIED,route.reconcile(f.topology.issuer()));
        assertEquals(1,helper.mutations.get());assertEquals(2,helper.inspections.get());assertSame(f.deadline,route.deadline());
    }

    @Test void readinessIsNonforgeableDeadlineBoundAndNeverOpensBrowserEligibility(){
        Fixture f=fixture();f.topology.reserve(f.executionId,f.inventory(Set.of()));
        var expected=new D2cReadinessAdapter.Expected(1,GATEWAY,WNET,ENET,DAEMON,List.of("eth0","eth1"),List.of("default-via-egress"));
        var adapter=new D2cReadinessAdapter(expected,(e,d)->new D2cReadinessAdapter.Observation(true,true,e.interfaces(),e.routes(),false,DAEMON));
        assertNotNull(f.topology.verifyReadiness(adapter));assertFalse(f.topology.browserExecutionEligible());
        f.topology.readinessLost();assertTrue(f.topology.compromised());assertFalse(f.topology.browserExecutionEligible());
    }

    @Test void dependencyDagBlocksNetworksUntilExactEndpointsAreAbsent(){
        var dag=D2cTopologyAuthority.dependencyDag();
        assertEquals(Set.of(D2cTopologyAuthority.Node.WORKER),
                dag.prerequisites(D2cTopologyAuthority.Node.WORKER_ENDPOINT));
        assertEquals(Set.of(D2cTopologyAuthority.Node.WORKER_ENDPOINT,
                D2cTopologyAuthority.Node.GATEWAY_WORKER_ENDPOINT),
                dag.prerequisites(D2cTopologyAuthority.Node.WORKER_NETWORK));
    }

    @Test void reservationReleaseIsDerivedFromBoundAuthoritativeNetworkAbsence(){
        Fixture f=fixture();var allocations=f.topology.reserve(f.executionId,f.inventory(Set.of()));var results=new ArrayDeque<DockerControlPlane.DockerTransportOutcome>();
        D2cDockerTopologyAdapter.Runner runner=(argv,d)->results.remove();var adapter=D2cDockerTopologyAdapter.trusted(f.topology.issuer(),runner,DAEMON);
        var attempts=new ArrayList<D2cDockerTopologyAdapter.NetworkAttempt>();for(int i=0;i<2;i++){var attempt=adapter.prepareNetwork(f.topology.issuer(),allocations.get(i),i==0,f.deadline);attempts.add(attempt);String id=(i==0?WNET:ENET);results.add(ok(id));results.add(ok(id));results.add(ok(createdNetwork(id,attempt)));assertEquals(D2cDockerTopologyAdapter.NetworkPhase.VERIFIED,adapter.acquireNetwork(f.topology.issuer(),attempt));}
        f.topology.bindNetworkAuthority(adapter);results.add(ok(WNET));results.add(ok(createdNetwork(WNET,attempts.get(0))));results.add(ok(""));results.add(absent());results.add(ok(ENET));results.add(ok(createdNetwork(ENET,attempts.get(1))));results.add(ok(""));results.add(absent());
        f.topology.release();assertFalse(f.topology.compromised());
    }

    @Test void ambiguousNetworkCandidatesAreRetainedAndIndependentlyClosed(){
        Fixture f=fixture();var allocation=f.topology.reserve(f.executionId,f.inventory(Set.of())).getFirst();var results=new ArrayDeque<DockerControlPlane.DockerTransportOutcome>();
        D2cDockerTopologyAdapter.Runner runner=(argv,d)->results.remove();var adapter=D2cDockerTopologyAdapter.trusted(f.topology.issuer(),runner,DAEMON);
        var attempt=adapter.prepareNetwork(f.topology.issuer(),allocation,true,f.deadline);results.add(ok(WNET));results.add(ok(WNET+"\n"+ENET));
        results.add(ok(createdNetwork(WNET,attempt)));results.add(ok(createdNetwork(ENET,attempt)));
        assertEquals(D2cDockerTopologyAdapter.NetworkPhase.AMBIGUOUS,adapter.acquireNetwork(f.topology.issuer(),attempt));
        assertEquals(List.of(WNET,ENET),adapter.networkLedger(f.topology.issuer()).getFirst().retainedIds().stream().sorted().toList());
        results.add(ok(WNET+"\n"+ENET));for(String id:List.of(WNET,ENET)){results.add(ok(createdNetwork(id,attempt)));results.add(ok());results.add(absent());}
        assertEquals(D2cDockerTopologyAdapter.NetworkPhase.ABSENT,adapter.reconcileNetworkAbsence(f.topology.issuer(),allocation,f.deadline).phase());
    }

    @Test void foreignIssuerCannotOperateEndpointOrRouteAuthority(){
        Fixture a=fixture(),b=fixture();FakeEndpoints ops=new FakeEndpoints();var endpoints=a.topology.endpoints(DAEMON,ops);
        var key=new D2cEndpointAuthority.Key(EndpointInstance.WORKER_ENDPOINT,WORKER,WNET,1);
        assertThrows(SecurityException.class,()->endpoints.register(b.topology.issuer(),key,"172.30.0.2"));
        var route=a.topology.route(new FakeHelper(),binding());
        assertThrows(SecurityException.class,()->route.establish(b.topology.issuer()));
    }

    @Test void typedD2cTerminalEvidenceCannotPublishUnresolvedInstanceSafe(){
        var unresolved=new D2cTerminalEvidence.Instance(D2cTerminalEvidence.Kind.ENDPOINT,WORKER+"@"+WNET,
                D2cTerminalEvidence.State.UNRESOLVED,DAEMON,1);assertFalse(new D2cTerminalEvidence(List.of(unresolved)).safe());
        var absent=new D2cTerminalEvidence.Instance(D2cTerminalEvidence.Kind.ENDPOINT,WORKER+"@"+WNET,
                D2cTerminalEvidence.State.VERIFIED_ABSENT,DAEMON,2);assertTrue(new D2cTerminalEvidence(List.of(absent)).safe());
    }

    private static D2cRouteAuthority.Binding binding(){return new D2cRouteAuthority.Binding(WORKER,100,200,300,400,WNET,
            "172.30.0.2","172.30.0.3","eth0",1,"sha256:"+"9".repeat(64),DAEMON);}
    private static Fixture fixture(){var deadline=ContainmentDeadline.after(Duration.ofSeconds(30));var cleanup=new SingleOwnerCleanup(deadline);
        var owner=cleanup.claim();return new Fixture(UUID.randomUUID(),deadline,cleanup.d2cTopology(owner,1));}
    private record Fixture(UUID executionId,ContainmentDeadline deadline,D2cTopologyAuthority topology){
        D2cTopologyAllocator.Inventory inventory(Set<Ipv4Cidr> values){return new D2cTopologyAllocator.Inventory(DAEMON,1,1,deadline,values,true);}}
    private static DockerControlPlane.DockerTransportOutcome ok(){return ok("");}
    private static DockerControlPlane.DockerTransportOutcome ok(String response){return new DockerControlPlane.DockerTransportOutcome(
            DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,DockerControlPlane.Completion.COMPLETED,0,response,true,1,
            DockerControlPlane.Semantic.NONE,DAEMON);}
    private static DockerControlPlane.DockerTransportOutcome absent(){return new DockerControlPlane.DockerTransportOutcome(
            DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,DockerControlPlane.Completion.COMPLETED,1,"not found",true,2,
            DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND,DAEMON);}
    private static String createdNetwork(String id,D2cDockerTopologyAdapter.NetworkAttempt attempt){String labels=attempt.labels().entrySet().stream().map(e->"\""+e.getKey()+"\":\""+e.getValue()+"\"").sorted().collect(java.util.stream.Collectors.joining(","));return "[{\"Id\":\""+id+"\",\"Driver\":\"bridge\",\"Scope\":\"local\",\"Internal\":"+attempt.internal()+",\"Attachable\":false,\"Ingress\":false,\"EnableIPv6\":false,\"Labels\":{"+labels+"},\"IPAM\":{\"Driver\":\"default\",\"Config\":[{\"Subnet\":\""+attempt.allocation().subnet()+"\",\"Gateway\":\""+attempt.allocation().bridgeGateway()+"\"}]}}]";}
    private static DockerControlPlane.DockerTransportOutcome timeout(){return new DockerControlPlane.DockerTransportOutcome(
            DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,DockerControlPlane.Completion.TIMED_OUT,null,null,false,1,
            DockerControlPlane.Semantic.NONE,DAEMON);}
    private static final class FakeEndpoints implements D2cEndpointAuthority.Operations{
        final AtomicInteger connects=new AtomicInteger(),disconnects=new AtomicInteger();D2cEndpointAuthority.Evidence evidence;
        DockerControlPlane.DockerTransportOutcome connectResult=ok();boolean absent;
        public DockerControlPlane.DockerTransportOutcome connect(D2cEndpointAuthority.Key k,String ip,ContainmentDeadline d){connects.incrementAndGet();return connectResult;}
        public D2cEndpointAuthority.Membership inspectMembership(D2cEndpointAuthority.Key k,ContainmentDeadline d){return new D2cEndpointAuthority.Membership(
                absent?D2cEndpointAuthority.MembershipState.ABSENT:evidence==null?D2cEndpointAuthority.MembershipState.UNKNOWN:D2cEndpointAuthority.MembershipState.PRESENT,
                absent?null:evidence,DAEMON,1);}
        public DockerControlPlane.DockerTransportOutcome disconnect(D2cEndpointAuthority.Key k,ContainmentDeadline d){disconnects.incrementAndGet();return ok();}
        }
    private static final class FakeHelper implements D2cRouteAuthority.Helper{
        final AtomicInteger mutations=new AtomicInteger(),inspections=new AtomicInteger();boolean firstCanonical=true,canonical=true;
        public D2cRouteAuthority.Result replaceDefaultRoute(D2cRouteAuthority.Binding b,ContainmentDeadline d){mutations.incrementAndGet();canonical=firstCanonical;return new D2cRouteAuthority.Result(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,firstCanonical);}
        public boolean inspectCanonicalRoute(D2cRouteAuthority.Binding b,ContainmentDeadline d){inspections.incrementAndGet();return canonical;}}
}
