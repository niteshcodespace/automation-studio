package com.automationstudio.engine.selenium;

import static com.automationstudio.engine.selenium.D2cTopologyModel.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashMap;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed production Docker grammar for D2c inventory, networks and endpoints. */
final class D2cDockerTopologyAdapter implements D2cEndpointAuthority.Operations {
    enum NetworkPhase { PREPARED, VERIFIED, AMBIGUOUS, ABSENT }
    record NetworkAttempt(Allocation allocation,boolean internal,long acquisitionRevision,String nonce,
            Map<String,String> labels,ContainmentDeadline deadline) {}
    record NetworkDisposition(Allocation allocation,List<String> retainedIds,NetworkPhase phase,
            DockerControlPlane.DockerDaemonIdentity daemon,long observationRevision) {
        NetworkDisposition { retainedIds=List.copyOf(retainedIds); }
        String immutableId(){return retainedIds.size()==1?retainedIds.getFirst():null;}
    }
    interface Runner { DockerControlPlane.DockerTransportOutcome run(List<String> argv, ContainmentDeadline deadline); }
    private static final java.util.regex.Pattern ID=java.util.regex.Pattern.compile("[a-f0-9]{64}");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final SingleOwnerCleanup.ProofIssuer issuer; private final Runner runner;
    private final DockerControlPlane.DockerDaemonIdentity daemon; private final AtomicLong revision=new AtomicLong();
    private final Map<ReservationKey,NetworkDisposition> networks=new LinkedHashMap<>();
    private D2cDockerTopologyAdapter(SingleOwnerCleanup.ProofIssuer issuer, Runner runner,
            DockerControlPlane.DockerDaemonIdentity daemon){this.issuer=Objects.requireNonNull(issuer);this.runner=Objects.requireNonNull(runner);this.daemon=Objects.requireNonNull(daemon);}
    static D2cDockerTopologyAdapter trusted(SingleOwnerCleanup.ProofIssuer issuer, Runner runner,
            DockerControlPlane.DockerDaemonIdentity daemon){return new D2cDockerTopologyAdapter(issuer,runner,daemon);}
    static D2cDockerTopologyAdapter production(SingleOwnerCleanup.ProofIssuer issuer,
            DockerControlPlane.DockerDaemonIdentity daemon){AtomicLong observations=new AtomicLong();
        return new D2cDockerTopologyAdapter(issuer,(argv,deadline)->{var before=observeDaemon(deadline);if(!daemon.equals(before))return failed(observations.incrementAndGet());
            var raw=DockerCliNetworkControlPlane.runProcess(argv,deadline);var after=observeDaemon(deadline);var observed=daemon.equals(after)?after:null;
            DockerControlPlane.Semantic semantic=exactNotFound(argv,raw)?DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND:DockerControlPlane.Semantic.NONE;
            return new DockerControlPlane.DockerTransportOutcome(raw.dispatch(),raw.completion(),raw.exitStatus(),raw.response(),
                    raw.responseComplete(),observations.incrementAndGet(),semantic,
                    raw.dispatch()==DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED?null:observed);},daemon);}

