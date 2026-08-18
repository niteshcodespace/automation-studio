package com.automationstudio.api.execution.engine.karate;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.execution.*;
import com.automationstudio.api.execution.artifact.metadata.*;
import com.automationstudio.api.execution.artifact.storage.*;
import com.automationstudio.api.execution.artifact.storage.local.LocalArtifactStorage;
import com.automationstudio.api.execution.engine.*;
import com.automationstudio.api.execution.orchestration.*;
import com.automationstudio.api.execution.preparation.*;
import com.automationstudio.api.execution.secret.*;
import com.automationstudio.api.execution.workspace.*;
import com.automationstudio.api.execution.workspace.local.*;
import com.automationstudio.api.execution.workspace.local.access.LocalEngineWorkspaceAccessResolver;
import com.automationstudio.api.source.*;
import com.automationstudio.api.source.materialization.git.*;
import com.automationstudio.engine.karate.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@EnabledIfSystemProperty(named="automation.karate.feature.tests",matches="true")
class KarateCanonicalLoopbackIntegrationTest {
    private static final String CANARY="as030f-secret-canary-never-durable";
    private static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test void executesExactRevisionThroughCanonicalLoopbackPathAndPublishesSanitizedEvidence()throws Exception{
        String priorWorker=System.getProperty("automation.karate.worker.image"),priorGateway=System.getProperty("automation.karate.gateway.image");
        try{
            System.setProperty("automation.karate.worker.image",buildImage("engines/karate-worker-runtime"));
            System.setProperty("automation.karate.gateway.image",buildImage("engines/karate-gateway-runtime"));
            Path origin=createRepository();String revision=run(origin,"git","rev-parse","HEAD");UUID executionId=UUID.randomUUID(),workspaceId=UUID.randomUUID(),projectId=UUID.randomUUID();
            Path workspaceRoot=temporaryDirectory.resolve("workspaces"),artifactRoot=temporaryDirectory.resolve("artifacts");
            var provider=new LocalWorkspaceProvider(new WorkspaceRootProperties(workspaceRoot.toString()),Clock.systemUTC());var manager=new WorkspaceManager(provider);
            var preparation=new SourcePreparationServiceImpl(manager,new GitSourceMaterializer(provider,new SourceConfigurationValidator(),new GitMaterializationProperties("git",Duration.ofSeconds(30),65_536,true),Clock.systemUTC()),Clock.systemUTC());
            var secretProvider=new RecordingSecretProvider();var secretFactory=new ExecutionSecretScopeFactory(new ExecutionSecretProviderRegistry(List.of(secretProvider)));
            var metadata=new RecordingMetadataService();var storage=new LocalArtifactStorage(artifactRoot,Clock.systemUTC());var limits=new ArtifactStorageLimits(artifactRoot.toString(),1_048_576,2_097_152,4,1);
            ArtifactPublisherFactory publishers=(w,p,e)->new StorageBackedArtifactPublisher(e,storage,limits,w,p,metadata,"retain:test");
            var plugin=new KarateEnginePlugin(GatewayAddressPolicy.LOOPBACK_ONLY_TEST);var registry=new ExecutionEngineRegistryImpl(List.of(plugin));
            assertThat(registry.resolve("karate","1.5.2").engine()).isSameAs(plugin);
            var orchestrator=new ExecutionOrchestratorImpl(preparation,registry,manager,secretFactory,new LocalEngineWorkspaceAccessResolver(provider),publishers,Clock.systemUTC());
            WorkspaceDescriptor planned=WorkspaceDescriptor.planned(new WorkspaceId(workspaceId),executionId,LocalWorkspaceProvider.PROVIDER_ID);
            ExecutionSourceReference source=new ExecutionSourceReference(SourceType.GIT_HTTPS,origin.toUri().toASCIIString(),revision,null);
            ExecutionOrchestrationResult result=orchestrator.execute(new ExecutionOrchestrationRequest(context(executionId,workspaceId,projectId),new SourcePreparationRequest(planned,source)));
            assertThat(result.engineResult().state().name()).isEqualTo("SUCCEEDED");assertThat(result.engineResult().resolvedRevision()).isEqualTo(revision);assertThat(result.engineResult().duration().isNegative()).isFalse();
            assertThat(secretProvider.resolutions).hasValue(3);assertThat(secretProvider.issued).allMatch(ResolvedSecret::isClosed);
            assertThat(metadata.values).singleElement().satisfies(value->{assertThat(value.category()).isEqualTo("REPORT");assertThat(value.logicalName()).isEqualTo("karate-sanitized-summary.json");assertThat(value.mediaType()).isEqualTo("application/json");assertThat(value.checksumAlgorithm()).isEqualTo("SHA-256");assertThat(value.metadata()).containsEntry("engineId","karate");});
            ArtifactMetadata evidence=metadata.values.getFirst();Path stored=artifactRoot.resolve(UUID.fromString(evidence.storageReference().substring("artifact:v1:".length()))+".artifact");byte[] bytes=Files.readAllBytes(stored);assertThat(storage.verify(new StoredArtifact(new ArtifactStorageReference(evidence.storageReference()),evidence.sizeBytes(),evidence.checksumAlgorithm(),evidence.checksum(),evidence.createdAt().toInstant()))).isTrue();
            var report=JSON.readTree(bytes);assertThat(report.get("engineId").asText()).isEqualTo("karate");assertThat(report.get("outcome").asText()).isEqualTo("SUCCEEDED");assertThat(report.get("features").asInt()).isEqualTo(1);assertThat(report.get("scenarios").asInt()).isEqualTo(3);assertThat(report.get("passed").asInt()).isEqualTo(3);assertThat(report.get("failed").asInt()).isZero();assertThat(report.get("durationMillis").asLong()).isNotNegative();
            String durable=new String(bytes,StandardCharsets.UTF_8)+metadata.values;assertThat(durable).doesNotContain(CANARY,"Authorization","as030f.feature","X-AS-Correlation","suite-proof","127.0.0.1");
            assertThat(workspaceRoot.resolve(workspaceId.toString())).doesNotExist();assertDockerAbsent(executionId);
        }finally{restore("automation.karate.worker.image",priorWorker);restore("automation.karate.gateway.image",priorGateway);}
    }

