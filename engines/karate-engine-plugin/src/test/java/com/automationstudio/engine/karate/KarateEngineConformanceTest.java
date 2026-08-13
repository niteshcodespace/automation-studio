package com.automationstudio.engine.karate;

import com.automationstudio.engine.conformance.ExecutionEnginePluginConformanceContract;
import com.automationstudio.engine.conformance.ExecutionEnginePluginFixture;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class KarateEngineConformanceTest implements ExecutionEnginePluginConformanceContract {
    private final Fixture fixture = new Fixture();
    @Override public ExecutionEnginePluginFixture fixture() { return fixture; }

    private static final class Fixture implements ExecutionEnginePluginFixture {
        private final ExecutionEnginePlugin plugin = new KarateEnginePlugin(java.time.Clock.systemUTC(),
                new KarateFeatureDiscovery(), (id, source, paths) -> {});
        private final Map<UUID, InMemoryWorkspaceAccess> workspaces = new ConcurrentHashMap<>();
        private final List<EngineExecutionRequest> requests = requests();
        public ExecutionEnginePlugin plugin() { return plugin; }
        public EngineExecutionRequest validRequest() { return requests.getFirst(); }
        public EngineExecutionState expectedState() { return EngineExecutionState.SUCCEEDED; }
        public List<EngineExecutionRequest> concurrentRequests() { return requests.subList(1, 3); }
        public long validationResourceAcquisitions() { return workspaces.values().stream().mapToLong(InMemoryWorkspaceAccess::openedHandleCount).sum(); }
        public boolean cleanupObserved(UUID id) {
            var workspace = workspaces.get(id);
            return workspace != null && workspace.openedHandleCount() > 0
                    && workspace.openedHandleCount() == workspace.closedHandleCount();
        }
        private List<EngineExecutionRequest> requests() {
            var result = new ArrayList<EngineExecutionRequest>();
            for (long seed = 1; seed <= 3; seed++) {
                InMemoryWorkspaceAccess[] observed = new InMemoryWorkspaceAccess[1];
                var request = KarateTestFixtures.request(seed, KarateTestFixtures.features("features/a.feature"), observed);
                workspaces.put(request.executionId(), observed[0]);
                result.add(request);
            }
            return List.copyOf(result);
        }
    }
}