    D2cTopologyAllocator.Inventory inventory(SingleOwnerCleanup.ProofIssuer authority,long generation,ContainmentDeadline deadline){
        require(authority);var listed=runner.run(List.of("docker","network","ls","--no-trunc","--format","{{.ID}}"),deadline);
        if(!authoritative(listed))return incomplete(generation,deadline);
        Set<String> ids=new TreeSet<>();String text=listed.response()==null?"":listed.response().strip();
        if(!text.isEmpty())for(String line:text.split("\\R")){if(!ID.matcher(line).matches()||!ids.add(line))return incomplete(generation,deadline);}
        Set<Ipv4Cidr> subnets=new HashSet<>();
        for(String id:ids){var inspected=runner.run(List.of("docker","network","inspect",id),deadline);
            if(!authoritative(inspected))return incomplete(generation,deadline);
            try { subnets.addAll(parseNetwork(id,inspected.response())); }
            catch(IllegalArgumentException bad){return incomplete(generation,deadline);}
        }
        return new D2cTopologyAllocator.Inventory(daemon,revision.incrementAndGet(),generation,deadline,subnets,true);
    }
    synchronized NetworkAttempt prepareNetwork(SingleOwnerCleanup.ProofIssuer authority,Allocation allocation,
            boolean internal,ContainmentDeadline deadline){require(authority);if(!allocation.subnet().toString().startsWith("172.30."))throw new SecurityException("Foreign subnet");
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String nonce=java.util.HexFormat.of().formatHex(random);
        long attemptRevision=revision.incrementAndGet();
        var labels=Map.of("com.automationstudio.execution-id",allocation.key().executionId().toString(),
                "com.automationstudio.resource-role",allocation.key().network().name(),
                "com.automationstudio.topology-generation",Long.toString(allocation.key().topologyGeneration()),
                "com.automationstudio.acquisition-revision",Long.toString(attemptRevision),
                "com.automationstudio.attempt-nonce",nonce,
                "com.automationstudio.deadline-correlation",nonce);var attempt=new NetworkAttempt(allocation,internal,attemptRevision,nonce,labels,deadline);if(attempts.putIfAbsent(allocation.key(),attempt)!=null)throw new IllegalStateException("Network attempt already prepared");return attempt;}
    synchronized NetworkPhase acquireNetwork(SingleOwnerCleanup.ProofIssuer authority,NetworkAttempt attempt){require(authority);if(networks.containsKey(attempt.allocation().key()))throw new IllegalStateException("Network already attempted");
        var argv=new ArrayList<>(List.of("docker","network","create","--driver","bridge","--scope","local",
                attempt.internal()?"--internal=true":"--internal=false","--attachable=false","--ingress=false","--ipv4=true","--ipv6=false",
                "--subnet",attempt.allocation().subnet().toString(),"--gateway",attempt.allocation().bridgeGateway()));
        var labels=attempt.labels();
        labels.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->argv.addAll(List.of("--label",e.getKey()+"="+e.getValue())));
        argv.add(diagnostic(attempt.allocation()));var created=runner.run(List.copyOf(argv),attempt.deadline());Set<String> candidates=new TreeSet<>();
        if(created!=null&&created.response()!=null&&ID.matcher(created.response().strip()).matches())candidates.add(created.response().strip());
        var lookup=new ArrayList<>(List.of("docker","network","ls","--no-trunc"));labels.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->lookup.addAll(List.of("--filter","label="+e.getKey()+"="+e.getValue())));lookup.addAll(List.of("--format","{{.ID}}"));
        var recovered=runner.run(List.copyOf(lookup),attempt.deadline());if(!authoritative(recovered)){networks.put(attempt.allocation().key(),new NetworkDisposition(attempt.allocation(),List.copyOf(candidates),NetworkPhase.AMBIGUOUS,daemon,revision.incrementAndGet()));return NetworkPhase.AMBIGUOUS;}
        if(recovered.response()!=null&&!recovered.response().isBlank())for(String line:recovered.response().strip().split("\\R"))if(ID.matcher(line).matches())candidates.add(line);else {networks.put(attempt.allocation().key(),new NetworkDisposition(attempt.allocation(),List.copyOf(candidates),NetworkPhase.AMBIGUOUS,daemon,revision.incrementAndGet()));return NetworkPhase.AMBIGUOUS;}
        List<String> verified=new ArrayList<>();for(String candidate:candidates){var inspected=runner.run(List.of("docker","network","inspect",candidate),attempt.deadline());if(authoritative(inspected)&&createdNetwork(candidate,attempt,inspected.response()))verified.add(candidate);}
        NetworkPhase phase=verified.size()==1?NetworkPhase.VERIFIED:NetworkPhase.AMBIGUOUS;
        networks.put(attempt.allocation().key(),new NetworkDisposition(attempt.allocation(),List.copyOf(candidates),phase,daemon,revision.incrementAndGet()));return phase;}
    synchronized NetworkDisposition reconcileNetworkAbsence(SingleOwnerCleanup.ProofIssuer authority,Allocation allocation,ContainmentDeadline deadline){require(authority);NetworkDisposition current=networks.get(allocation.key());if(current==null)return null;
        if(current.phase()==NetworkPhase.ABSENT)return current;var candidates=new TreeSet<>(current.retainedIds());
        NetworkAttempt attempt=attemptFor(allocation,current);var lookup=new ArrayList<>(List.of("docker","network","ls","--no-trunc"));attempt.labels().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->lookup.addAll(List.of("--filter","label="+e.getKey()+"="+e.getValue())));lookup.addAll(List.of("--format","{{.ID}}"));
        var recovered=runner.run(List.copyOf(lookup),deadline);if(!authoritative(recovered))return retain(current,candidates,NetworkPhase.AMBIGUOUS,revision.incrementAndGet());
        if(recovered.response()!=null&&!recovered.response().isBlank())for(String line:recovered.response().strip().split("\\R")){if(!ID.matcher(line).matches())return retain(current,candidates,NetworkPhase.AMBIGUOUS,recovered.observationRevision());candidates.add(line);}
        if(candidates.isEmpty())return retain(current,candidates,NetworkPhase.AMBIGUOUS,recovered.observationRevision());long observedRevision=recovered.observationRevision();boolean allAbsent=true;
        for(String candidate:candidates){var before=runner.run(List.of("docker","network","inspect",candidate),deadline);observedRevision=before==null?observedRevision:before.observationRevision();
            if(exactAbsent(before))continue;if(!authoritative(before)||!createdNetwork(candidate,attempt,before.response())){allAbsent=false;continue;}
            var removed=runner.run(List.of("docker","network","rm",candidate),deadline);if(!authoritative(removed)){allAbsent=false;continue;}
            var absent=runner.run(List.of("docker","network","inspect",candidate),deadline);observedRevision=absent==null?observedRevision:absent.observationRevision();if(!exactAbsent(absent))allAbsent=false;}
        return retain(current,candidates,allAbsent?NetworkPhase.ABSENT:NetworkPhase.AMBIGUOUS,observedRevision);}
    private NetworkAttempt attemptFor(Allocation allocation,NetworkDisposition current){for(var value:attempts.values())if(value.allocation().key().equals(allocation.key()))return value;throw new IllegalStateException("Missing retained network attempt");}
    private final Map<ReservationKey,NetworkAttempt> attempts=new LinkedHashMap<>();
    private NetworkDisposition retain(NetworkDisposition current,Set<String> ids,NetworkPhase phase,long observed){var value=new NetworkDisposition(current.allocation(),List.copyOf(ids),phase,daemon,observed);networks.put(current.allocation().key(),value);return value;}
    private boolean exactAbsent(DockerControlPlane.DockerTransportOutcome value){return value!=null&&value.semantic()==DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND&&value.responseComplete()&&daemon.equals(value.daemonIdentity());}
    synchronized List<NetworkDisposition> networkLedger(SingleOwnerCleanup.ProofIssuer authority){require(authority);return List.copyOf(networks.values());}
    @Override public DockerControlPlane.DockerTransportOutcome connect(D2cEndpointAuthority.Key key,String ipv4,ContainmentDeadline deadline){
        return runner.run(List.of("docker","network","connect","--ip",ipv4,key.networkId(),key.containerId()),deadline);}
    @Override public D2cEndpointAuthority.Membership inspectMembership(D2cEndpointAuthority.Key key,ContainmentDeadline deadline){
        var out=runner.run(List.of("docker","inspect","--type","container",key.containerId()),deadline);
        if(authoritative(out))return membership(key,out.response(),out.observationRevision());
        if(out!=null&&out.dispatch()==DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                && out.semantic()==DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND&&out.responseComplete()
                &&daemon.equals(out.daemonIdentity()))return new D2cEndpointAuthority.Membership(
                        D2cEndpointAuthority.MembershipState.ABSENT,null,daemon,out.observationRevision());
        return null;}
    @Override public DockerControlPlane.DockerTransportOutcome disconnect(D2cEndpointAuthority.Key key,ContainmentDeadline deadline){
        return runner.run(List.of("docker","network","disconnect",key.networkId(),key.containerId()),deadline);}
    private boolean authoritative(DockerControlPlane.DockerTransportOutcome value){return value!=null&&value.successful()&&daemon.equals(value.daemonIdentity());}
    private D2cTopologyAllocator.Inventory incomplete(long generation,ContainmentDeadline deadline){return new D2cTopologyAllocator.Inventory(daemon,revision.incrementAndGet(),generation,deadline,Set.of(),false);}
    private void require(SingleOwnerCleanup.ProofIssuer authority){if(authority!=issuer)throw new SecurityException("Foreign topology authority");}
    private static String diagnostic(Allocation a){return "as-sel-"+a.key().network().name().toLowerCase().replace('_','-')+"-"+a.key().topologyGeneration();}
    private static Set<Ipv4Cidr> parseNetwork(String expectedId,String raw){try{JsonNode a=JSON.readTree(raw);if(a==null||!a.isArray()||a.size()!=1)throw bad();JsonNode n=a.get(0);
        if(!expectedId.equals(text(n,"Id"))||!"bridge".equals(text(n,"Driver"))||!"local".equals(text(n,"Scope")))throw bad();
        bool(n,"Internal");bool(n,"Attachable");bool(n,"Ingress");bool(n,"EnableIPv6");object(n,"Options");object(n,"Labels");object(n,"Containers");
        JsonNode ipam=object(n,"IPAM");if(!"default".equals(text(ipam,"Driver")))throw bad();object(ipam,"Options");JsonNode configs=ipam.get("Config");if(configs==null||!configs.isArray())throw bad();
        Set<Ipv4Cidr> result=new HashSet<>();for(JsonNode c:configs){if(!c.isObject()||!result.add(Ipv4Cidr.parse(text(c,"Subnet"))))throw bad();}if(result.isEmpty())throw bad();return result;
    }catch(RuntimeException x){throw bad();}}
    private static boolean createdNetwork(String id,NetworkAttempt attempt,String raw){try{JsonNode a=JSON.readTree(raw);if(a==null||!a.isArray()||a.size()!=1)throw bad();JsonNode n=a.get(0);if(!id.equals(text(n,"Id"))||!"bridge".equals(text(n,"Driver"))||!"local".equals(text(n,"Scope"))
                ||bool(n,"Internal")!=attempt.internal()||bool(n,"Attachable")||bool(n,"Ingress")||bool(n,"EnableIPv6"))throw bad();JsonNode labels=object(n,"Labels");if(labels.size()!=attempt.labels().size())throw bad();for(var e:attempt.labels().entrySet())if(!e.getValue().equals(text(labels,e.getKey())))throw bad();
        JsonNode ipam=object(n,"IPAM"),configs=ipam.get("Config");if(!"default".equals(text(ipam,"Driver"))||configs==null||!configs.isArray()||configs.size()!=1||!attempt.allocation().subnet().toString().equals(text(configs.get(0),"Subnet"))||!attempt.allocation().bridgeGateway().equals(text(configs.get(0),"Gateway")))throw bad();return true;}catch(RuntimeException bad){return false;}}
    private D2cEndpointAuthority.Membership membership(D2cEndpointAuthority.Key key,String raw,long observation){try{JsonNode a=JSON.readTree(raw);if(a==null||!a.isArray()||a.size()!=1||!key.containerId().equals(text(a.get(0),"Id")))throw bad();
        JsonNode networks=object(object(a.get(0),"NetworkSettings"),"Networks");D2cEndpointAuthority.Evidence found=null;
        for(var it=networks.properties().iterator();it.hasNext();){var e=it.next();JsonNode n=e.getValue();if(key.networkId().equals(text(n,"NetworkID"))){if(found!=null)throw bad();String address=text(n,"IPAddress"),prefix=Integer.toString(integer(n,"IPPrefixLen"));found=new D2cEndpointAuthority.Evidence(text(n,"EndpointID"),address,prefix,text(n,"MacAddress"),daemon);}}
        return new D2cEndpointAuthority.Membership(found==null?D2cEndpointAuthority.MembershipState.ABSENT:D2cEndpointAuthority.MembershipState.PRESENT,found,daemon,observation);
        }catch(RuntimeException x){return new D2cEndpointAuthority.Membership(D2cEndpointAuthority.MembershipState.UNKNOWN,null,daemon,observation);}}
    private static JsonNode object(JsonNode n,String f){JsonNode v=n==null?null:n.get(f);if(v==null||!v.isObject())throw bad();return v;}
    private static String text(JsonNode n,String f){JsonNode v=n==null?null:n.get(f);if(v==null||!v.isTextual())throw bad();return v.asText();}
    private static boolean bool(JsonNode n,String f){JsonNode v=n==null?null:n.get(f);if(v==null||!v.isBoolean())throw bad();return v.asBoolean();}
    private static int integer(JsonNode n,String f){JsonNode v=n==null?null:n.get(f);if(v==null||!v.isIntegralNumber())throw bad();return v.asInt();}
    private static IllegalArgumentException bad(){return new IllegalArgumentException("Malformed authoritative Docker topology inspection");}
    private static DockerControlPlane.DockerDaemonIdentity observeDaemon(ContainmentDeadline deadline){try{
        String endpoint=probe(List.of("docker","context","inspect","--format","{{.Endpoints.docker.Host}}"),deadline);
        String context=probe(List.of("docker","context","show"),deadline);String engine=probe(List.of("docker","info","--format","{{.ID}}"),deadline);
        return new DockerControlPlane.DockerDaemonIdentity(endpoint,context,engine);}catch(RuntimeException bad){return null;}}
    private static String probe(List<String> argv,ContainmentDeadline deadline){var r=DockerCliNetworkControlPlane.runProcess(argv,deadline);String value=r.response()==null?null:r.response().strip();
        if(r.dispatch()!=DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED||r.completion()!=DockerControlPlane.Completion.COMPLETED||!Integer.valueOf(0).equals(r.exitStatus())||!r.responseComplete()||value==null||value.isBlank())throw bad();return value;}
    private static DockerControlPlane.DockerTransportOutcome failed(long revision){return new DockerControlPlane.DockerTransportOutcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
            DockerControlPlane.Completion.PROTOCOL_FAILED,null,null,false,revision,DockerControlPlane.Semantic.NONE,null);}
    private static boolean exactNotFound(List<String> argv,DockerCliNetworkControlPlane.CommandResult raw){if(raw==null||!raw.responseComplete()||raw.exitStatus()==null||raw.exitStatus()==0||argv.isEmpty())return false;
        String id=argv.getLast(),response=raw.response()==null?"":raw.response().strip();if(!ID.matcher(id).matches())return false;
        String quoted=java.util.regex.Pattern.quote(id);return java.util.regex.Pattern.compile("(?is)^(?:error response from daemon:\\s*)?(?:no such (?:container|network):?\\s*"+quoted+"|network\\s+"+quoted+"\\s+not found)$").matcher(response).matches();}
}
