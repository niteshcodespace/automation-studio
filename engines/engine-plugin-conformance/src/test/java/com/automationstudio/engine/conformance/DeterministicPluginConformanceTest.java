package com.automationstudio.engine.conformance;

import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class DeterministicPluginConformanceTest implements ExecutionEnginePluginConformanceContract {
    private final TestFixture fixture = new TestFixture();

    @Override public ExecutionEnginePluginFixture fixture() { return fixture; }

    private static final class TestFixture implements ExecutionEnginePluginFixture {
        private final Set<UUID> cleaned = ConcurrentHashMap.newKeySet();
        private final ExecutionEnginePlugin plugin = new DeterministicPlugin(cleaned);
        private final List<EngineExecutionRequest> requests = List.of(request(1), request(2), request(3));

        @Override public ExecutionEnginePlugin plugin() { return plugin; }
        @Override public EngineExecutionRequest validRequest() { return requests.getFirst(); }
        @Override public EngineExecutionState expectedState() { return EngineExecutionState.SUCCEEDED; }
        @Override public List<EngineExecutionRequest> concurrentRequests() { return requests.subList(1, 3); }
        @Override public long validationResourceAcquisitions() {
            return requests.stream().map(EngineExecutionRequest::workspaceAccess)
                    .map(InMemoryWorkspaceAccess.class::cast)
                    .mapToLong(InMemoryWorkspaceAccess::openedHandleCount).sum();
        }
        @Override public boolean cleanupObserved(UUID executionId) { return cleaned.contains(executionId); }

        private static EngineExecutionRequest request(int seed) {
            UUID executionId = new UUID(0, seed);
            WorkspaceId workspaceId = new WorkspaceId(new UUID(1, seed));
            var context = new EngineExecutionContext(executionId,
                    new EngineIdentity("fixture", "1.0.0"), "suite", Map.of(),
                    "https://example.invalid", Map.of(), Map.of());
            var source = new PreparedSource(workspaceId, "fixture", "revision-" + seed);
            var workspace = new InMemoryWorkspaceAccess(executionId, workspaceId,
                    Map.of("scenario.txt", "ok".getBytes(StandardCharsets.UTF_8)));
            var secrets = new InMemoryExecutionSecretAccess(executionId,
                    Map.of("token", new char[] {'s', 'e', 'c', 'r', 'e', 't'}));
            return new EngineExecutionRequest(context, source, workspace, secrets);
        }
    }

    private record DeterministicPlugin(Set<UUID> cleaned) implements ExecutionEnginePlugin {
        private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
                "fixture", "1.0.0", "Deterministic fixture", Set.of("workspace", "secrets"),
                Set.of("concurrency"));

        @Override public ExecutionEngineDescriptor descriptor() { return DESCRIPTOR; }
        @Override public void validate(EngineExecutionContext context) {
            if (!context.engineIdentity().equals(DESCRIPTOR.identity())) {
                throw new IllegalArgumentException("Unexpected engine identity");
            }
        }

        @Override
        public EngineExecutionResult execute(EngineExecutionRequest request) {
            request.validateFor(DESCRIPTOR);
            OffsetDateTime started = OffsetDateTime.parse("2026-01-01T00:00:00Z");
            try (var source = request.workspaceAccess().openPreparedSource();
                    var scenario = source.open("scenario.txt");
                    var secret = request.secretAccess().resolve("token")) {
                scenario.readAllBytes();
                secret.withValue(value -> { if (value.length == 0) throw new AssertionError(); });
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            } finally {
                cleaned.add(request.executionId());
            }
            return new EngineExecutionResult(request.executionId(), DESCRIPTOR.engineId(),
                    DESCRIPTOR.implementationVersion(), request.preparedSource().workspaceId(),
                    request.preparedSource().resolvedRevision(), EngineExecutionState.SUCCEEDED,
                    started, started.plusSeconds(1), Duration.ofSeconds(1));
        }
    }
}
