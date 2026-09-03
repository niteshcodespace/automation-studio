package com.automationstudio.engine.selenium;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed gateway-only Docker CLI grammar. */
final class D2cCliGatewayAdapter implements D2cGatewayAuthority.Adapter {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final SingleOwnerCleanup.ProofIssuer issuer;private final D2cDockerTopologyAdapter.Runner runner;
    D2cCliGatewayAdapter(SingleOwnerCleanup.ProofIssuer issuer,D2cDockerTopologyAdapter.Runner runner){this.issuer=Objects.requireNonNull(issuer);this.runner=Objects.requireNonNull(runner);}
    @Override public DockerControlPlane.DockerTransportOutcome create(SingleOwnerCleanup.ProofIssuer authority,D2cGatewaySpec spec,ContainmentDeadline deadline){require(authority);
        var argv=new ArrayList<>(List.of("docker","create","--read-only","--user",spec.user(),"--network","none","--pid","private","--ipc","private",
                "--restart","no","--cap-drop","ALL","--cap-add","NET_ADMIN","--tmpfs",spec.tmpfs().getFirst(),"--env",spec.environment().getFirst()));
        spec.labels().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e->argv.addAll(List.of("--label",e.getKey()+"="+e.getValue())));
        argv.add("--entrypoint");argv.add(D2cGatewaySpec.ENTRYPOINT);argv.add(spec.imageDigest());return runner.run(List.copyOf(argv),deadline);}
    @Override public List<String> lookup(SingleOwnerCleanup.ProofIssuer authority,D2cGatewaySpec spec,ContainmentDeadline deadline){require(authority);var argv=new ArrayList<>(List.of("docker","ps","-a","--no-trunc"));
        spec.labels().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->argv.addAll(List.of("--filter","label="+e.getKey()+"="+e.getValue())));argv.addAll(List.of("--format","{{.ID}}"));
        var out=runner.run(List.copyOf(argv),deadline);if(out==null||!out.successful()||out.daemonIdentity()==null)return null;String text=out.response()==null?"":out.response().strip();if(text.isEmpty())return List.of();
        var ids=new java.util.TreeSet<String>();for(String line:text.split("\\R")){if(!line.matches("[a-f0-9]{64}")||!ids.add(line))return List.of();}return List.copyOf(ids);}
    @Override public D2cGatewayAuthority.Evidence inspect(SingleOwnerCleanup.ProofIssuer authority,String id,ContainmentDeadline deadline){require(authority);var out=runner.run(List.of("docker","inspect","--type","container",id),deadline);
        if(out==null||!out.successful()||out.daemonIdentity()==null)return null;try{JsonNode array=JSON.readTree(out.response());if(array==null||!array.isArray()||array.size()!=1)throw new IllegalArgumentException();
            JsonNode labels=array.get(0).path("Config").path("Labels");if(!labels.isObject())throw new IllegalArgumentException();var map=new java.util.HashMap<String,String>();
            labels.properties().forEach(e->{if(!e.getValue().isTextual())throw new IllegalArgumentException();map.put(e.getKey(),e.getValue().asText());});var fingerprint=DockerInspectParser.parse(out.response());
            var image=runner.run(List.of("docker","image","inspect","--format","{{index .Config.Labels \"com.automationstudio.executable-digest\"}}",fingerprint.imageIdentity()),deadline);
            String executable=image==null||image.response()==null?null:image.response().strip();if(image==null||!image.successful()||!Objects.equals(out.daemonIdentity(),image.daemonIdentity())||executable==null||!executable.matches("sha256:[a-f0-9]{64}"))return null;
            return new D2cGatewayAuthority.Evidence(fingerprint,map,executable,out.daemonIdentity());}catch(RuntimeException failure){return null;}}
    @Override public DockerControlPlane.DockerTransportOutcome remove(SingleOwnerCleanup.ProofIssuer authority,String id,ContainmentDeadline deadline){require(authority);valid(id);return runner.run(List.of("docker","rm","--force",id),deadline);}
    @Override public D2cGatewayAuthority.Absence absent(SingleOwnerCleanup.ProofIssuer authority,String id,ContainmentDeadline deadline){require(authority);valid(id);var out=runner.run(List.of("docker","inspect","--type","container",id),deadline);
        return out!=null&&out.semantic()==DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND&&out.responseComplete()&&out.daemonIdentity()!=null
                ?new D2cGatewayAuthority.Absence(true,out.daemonIdentity(),out.observationRevision()):null;}
    private void require(SingleOwnerCleanup.ProofIssuer authority){if(authority!=issuer)throw new SecurityException("Foreign gateway authority");}
    private static void valid(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid gateway ID");}
}
