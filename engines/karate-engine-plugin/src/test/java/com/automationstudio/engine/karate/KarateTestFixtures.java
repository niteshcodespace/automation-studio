package com.automationstudio.engine.karate;

import com.automationstudio.engine.conformance.InMemoryArtifactPublisher;
import com.automationstudio.engine.conformance.InMemoryExecutionSecretAccess;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

final class KarateTestFixtures {
    private KarateTestFixtures() { }

    static Map<String, Object> configuration() {
        return Map.of("schemaVersion", "1", "featureRoot", "features",
                "includeTags", java.util.List.of("@smoke"),
                "secretReferences", Map.of("apiKey", "logical-api-key"));
    }

    static EngineExecutionRequest request(long seed, Map<String, byte[]> files,
            InMemoryWorkspaceAccess[] observed) {
        UUID id = new UUID(30, seed);
        WorkspaceId workspaceId = new WorkspaceId(new UUID(300, seed));
        var workspace = new InMemoryWorkspaceAccess(id, workspaceId, files);
        if (observed != null) observed[0] = workspace;
        var context = new EngineExecutionContext(id,
                new EngineIdentity(KarateEnginePlugin.ENGINE_ID, KarateEnginePlugin.IMPLEMENTATION_VERSION),
                "features", configuration(), "https://example.invalid", Map.of(), Map.of());
        return new EngineExecutionRequest(context, new PreparedSource(workspaceId, "GIT_HTTPS", "revision-" + seed),
                workspace, new InMemoryExecutionSecretAccess(id, Map.of("logical-api-key", "secret".toCharArray())),
                new InMemoryArtifactPublisher(id));
    }

    static Map<String, byte[]> features(String... paths) {
        var files = new java.util.LinkedHashMap<String, byte[]>();
        for (String path : paths) files.put(path, "Feature: structural\n".getBytes(StandardCharsets.UTF_8));
        return files;
    }
}
