package com.automationstudio.api.execution.engine.builtin;

import com.automationstudio.api.security.SensitiveKeyDetector;
import com.automationstudio.engine.conformance.ExecutionEnginePluginConformanceContract;
import com.automationstudio.engine.conformance.ExecutionEnginePluginFixture;
import com.automationstudio.engine.conformance.InMemoryExecutionSecretAccess;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class BuiltinExecutionEngineConformanceTest
        implements ExecutionEnginePluginConformanceContract {

    private final BuiltinFixture fixture = new BuiltinFixture();

    @Override
    public ExecutionEnginePluginFixture fixture() {
        return fixture;
    }

    private static final class BuiltinFixture implements ExecutionEnginePluginFixture {
        private final ExecutionEnginePlugin plugin = new BuiltinExecutionEngine(
                new BuiltinExecutionEngineConfiguration(new SensitiveKeyDetector()),
                Clock.fixed(Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC));
        private final List<EngineExecutionRequest> requests = List.of(
                request(1), request(2), request(3));

        @Override public ExecutionEnginePlugin plugin() { return plugin; }
        @Override public EngineExecutionRequest validRequest() { return requests.getFirst(); }
        @Override public EngineExecutionState expectedState() { return EngineExecutionState.SUCCEEDED; }
        @Override public List<EngineExecutionRequest> concurrentRequests() {
            return requests.subList(1, 3);
        }
        @Override public long validationResourceAcquisitions() { return 0; }
        @Override public boolean cleanupObserved(UUID executionId) {
            return requests.stream().anyMatch(request -> request.executionId().equals(executionId));
        }

        private static EngineExecutionRequest request(long seed) {
            UUID executionId = new UUID(27, seed);
            WorkspaceId workspaceId = new WorkspaceId(new UUID(270, seed));
            var context = new EngineExecutionContext(executionId,
                    new EngineIdentity(BuiltinExecutionEngine.ENGINE_ID,
                            BuiltinExecutionEngine.IMPLEMENTATION_VERSION),
                    "builtin-suite", Map.of("operation", "SUCCEED"),
                    "https://example.invalid", Map.of(), Map.of());
            return new EngineExecutionRequest(context,
                    new PreparedSource(workspaceId, "GIT_HTTPS", "revision-" + seed),
                    new InMemoryWorkspaceAccess(executionId, workspaceId, Map.of()),
                    new InMemoryExecutionSecretAccess(executionId, Map.of()));
        }
    }
}
