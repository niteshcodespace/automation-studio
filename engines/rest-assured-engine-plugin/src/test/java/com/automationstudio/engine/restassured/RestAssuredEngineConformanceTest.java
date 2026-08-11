package com.automationstudio.engine.restassured;

import com.automationstudio.engine.conformance.ExecutionEnginePluginConformanceContract;
import com.automationstudio.engine.conformance.ExecutionEnginePluginFixture;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParserTest;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class RestAssuredEngineConformanceTest implements ExecutionEnginePluginConformanceContract {

    private final Fixture fixture = new Fixture();

    @Override
    public ExecutionEnginePluginFixture fixture() {
        return fixture;
    }

    private static final class Fixture implements ExecutionEnginePluginFixture {
        private final ExecutionEnginePlugin plugin = RestAssuredEnginePluginTest.plugin();
        private final Map<UUID, InMemoryWorkspaceAccess> workspaces = new ConcurrentHashMap<>();
        private final List<EngineExecutionRequest> requests = createRequests();

        @Override public ExecutionEnginePlugin plugin() { return plugin; }
        @Override public EngineExecutionRequest validRequest() { return requests.getFirst(); }
        @Override public EngineExecutionState expectedState() { return EngineExecutionState.SUCCEEDED; }
        @Override public List<EngineExecutionRequest> concurrentRequests() { return requests.subList(1, 3); }
        @Override public long validationResourceAcquisitions() {
            return workspaces.values().stream().mapToLong(InMemoryWorkspaceAccess::openedHandleCount).sum();
        }
        @Override public boolean cleanupObserved(UUID executionId) {
            InMemoryWorkspaceAccess workspace = workspaces.get(executionId);
            return workspace != null && workspace.openedHandleCount() > 0
                    && workspace.openedHandleCount() == workspace.closedHandleCount();
        }

        private List<EngineExecutionRequest> createRequests() {
            List<EngineExecutionRequest> values = new ArrayList<>();
            for (long seed = 1; seed <= 3; seed++) {
                UUID executionId = new UUID(29, seed);
                var secrets = new RestAssuredEnginePluginTest.TrackingSecretAccess(executionId);
                var context = new com.automationstudio.engine.sdk.EngineExecutionContext(executionId,
                        new com.automationstudio.engine.sdk.EngineIdentity(
                                RestAssuredEnginePlugin.ENGINE_ID,
                                RestAssuredEnginePlugin.IMPLEMENTATION_VERSION),
                        "api-manifest.json", Map.of(), "https://example.invalid", Map.of(), Map.of());
                var workspaceId = new com.automationstudio.engine.sdk.WorkspaceId(new UUID(290, seed));
                var workspace = new InMemoryWorkspaceAccess(executionId, workspaceId, Map.of(
                        "api-manifest.json", RestAssuredManifestParserTest.validManifest()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                workspaces.put(executionId, workspace);
                values.add(new EngineExecutionRequest(context,
                        new com.automationstudio.engine.sdk.PreparedSource(
                                workspaceId, "GIT_HTTPS", "revision-" + seed), workspace, secrets));
            }
            return List.copyOf(values);
        }
    }
}
