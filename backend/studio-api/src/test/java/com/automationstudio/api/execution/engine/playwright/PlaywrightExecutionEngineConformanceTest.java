package com.automationstudio.api.execution.engine.playwright;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.engine.playwright.action.PlaywrightOrderedScenarioRunner;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightScenarioExecutionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.SelectorResolver;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationParser;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntime;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeMetrics;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeResult;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeSession;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

class PlaywrightExecutionEngineConformanceTest
        implements ExecutionEnginePluginConformanceContract {

    private final PlaywrightFixture fixture = new PlaywrightFixture();

    @Override
    public ExecutionEnginePluginFixture fixture() {
        return fixture;
    }

    private static final class PlaywrightFixture implements ExecutionEnginePluginFixture {
        private static final byte[] MANIFEST = ("""
                {"schemaVersion":"2.0","name":"Conformance","scenarios":[
                  {"id":"scenario","name":"Scenario","steps":[
                    {"id":"fill","action":"fill","selector":"#user","secretRef":"token"}
                  ]}
                ]}
                """).getBytes(StandardCharsets.UTF_8);

        private final Map<UUID, InMemoryWorkspaceAccess> workspaces = new ConcurrentHashMap<>();
        private final AtomicInteger runtimeSessionsClosed = new AtomicInteger();
        private final List<EngineExecutionRequest> requests = List.of(
                request(1), request(2), request(3));
        private final ExecutionEnginePlugin plugin = createPlugin();

        @Override public ExecutionEnginePlugin plugin() { return plugin; }
        @Override public EngineExecutionRequest validRequest() { return requests.getFirst(); }
        @Override public EngineExecutionState expectedState() { return EngineExecutionState.SUCCEEDED; }
        @Override public List<EngineExecutionRequest> concurrentRequests() {
            return requests.subList(1, 3);
        }
        @Override public long validationResourceAcquisitions() {
            return workspaces.values().stream()
                    .mapToLong(InMemoryWorkspaceAccess::openedHandleCount).sum();
        }
        @Override public boolean cleanupObserved(UUID executionId) {
            InMemoryWorkspaceAccess workspace = workspaces.get(executionId);
            long cleanedWorkspaces = workspaces.values().stream()
                    .filter(value -> value.closedHandleCount() > 0).count();
            return workspace != null && workspace.openedHandleCount() > 0
                    && workspace.openedHandleCount() == workspace.closedHandleCount()
                    && runtimeSessionsClosed.get() >= cleanedWorkspaces;
        }

        private ExecutionEnginePlugin createPlugin() {
            PlaywrightRuntime runtime = mock(PlaywrightRuntime.class);
            when(runtime.open(any())).thenAnswer(ignored -> runtimeSession());
            PlaywrightOrderedScenarioRunner runner = mock(PlaywrightOrderedScenarioRunner.class);
            when(runner.execute(any(), any(), any())).thenAnswer(invocation -> {
                var context = (com.automationstudio.api.execution.engine.playwright.action
                        .PlaywrightActionExecutionContext) invocation.getArgument(1);
                try (var secret = context.sensitiveFillValueResolver().resolve("token")) {
                    secret.withValue(value -> {
                        if (value.length == 0) throw new AssertionError("Secret was empty");
                    });
                }
                return new PlaywrightScenarioExecutionOutcome(
                        PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED, null,
                        new PlaywrightRuntimeMetrics(1, 1, 0,
                                Duration.ofMillis(1), Duration.ofMillis(1)));
            });
            return new PlaywrightExecutionEngine(
                    new PlaywrightConfigurationParser(new SensitiveKeyDetector()),
                    mock(EngineWorkspaceAccessResolver.class),
                    new PlaywrightScenarioManifestLoader(), runtime, runner,
                    mock(SelectorResolver.class),
                    Clock.fixed(Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC));
        }

        private PlaywrightRuntimeSession runtimeSession() {
            PlaywrightRuntimeSession session = mock(PlaywrightRuntimeSession.class);
            when(session.result()).thenReturn(new PlaywrightRuntimeResult(
                    PlaywrightRuntimeMetrics.startup(Duration.ofMillis(1))));
            org.mockito.Mockito.doAnswer(ignored -> {
                runtimeSessionsClosed.incrementAndGet();
                return null;
            }).when(session).close();
            return session;
        }

        private EngineExecutionRequest request(long seed) {
            UUID executionId = new UUID(27, seed);
            WorkspaceId workspaceId = new WorkspaceId(new UUID(271, seed));
            var context = new EngineExecutionContext(executionId,
                    new EngineIdentity(PlaywrightEngineDescriptor.ENGINE_ID,
                            PlaywrightEngineDescriptor.IMPLEMENTATION_VERSION),
                    "scenario.json", Map.of(), "https://example.invalid", Map.of(),
                    Map.of("name", "conformance"));
            var workspace = new InMemoryWorkspaceAccess(executionId, workspaceId,
                    Map.of("scenario.json", MANIFEST));
            workspaces.put(executionId, workspace);
            return new EngineExecutionRequest(context,
                    new PreparedSource(workspaceId, "GIT_HTTPS", "revision-" + seed), workspace,
                    new InMemoryExecutionSecretAccess(executionId,
                            Map.of("token", new char[] {'s', 'e', 'c', 'r', 'e', 't'})));
        }
    }
}