    private ExecutionContext context(UUID executionId,UUID workspaceId,UUID projectId){OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);Map<String,Object> config=Map.of("schemaVersion","1","featureRoot","features","includeTags",List.of("@as030f"),"variables",Map.of("suiteMarker","suite-proof"),"secretReferences",Map.of("token","platform-token"),"authentication",Map.of("type","BEARER","secretRef","token"),"parallelism",2);
        return new ExecutionContext(executionId,projectId,workspaceId,new ExecutionSuiteSnapshot(UUID.randomUUID(),"AS-030F","karate","1.5.2","KARATE",null,"features",Map.of(),config),new ExecutionEnvironmentSnapshot(UUID.randomUUID(),"Loopback","TEST","http://127.0.0.1:8080",Map.of(),Map.of()),List.of(new ExecutionSecretReference("platform-token",Map.of("provider","as030f","key","token"))),Map.of("targetUrl",new ExecutionVariable("targetUrl","http://127.0.0.1:8080",ExecutionVariableSource.EXECUTION),"executionId",new ExecutionVariable("executionId",executionId.toString(),ExecutionVariableSource.EXECUTION)),new ExecutionRunnerContext(UUID.randomUUID(),"runner","1","windows","amd64",Map.of(),Map.of()),new ExecutionMetadata(UUID.randomUUID(),now,now,Duration.ofMinutes(5),ExecutionRetryPolicy.DISABLED));}
    private Path createRepository()throws Exception{Path origin=temporaryDirectory.resolve("origin");Files.createDirectory(origin);run(origin,"git","init");Path features=Files.createDirectory(origin.resolve("features"));Files.writeString(features.resolve("as030f.feature"),"""
            @as030f
            Feature: AS-030F canonical loopback
            Scenario Outline: bounded canonical scenario <caseId>
              * match suiteMarker == 'suite-proof'
              * url targetUrl
              * header X-AS-Correlation = executionId
              * path 'health'
              * method get
              * status 204
            Examples:
              | caseId |
              | one    |
              | two    |
              | three  |
            """);run(origin,"git","add","--","features/as030f.feature");run(origin,"git","-c","user.name=Automation Studio Test","-c","user.email=automation-studio@example.invalid","commit","-m","fixture");return origin;}
    private String buildImage(String relative)throws Exception{Path module=repositoryRoot().resolve(relative).normalize().toAbsolutePath();assertThat(module).isDirectory();assertThat(module.resolve("Dockerfile")).isRegularFile();String output=run(module,"docker","build","--quiet",".");String id=output.lines().filter(v->v.startsWith("sha256:")).reduce((a,b)->b).orElseThrow();assertThat(id).matches("sha256:[a-f0-9]{64}");return id;}
    private static Path repositoryRoot(){Path current=Path.of("").toAbsolutePath().normalize();while(current!=null&&!isRepositoryRoot(current)){current=current.getParent();}if(current==null)throw new AssertionError("Repository root could not be located from the Maven test working directory");return current;}
    private static boolean isRepositoryRoot(Path candidate){return Files.isRegularFile(candidate.resolve("pom.xml"))&&Files.isDirectory(candidate.resolve("engines/karate-worker-runtime"))&&Files.isDirectory(candidate.resolve("engines/karate-gateway-runtime"))&&Files.isDirectory(candidate.resolve("backend/studio-api"));}
    private static String run(Path directory,String... command)throws Exception{ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);builder.environment().put("GIT_TERMINAL_PROMPT","0");Process process=builder.start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();assertThat(process.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).withFailMessage(output).isZero();return output;}
    private static void assertDockerAbsent(UUID id)throws Exception{String suffix=id.toString().replace("-","");for(String[] command:new String[][]{{"docker","container","inspect","as-karate-"+suffix},{"docker","container","inspect","as-karate-gateway-"+suffix},{"docker","network","inspect","as-karate-net-"+suffix}}){Process p=new ProcessBuilder(command).redirectErrorStream(true).start();p.getInputStream().readAllBytes();assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isNotZero();}}
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static final class RecordingSecretProvider implements ExecutionSecretProvider{final AtomicInteger resolutions=new AtomicInteger();final List<ResolvedSecret> issued=new ArrayList<>();public String providerId(){return "as030f";}public ResolvedSecret resolve(Object reference){resolutions.incrementAndGet();ResolvedSecret value=ResolvedSecret.from(CANARY.toCharArray());issued.add(value);return value;}}
    private static final class RecordingMetadataService implements ArtifactMetadataService{final List<ArtifactMetadata> values=new ArrayList<>();public ArtifactMetadata register(UUID workspace,UUID project,UUID execution,ArtifactRegistration r){ArtifactMetadata value=new ArtifactMetadata(r.artifactId(),execution,r.category().value(),r.logicalName(),r.mediaType(),r.storedArtifact().sizeBytes(),r.storedArtifact().checksumAlgorithm(),r.storedArtifact().checksum(),r.storedArtifact().storageReference().value(),r.storedArtifact().finalizedAt().atOffset(ZoneOffset.UTC),r.retentionReference(),r.metadata());values.add(value);return value;}public List<ArtifactMetadata> list(UUID w,UUID p,UUID e){return List.copyOf(values);}public Optional<ArtifactMetadata> find(UUID w,UUID p,UUID e,UUID a){return values.stream().filter(v->v.artifactId().equals(a)).findFirst();}}
}
