package com.automationstudio.engine.sample;

import com.automationstudio.engine.conformance.ExecutionEnginePluginConformanceContract;
import com.automationstudio.engine.conformance.ExecutionEnginePluginFixture;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.ResolvedSecret;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

class SampleExecutionEngineConformanceTest
        implements ExecutionEnginePluginConformanceContract {

    private final SampleFixture fixture = new SampleFixture();

    @Override
    public ExecutionEnginePluginFixture fixture() {
        return fixture;
    }

    private static final class SampleFixture implements ExecutionEnginePluginFixture {
        private final Map<UUID, InMemoryWorkspaceAccess> workspaces = new ConcurrentHashMap<>();
        private final Map<UUID, TrackingSecretAccess> secrets = new ConcurrentHashMap<>();
        private final ExecutionEnginePlugin plugin = new SampleExecutionEnginePlugin(Clock.fixed(
                Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        private final List<EngineExecutionRequest> requests = List.of(
                request(1), request(2), request(3));

        @Override public ExecutionEnginePlugin plugin() { return plugin; }
        @Override public EngineExecutionRequest validRequest() { return requests.getFirst(); }
        @Override public EngineExecutionState expectedState() { return EngineExecutionState.SUCCEEDED; }
        @Override public List<EngineExecutionRequest> concurrentRequests() {
            return requests.subList(1, 3);
        }
        @Override public long validationResourceAcquisitions() {
            return workspaces.values().stream().mapToLong(InMemoryWorkspaceAccess::openedHandleCount).sum()
                    + secrets.values().stream().mapToLong(TrackingSecretAccess::resolutionCount).sum();
        }
        @Override public boolean cleanupObserved(UUID executionId) {
            InMemoryWorkspaceAccess workspace = workspaces.get(executionId);
            TrackingSecretAccess secret = secrets.get(executionId);
            return workspace != null && workspace.openedHandleCount() > 0
                    && workspace.openedHandleCount() == workspace.closedHandleCount()
                    && secret != null && secret.closed();
        }

        private EngineExecutionRequest request(long seed) {
            UUID executionId = new UUID(27, seed);
            WorkspaceId workspaceId = new WorkspaceId(new UUID(272, seed));
            var context = new EngineExecutionContext(executionId,
                    new EngineIdentity(SampleExecutionEnginePlugin.ENGINE_ID,
                            SampleExecutionEnginePlugin.IMPLEMENTATION_VERSION),
                    SampleExecutionEnginePlugin.SOURCE_FILE,
                    Map.of("mode", "DETERMINISTIC"), "https://example.invalid", Map.of(), Map.of());
            var workspace = new InMemoryWorkspaceAccess(executionId, workspaceId, Map.of(
                    SampleExecutionEnginePlugin.SOURCE_FILE,
                    "ready".getBytes(StandardCharsets.UTF_8)));
            var secret = new TrackingSecretAccess(executionId);
            workspaces.put(executionId, workspace);
            secrets.put(executionId, secret);
            return new EngineExecutionRequest(context,
                    new PreparedSource(workspaceId, "GIT_HTTPS", "revision-" + seed),
                    workspace, secret);
        }
    }

    private static final class TrackingSecretAccess implements ExecutionSecretAccess {
        private final UUID executionId;
        private final AtomicInteger resolutions = new AtomicInteger();
        private volatile ResolvedSecret resolved;

        private TrackingSecretAccess(UUID executionId) { this.executionId = executionId; }
        @Override public UUID executionId() { return executionId; }
        @Override public ResolvedSecret resolve(String logicalName) {
            if (!SampleExecutionEnginePlugin.SECRET_NAME.equals(logicalName)) {
                throw new IllegalArgumentException("Unexpected logical secret name");
            }
            resolutions.incrementAndGet();
            resolved = ResolvedSecret.from(new char[] {'s', 'a', 'm', 'p', 'l', 'e'});
            return resolved;
        }
        private int resolutionCount() { return resolutions.get(); }
        private boolean closed() { return resolved != null && resolved.isClosed(); }
    }
}
