package com.automationstudio.engine.karate;

import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublisher;
import com.automationstudio.engine.sdk.EngineExecutionState;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Builds required evidence only from bounded structural worker results. */
final class KarateEvidenceReport {
    static final String LOGICAL_NAME="karate-sanitized-summary.json",MEDIA_TYPE="application/json";
    static final int MAX_REPORT_BYTES=65_536;
    private static final ObjectMapper JSON=new ObjectMapper();
    private KarateEvidenceReport(){}

    static void publish(ArtifactPublisher publisher,EngineExecutionState state,
            KarateWorkerRuntime.WorkerExecutionResult result,Duration duration){
        Objects.requireNonNull(publisher);Objects.requireNonNull(state);Objects.requireNonNull(result);Objects.requireNonNull(duration);
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion",1);report.put("engineId",KarateEnginePlugin.ENGINE_ID);report.put("engineVersion",KarateEnginePlugin.IMPLEMENTATION_VERSION);report.put("outcome",state.name());report.put("features",result.features());report.put("scenarios",result.scenarios());report.put("passed",result.passed());report.put("failed",result.failed());report.put("durationMillis",duration.toMillis());report.put("failureCategories","NONE".equals(result.diagnostic())?List.of():List.of(result.diagnostic()));
        try{byte[] content=JSON.writeValueAsBytes(report);if(content.length>MAX_REPORT_BYTES)throw new KarateEngineException("EVIDENCE_TOO_LARGE","Karate evidence is too large");publisher.publish(new ArtifactPublication(ArtifactCategory.REPORT,LOGICAL_NAME,MEDIA_TYPE,Map.of("schemaVersion","1","engineId",KarateEnginePlugin.ENGINE_ID),out->out.write(content)));}
        catch(KarateEngineException e){throw e;}catch(com.automationstudio.engine.sdk.ArtifactPublicationException e){throw e;}catch(Exception e){throw new KarateEngineException("EVIDENCE_SERIALIZATION_FAILED","Karate evidence could not be serialized");}
    }
}
