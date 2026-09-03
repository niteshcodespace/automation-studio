package com.automationstudio.engine.selenium;

import static com.automationstudio.engine.selenium.D2cTopologyModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class D2cProductionBoundaryTest {
    private static final String A="a".repeat(64),B="b".repeat(64),C="c".repeat(64),TOKEN="d".repeat(64);
    private static final DockerControlPlane.DockerDaemonIdentity DAEMON=new DockerControlPlane.DockerDaemonIdentity("npipe://engine","default","engine-a");
    @AfterEach void clear(){D2cTopologyAllocator.clearForTesting();}

    @Test void closedInventoryInspectsEveryExactIdAndBuildsFrozenNetworkArgv(){
        Harness h=harness();h.runner.results.add(ok(A+"\n"+B));h.runner.results.add(ok(network(A,"10.0.0.0/24")));
        h.runner.results.add(ok(network(B,"10.1.0.0/24")));
        var inventory=h.adapter.inventory(h.topology.issuer(),1,h.deadline);assertTrue(inventory.complete());assertEquals(2,inventory.subnets().size());
        assertEquals(List.of("docker","network","inspect",A),h.runner.argv.get(1));
        var allocations=h.topology.reserve(h.id,inventory);var attempt=h.adapter.prepareNetwork(h.topology.issuer(),allocations.get(0),true,h.deadline);
        h.runner.results.add(ok(C));h.runner.results.add(ok(C));h.runner.results.add(ok(createdNetwork(C,attempt)));
        assertEquals(D2cDockerTopologyAdapter.NetworkPhase.VERIFIED,h.adapter.acquireNetwork(h.topology.issuer(),attempt));
        assertTrue(h.runner.argv.get(3).contains("--internal=true"));assertTrue(h.runner.argv.get(3).contains("--subnet"));
        assertTrue(h.runner.argv.get(3).stream().anyMatch(v->v.startsWith("com.automationstudio.attempt-nonce=")));
    }

    @Test void incompleteOrMalformedInventoryCannotReserve(){
        Harness h=harness();h.runner.results.add(ok("short-id"));var inventory=h.adapter.inventory(h.topology.issuer(),1,h.deadline);
        assertFalse(inventory.complete());assertThrows(IllegalStateException.class,()->h.topology.reserve(h.id,inventory));
    }

    @Test void endpointAdapterUsesOnlyExactIdsAndDoesNotManufactureAbsence(){
        Harness h=harness();var key=new D2cEndpointAuthority.Key(EndpointInstance.WORKER_ENDPOINT,A,B,1);
        h.runner.results.add(ok(container(A,"worker",B,C)));
        assertEquals(D2cEndpointAuthority.MembershipState.PRESENT,h.adapter.inspectMembership(key,h.deadline).state());h.runner.results.add(protocol());assertNull(h.adapter.inspectMembership(key,h.deadline));
        assertEquals(A,h.runner.argv.get(0).getLast());
    }

    @Test void strictInventoryRejectsWrongIdAndEndpointAbsenceAllowsLiveContainer(){
        Harness h=harness();h.runner.results.add(ok(A));h.runner.results.add(ok(network(B,"10.0.0.0/24")));
        assertFalse(h.adapter.inventory(h.topology.issuer(),1,h.deadline).complete());
        var key=new D2cEndpointAuthority.Key(EndpointInstance.WORKER_ENDPOINT,A,B,1);
        h.runner.results.add(ok("[{\"Id\":\""+A+"\",\"NetworkSettings\":{\"Networks\":{}}}]"));
        assertEquals(D2cEndpointAuthority.MembershipState.ABSENT,h.adapter.inspectMembership(key,h.deadline).state());
        h.runner.results.add(ok("[{\"Id\":\""+A+"\",\"NetworkSettings\":{}}]"));
        assertEquals(D2cEndpointAuthority.MembershipState.UNKNOWN,h.adapter.inspectMembership(key,h.deadline).state());
    }

    @Test void helperLauncherFreezesPathEnvironmentDigestAndInspectOnlyProtocol(){
        var calls=new ArrayList<String>();String digest="sha256:"+"9".repeat(64);
        var launcher=new D2cNamespaceHelperLauncher(digest,(argv,env,request,deadline)->{calls.add(String.join("|",argv)+"#"+String.join("|",env)+"#"+request);
            return new D2cNamespaceHelperLauncher.Reply(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,true,"ROUTE_CANONICAL",true);});
        var binding=new D2cRouteAuthority.Binding(A,10,11,12,13,B,"172.30.0.2","172.30.0.3","eth0",1,digest,DAEMON);
        assertTrue(launcher.replaceDefaultRoute(binding,ContainmentDeadline.after(Duration.ofSeconds(1))).canonicalRoute());
        assertTrue(calls.get(0).startsWith(D2cNamespaceHelperLauncher.PATH+"|--digest|"+digest));assertTrue(calls.get(0).contains("LANG=C"));
    }

    @Test void gatewayAcquisitionRequiresExactFingerprintAndCleanupAbsence(){
        UUID id=UUID.randomUUID();String digest="sha256:"+"e".repeat(64);var spec=new D2cGatewaySpec(id,1,1,TOKEN,TOKEN,digest,digest,
                D2cGatewaySpec.expectedLabels(id,1,1,TOKEN,TOKEN,digest,digest));var cleanup=new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(5)));
        var owner=cleanup.claim();var topology=cleanup.d2cTopology(owner,1);var fake=new GatewayFake(new D2cGatewayAuthority.Evidence(spec.resourceSpec().fingerprint(A),spec.labels(),digest,DAEMON));
        var authority=topology.gateway(DAEMON,spec,fake);
        assertEquals(D2cGatewayAuthority.Phase.VERIFIED,authority.acquire(topology.issuer()));
        assertEquals(D2cGatewayAuthority.Phase.ABSENT,authority.cleanup(topology.issuer()));assertEquals(1,fake.removes.get());
    }

    @Test void gatewayCliSurfaceBuildsOnlyFrozenCreateAndExactCleanupArgv(){
        Harness h=harness();UUID id=UUID.randomUUID();String digest="sha256:"+"e".repeat(64);var spec=new D2cGatewaySpec(id,1,1,TOKEN,TOKEN,digest,digest,
                D2cGatewaySpec.expectedLabels(id,1,1,TOKEN,TOKEN,digest,digest));var adapter=new D2cCliGatewayAdapter(h.topology.issuer(),h.runner);
        h.runner.results.add(ok(A));adapter.create(h.topology.issuer(),spec,h.deadline);List<String> argv=h.runner.argv.getFirst();
        assertTrue(argv.containsAll(List.of("--network","none","--cap-drop","ALL","--cap-add","NET_ADMIN","--read-only")));
        assertFalse(argv.contains("--privileged"));assertFalse(argv.stream().anyMatch(v->v.contains("docker.sock")));
        h.runner.results.add(ok(""));adapter.remove(h.topology.issuer(),A,h.deadline);assertEquals(List.of("docker","rm","--force",A),h.runner.argv.get(1));
    }

    @Test void gatewayRejectsForeignIssuerAndObservedDaemonChange(){
        UUID id=UUID.randomUUID();String digest="sha256:"+"e".repeat(64);var spec=new D2cGatewaySpec(id,1,1,TOKEN,TOKEN,digest,digest,
                D2cGatewaySpec.expectedLabels(id,1,1,TOKEN,TOKEN,digest,digest));var cleanup=new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(5)));
        var topology=cleanup.d2cTopology(cleanup.claim(),1);var changed=new DockerControlPlane.DockerDaemonIdentity("npipe://other","other","engine-b");
        var fake=new GatewayFake(new D2cGatewayAuthority.Evidence(spec.resourceSpec().fingerprint(A),spec.labels(),digest,changed));
        var authority=topology.gateway(DAEMON,spec,fake);assertEquals(D2cGatewayAuthority.Phase.AMBIGUOUS,authority.acquire(topology.issuer()));
        var foreignCleanup=new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(5)));var foreign=foreignCleanup.d2cTopology(foreignCleanup.claim(),1);
        var adapter=new D2cCliGatewayAdapter(topology.issuer(),(argv,d)->ok(""));assertThrows(SecurityException.class,()->adapter.lookup(foreign.issuer(),spec,topology.deadline()));
    }

    @Test void deterministicNativeAndGatewayQualificationBoundariesArePresent() throws Exception {
        String helper=java.nio.file.Files.readString(java.nio.file.Path.of("src/helper/as_netns_helper.c"));
        assertTrue(helper.contains("RTM_GETRULE"));assertTrue(helper.contains("RTM_GETADDR"));assertTrue(helper.contains("RTA_MULTIPATH"));assertTrue(helper.contains("SYS_capget"));
        assertTrue(helper.contains("NLMSG_DONE"));assertTrue(helper.contains("recvmsg"));assertTrue(helper.contains("MSG_TRUNC"));
        String dockerfile=java.nio.file.Files.readString(java.nio.file.Path.of("src/gateway/Dockerfile.gateway"));
        assertTrue(dockerfile.contains("alpine@sha256:"));assertTrue(dockerfile.contains("AS_GATEWAY_EXECUTABLE_DIGEST")||dockerfile.contains("GATEWAY_EXECUTABLE_DIGEST"));
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("src/gateway/qualify-artifact.sh")));assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("src/helper/qualify-helper.sh")));
        String qualification=java.nio.file.Files.readString(java.nio.file.Path.of("src/gateway/qualify-artifact.sh"));
        assertTrue(qualification.contains("GATEWAY_OCI_MANIFEST_DIGEST"));assertTrue(qualification.contains("GATEWAY_OCI_CONFIG_DIGEST"));
        String launcher=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/automationstudio/engine/selenium/D2cNamespaceHelperLauncher.java"));
        assertTrue(launcher.contains("/proc/self/fd"));assertTrue(launcher.contains("NOFOLLOW_LINKS"));
    }

    @Test void activeD2cTopologyCompromisesD2a1TerminalPublication(){
        var deadline=ContainmentDeadline.after(Duration.ofSeconds(5));var cleanup=new SingleOwnerCleanup(deadline);var owner=cleanup.claim();
        var topology=cleanup.d2cTopology(owner,1);topology.reserve(UUID.randomUUID(),new D2cTopologyAllocator.Inventory(DAEMON,1,1,deadline,Set.of(),true));
        assertFalse(owner.execute(()->new SingleOwnerCleanup.TerminalOutcome(ExecutionOutcome.SUCCEEDED,ContainmentCode.ABSENT,
                AttachmentState.CLOSED,DependencyEvidence.SATISFIED)).safe());
    }

    private static Harness harness(){var deadline=ContainmentDeadline.after(Duration.ofSeconds(5));var cleanup=new SingleOwnerCleanup(deadline);var owner=cleanup.claim();
        var topology=cleanup.d2cTopology(owner,1);var runner=new QueueRunner();return new Harness(UUID.randomUUID(),deadline,topology,runner,
                D2cDockerTopologyAdapter.trusted(topology.issuer(),runner,DAEMON));}
    private record Harness(UUID id,ContainmentDeadline deadline,D2cTopologyAuthority topology,QueueRunner runner,D2cDockerTopologyAdapter adapter){}
    private static DockerControlPlane.DockerTransportOutcome ok(String response){return new DockerControlPlane.DockerTransportOutcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
            DockerControlPlane.Completion.COMPLETED,0,response,true,1,DockerControlPlane.Semantic.NONE,DAEMON);}
    private static DockerControlPlane.DockerTransportOutcome protocol(){return new DockerControlPlane.DockerTransportOutcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
            DockerControlPlane.Completion.PROTOCOL_FAILED,1,null,false,1,DockerControlPlane.Semantic.NONE,DAEMON);}
    private static String network(String id,String subnet){return "[{\"Id\":\""+id+"\",\"Driver\":\"bridge\",\"Scope\":\"local\",\"Internal\":false,\"Attachable\":false,\"Ingress\":false,\"EnableIPv6\":false,\"Options\":{},\"Labels\":{},\"Containers\":{},\"IPAM\":{\"Driver\":\"default\",\"Options\":{},\"Config\":[{\"Subnet\":\""+subnet+"\"}]}}]";}
    private static String createdNetwork(String id,D2cDockerTopologyAdapter.NetworkAttempt attempt){String labels=attempt.labels().entrySet().stream().map(e->"\""+e.getKey()+"\":\""+e.getValue()+"\"").sorted().collect(java.util.stream.Collectors.joining(","));return "[{\"Id\":\""+id+"\",\"Driver\":\"bridge\",\"Scope\":\"local\",\"Internal\":"+attempt.internal()+",\"Attachable\":false,\"Ingress\":false,\"EnableIPv6\":false,\"Labels\":{"+labels+"},\"IPAM\":{\"Driver\":\"default\",\"Config\":[{\"Subnet\":\""+attempt.allocation().subnet()+"\",\"Gateway\":\""+attempt.allocation().bridgeGateway()+"\"}]}}]";}
    private static String container(String id,String name,String network,String endpoint){return "[{\"Id\":\""+id+"\",\"NetworkSettings\":{\"Networks\":{\""+name+"\":{\"NetworkID\":\""+network+"\",\"EndpointID\":\""+endpoint+"\",\"IPAddress\":\"172.30.0.2\",\"IPPrefixLen\":28,\"MacAddress\":\"02:00:00:00:00:02\"}}}}]";}
    private static final class QueueRunner implements D2cDockerTopologyAdapter.Runner{final ArrayDeque<DockerControlPlane.DockerTransportOutcome> results=new ArrayDeque<>();final List<List<String>> argv=new ArrayList<>();
        public DockerControlPlane.DockerTransportOutcome run(List<String> value,ContainmentDeadline deadline){argv.add(value);return results.remove();}}
    private static final class GatewayFake implements D2cGatewayAuthority.Adapter{final D2cGatewayAuthority.Evidence evidence;final AtomicInteger removes=new AtomicInteger();GatewayFake(D2cGatewayAuthority.Evidence e){evidence=e;}
        public DockerControlPlane.DockerTransportOutcome create(SingleOwnerCleanup.ProofIssuer a,D2cGatewaySpec s,ContainmentDeadline d){return ok(A);}public List<String> lookup(SingleOwnerCleanup.ProofIssuer a,D2cGatewaySpec s,ContainmentDeadline d){return List.of(A);}public D2cGatewayAuthority.Evidence inspect(SingleOwnerCleanup.ProofIssuer a,String id,ContainmentDeadline d){return evidence;}
        public DockerControlPlane.DockerTransportOutcome remove(SingleOwnerCleanup.ProofIssuer a,String id,ContainmentDeadline d){removes.incrementAndGet();return ok("");}public D2cGatewayAuthority.Absence absent(SingleOwnerCleanup.ProofIssuer a,String id,ContainmentDeadline d){return new D2cGatewayAuthority.Absence(true,DAEMON,1);}}
}
