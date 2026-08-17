package com.automationstudio.engine.karate;

import static org.junit.jupiter.api.Assertions.*;
import com.automationstudio.engine.conformance.InMemoryExecutionSecretAccess;
import com.automationstudio.engine.sdk.EngineExecutionState;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KarateEnginePluginTest {
    @Test void exposesStableIdentityAndPerformsStructuralDiscoveryOnly() {
        var request = KarateTestFixtures.request(1, KarateTestFixtures.features("features/a.feature"), null);
        var plugin = plugin();
        assertEquals("karate", plugin.descriptor().engineId());
        assertEquals("1.5.2", plugin.descriptor().implementationVersion());
        assertEquals(EngineExecutionState.SUCCEEDED, plugin.execute(request).state());
        assertEquals(0, ((InMemoryExecutionSecretAccess) request.secretAccess()).resolutionCount());
        var evidence=((com.automationstudio.engine.conformance.InMemoryArtifactPublisher)request.artifactPublisher()).observations();
        assertEquals(1,evidence.size());
        assertEquals(com.automationstudio.engine.sdk.ArtifactCategory.REPORT,evidence.getFirst().receipt().category());
        assertEquals(KarateEvidenceReport.LOGICAL_NAME,evidence.getFirst().receipt().logicalName());
    }

    @Test void closesPreparedSourceAfterSuccessfulDiscovery() {
        var observed = new com.automationstudio.engine.conformance.InMemoryWorkspaceAccess[1];
        var request = KarateTestFixtures.request(4, KarateTestFixtures.features("features/a.feature"), observed);
        plugin().execute(request);
        assertEquals(1, observed[0].openedHandleCount());
        assertEquals(1, observed[0].closedHandleCount());
    }

    @Test void validationDoesNotAcquirePreparedSource() {
        var observed = new com.automationstudio.engine.conformance.InMemoryWorkspaceAccess[1];
        var request = KarateTestFixtures.request(2, KarateTestFixtures.features("features/a.feature"), observed);
        plugin().validate(request.context());
        assertEquals(0, observed[0].openedHandleCount());
    }

    @Test void sanitizesInvalidRequests() {
        var request = KarateTestFixtures.request(3, KarateTestFixtures.features("features/a.feature"), null);
        var badContext = new com.automationstudio.engine.sdk.EngineExecutionContext(request.executionId(),
                request.context().engineIdentity(), "features", Map.of("schemaVersion", "1", "featureRoot", "../secret"),
                "https://example.invalid", Map.of(), Map.of());
        var exception = assertThrows(KarateEngineException.class, () -> plugin().validate(badContext));
        assertEquals("INVALID_FEATURE_ROOT", exception.code());
        assertNull(exception.getCause());
    }

    @Test void publishesOnlyAllowlistedStructuralEvidence()throws Exception{
        var request=KarateTestFixtures.request(5,KarateTestFixtures.features("features/private.feature"),null);
        plugin().execute(request);var observation=((com.automationstudio.engine.conformance.InMemoryArtifactPublisher)request.artifactPublisher()).observations().getFirst();
        String text=new String(observation.content(),StandardCharsets.UTF_8);assertFalse(text.contains("private.feature"));assertFalse(text.contains("logical-api-key"));assertFalse(text.contains("secret"));
        var json=new ObjectMapper().readTree(observation.content());assertEquals("karate",json.get("engineId").asText());assertEquals("SUCCEEDED",json.get("outcome").asText());assertEquals(1,json.get("scenarios").asInt());assertEquals(0,json.get("failureCategories").size());
    }

    @Test void requiredEvidencePublicationFailureFailsClosed(){
        var original=KarateTestFixtures.request(6,KarateTestFixtures.features("features/a.feature"),null);var failing=new com.automationstudio.engine.sdk.ArtifactPublisher(){public java.util.UUID executionId(){return original.executionId();}public com.automationstudio.engine.sdk.ArtifactReceipt publish(com.automationstudio.engine.sdk.ArtifactPublication value){throw new com.automationstudio.engine.sdk.ArtifactPublicationException("ARTIFACT_PUBLICATION_FAILED","Artifact publication failed safely");}};
        var request=new com.automationstudio.engine.sdk.EngineExecutionRequest(original.context(),original.preparedSource(),original.workspaceAccess(),original.secretAccess(),failing);assertThrows(com.automationstudio.engine.sdk.ArtifactPublicationException.class,()->plugin().execute(request));
    }

    @Test void validatesTimingBeforePublicationAndPublishesZeroForBackwardClock(){
        var request=KarateTestFixtures.request(7,KarateTestFixtures.features("features/a.feature"),null);var publisher=(com.automationstudio.engine.conformance.InMemoryArtifactPublisher)request.artifactPublisher();
        var exception=assertThrows(KarateEngineException.class,()->plugin(clock("2026-08-17T10:00:01Z","2026-08-17T10:00:00Z"),"SUCCEEDED",1,1,1,0,"NONE").execute(request));
        assertEquals("FEATURE_DISCOVERY_FAILED",exception.code());assertTrue(publisher.observations().isEmpty());
    }

    @Test void publishesValidatedZeroAndPositiveDurations()throws Exception{
        var zero=KarateTestFixtures.request(8,KarateTestFixtures.features("features/a.feature"),null);var zeroResult=plugin(clock("2026-08-17T10:00:00Z","2026-08-17T10:00:00Z"),"SUCCEEDED",1,1,1,0,"NONE").execute(zero);assertEquals(Duration.ZERO,zeroResult.duration());assertEquals(0,report(zero).get("durationMillis").asLong());
        var positive=KarateTestFixtures.request(9,KarateTestFixtures.features("features/a.feature"),null);var positiveResult=plugin(clock("2026-08-17T10:00:00Z","2026-08-17T10:00:02Z"),"SUCCEEDED",2,4,3,1,"KARATE_ASSERTION_FAILED").execute(positive);assertEquals(Duration.ofSeconds(2),positiveResult.duration());var json=report(positive);assertEquals(2000,json.get("durationMillis").asLong());assertEquals(2,json.get("features").asInt());assertEquals(4,json.get("scenarios").asInt());assertEquals(3,json.get("passed").asInt());assertEquals(1,json.get("failed").asInt());
    }

    @Test void publishesFailedAndCancelledOutcomes()throws Exception{
        var failed=KarateTestFixtures.request(10,KarateTestFixtures.features("features/a.feature"),null);assertEquals(EngineExecutionState.FAILED,plugin(clock("2026-08-17T10:00:00Z","2026-08-17T10:00:01Z"),"FAILED",1,1,0,1,"KARATE_ASSERTION_FAILED").execute(failed).state());assertEquals("FAILED",report(failed).get("outcome").asText());
        var cancelled=KarateTestFixtures.request(11,KarateTestFixtures.features("features/a.feature"),null);assertEquals(EngineExecutionState.CANCELLED,plugin(clock("2026-08-17T10:00:00Z","2026-08-17T10:00:01Z"),"CANCELLED",0,0,0,0,"EXECUTION_CANCELLED").execute(cancelled).state());assertEquals("CANCELLED",report(cancelled).get("outcome").asText());
    }

    private static tools.jackson.databind.JsonNode report(com.automationstudio.engine.sdk.EngineExecutionRequest request)throws Exception{return new ObjectMapper().readTree(((com.automationstudio.engine.conformance.InMemoryArtifactPublisher)request.artifactPublisher()).observations().getFirst().content());}
    private static Clock clock(String... instants){var values=java.util.Arrays.stream(instants).map(Instant::parse).toList();return new Clock(){private final AtomicInteger index=new AtomicInteger();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return values.get(Math.min(index.getAndIncrement(),values.size()-1));}};}
    private static KarateEnginePlugin plugin(Clock clock,String outcome,int features,int scenarios,int passed,int failed,String diagnostic){return new KarateEnginePlugin(clock,new KarateFeatureDiscovery(),(id,source,projected,paths,configuration,variables,base,secrets)->new KarateWorkerRuntime.WorkerExecutionResult(outcome,features,scenarios,passed,failed,diagnostic));}

    private static KarateEnginePlugin plugin() {
        return new KarateEnginePlugin(java.time.Clock.systemUTC(), new KarateFeatureDiscovery(), (id, source, projected, features, configuration, variables, base, secrets) -> new KarateWorkerRuntime.WorkerExecutionResult("SUCCEEDED",1,1,1,0,"NONE"));
    }
}
