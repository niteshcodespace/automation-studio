package com.automationstudio.api.execution.engine.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionEnvironmentSnapshot;
import com.automationstudio.api.execution.ExecutionMetadata;
import com.automationstudio.api.execution.ExecutionRetryPolicy;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSecretReference;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.ExecutionVariable;
import com.automationstudio.api.execution.ExecutionVariableSource;
import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.EngineExecutionState;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionException;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionExecutionContext;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionMetricsAccumulator;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightOrderedScenarioRunner;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightScenarioExecutionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.SelectorResolver;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightBrowser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationException;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationParser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightManifestException;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenario;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifest;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntime;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeException;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeMetrics;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeResult;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeSession;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.secret.ExecutionSecretAccess;
import com.automationstudio.api.execution.secret.ResolvedSecret;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccess;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessException;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessRequest;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class PlaywrightExecutionEngineTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISH = START.plusSeconds(3);
    private static final String REVISION = "0123456789abcdef";

    private final PlaywrightConfigurationParser parser = mock(PlaywrightConfigurationParser.class);
    private final EngineWorkspaceAccessResolver resolver = mock(EngineWorkspaceAccessResolver.class);
    private final PlaywrightScenarioManifestLoader loader = mock(PlaywrightScenarioManifestLoader.class);
    private final PlaywrightRuntime runtime = mock(PlaywrightRuntime.class);
    private final PlaywrightOrderedScenarioRunner runner = mock(PlaywrightOrderedScenarioRunner.class);
    private final SelectorResolver selectors = mock(SelectorResolver.class);
    private final EngineWorkspaceAccess workspace = mock(EngineWorkspaceAccess.class);
    private final PlaywrightRuntimeSession session = mock(PlaywrightRuntimeSession.class);
    private final Clock clock = mock(Clock.class);

    private PlaywrightExecutionConfiguration configuration;
    private EngineExecutionRequest request;
    private PlaywrightScenarioManifest manifest;
    private PlaywrightExecutionEngine engine;

    @BeforeEach
    void setUp() {
        configuration = new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM, true, Duration.ofSeconds(30),
                Duration.ofSeconds(30), 1280, 720, null,
                PlaywrightNavigationPolicy.SAME_ORIGIN);
        request = request(UUID.randomUUID(), UUID.randomUUID(), REVISION, "alpha");
        manifest = manifest("scenario-1", 2, "scenario-2", 1);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(START, FINISH);
        engine = new PlaywrightExecutionEngine(parser, resolver, loader, runtime, runner, selectors, clock);
        stubSuccess(request, workspace, session, manifest);
    }

    @Test
    void exposesExactDescriptorAndValidatesWithoutLifecycleSideEffects() {
        assertThat(engine.descriptor().engineId()).isEqualTo("playwright-java");
        assertThat(engine.descriptor().implementationVersion()).isEqualTo("1.61.0");

        engine.validate(request.context());

        verify(parser).parse(request.context());
        verifyNoInteractions(resolver, loader, runtime, runner, workspace, session);
    }

    @Test
    void validationRejectsExactIdentityVariantsBeforeLifecycle() {
        for (ExecutionContext invalid : List.of(
                context(request, "Playwright-java", "1.61.0", request.context().workspaceId()),
                context(request, "playwright-java", "1.61.1", request.context().workspaceId()))) {
            assertFailure(() -> engine.validate(invalid), "UNSUPPORTED_PLAYWRIGHT_ENGINE",
                    "Execution request does not target the supported Playwright engine");
        }
        assertThatThrownBy(() -> engine.validate(null))
                .isInstanceOf(PlaywrightExecutionException.class)
                .hasMessage("Playwright execution request is invalid");
        verifyNoInteractions(resolver, loader, runtime, runner);
    }

    @Test
    void configurationFailureIsIsolatedBeforeWorkspaceAcquisition() {
        when(parser.parse(any())).thenThrow(new PlaywrightConfigurationException("raw configuration"));

        assertFailure(() -> engine.execute(request), "PLAYWRIGHT_CONFIGURATION_INVALID",
                "Playwright execution configuration is invalid");

        verifyNoInteractions(resolver, loader, runtime, runner);
    }

    @Test
    void catalogWorkspaceMayDifferFromPreparedExecutionWorkspace() {
        assertThat(request.context().workspaceId())
                .isNotEqualTo(request.preparation().workspace().workspaceId().value());

        EngineExecutionResult result = engine.execute(request);

        assertThat(result.workspaceId()).isEqualTo(request.preparation().workspace().workspaceId());
        verify(runtime).open(configuration);
    }

    @Test
    void malformedPreparationIdentityIsRejectedBeforeWorkspaceAcquisition() {
        SourcePreparationResult malformed = mock(SourcePreparationResult.class);
        when(malformed.state()).thenReturn(SourcePreparationState.PREPARED);
        when(malformed.executionId()).thenReturn(request.executionId());
        when(malformed.workspace()).thenReturn(request.preparation().workspace());
        when(malformed.source()).thenReturn(new SourceMaterializationResult(
                new WorkspaceId(UUID.randomUUID()), SourceType.GIT_HTTPS, REVISION,
                SourceMaterializationState.MATERIALIZED,
                request.preparation().preparedAt()));
        assertThatThrownBy(() -> new EngineExecutionRequest(request.context(), malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Prepared workspace and source identities are inconsistent");
        verifyNoInteractions(resolver, loader, runtime, runner, clock);
    }

    @Test
    void malformedPreparedSourceIdentityOrMismatchedRevisionIsRejectedBeforeWorkspaceAcquisition() {
        SourceMaterializationResult missingIdentity = mock(SourceMaterializationResult.class);
        when(missingIdentity.workspaceId())
                .thenReturn(request.preparation().workspace().workspaceId());
        when(missingIdentity.resolvedRevision()).thenReturn(REVISION);
        for (SourceMaterializationResult source : List.of(
                missingIdentity,
                new SourceMaterializationResult(
                        request.preparation().workspace().workspaceId(), SourceType.GIT_HTTPS,
                        "different-revision", SourceMaterializationState.MATERIALIZED,
                        request.preparation().preparedAt()))) {
            SourcePreparationResult malformed = mock(SourcePreparationResult.class);
            when(malformed.state()).thenReturn(SourcePreparationState.PREPARED);
            when(malformed.executionId()).thenReturn(request.executionId());
            when(malformed.workspace()).thenReturn(request.preparation().workspace());
            when(malformed.source()).thenReturn(source);

            assertThatThrownBy(() -> new EngineExecutionRequest(
                            request.context(), malformed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Prepared workspace and source identities are inconsistent");
        }
        verifyNoInteractions(resolver, loader, runtime, runner, clock);
    }

    @Test
    void workspaceAccessMustIdentifyThePreparedPhysicalWorkspace() {
        EngineWorkspaceAccess mismatchedAccess = mock(EngineWorkspaceAccess.class);
        when(mismatchedAccess.workspaceId()).thenReturn(new WorkspaceId(UUID.randomUUID()));
        when(resolver.open(any())).thenReturn(mismatchedAccess);

        assertFailure(() -> engine.execute(request), "INVALID_PLAYWRIGHT_EXECUTION_REQUEST",
                "Playwright execution request is invalid");

        verify(mismatchedAccess).close();
        verifyNoInteractions(loader, runtime, runner);
    }

    @Test
    void successfulLifecycleMapsTrustedIdentityTimingMetricsAndActionContext() {
        EngineExecutionResult result = engine.execute(request);

        assertThat(result.executionId()).isEqualTo(request.executionId());
        assertThat(result.workspaceId()).isEqualTo(request.preparation().workspace().workspaceId());
        assertThat(result.resolvedRevision()).isEqualTo(REVISION);
        assertThat(result.engineName()).isEqualTo("playwright-java");
        assertThat(result.engineVersion()).isEqualTo("1.61.0");
        assertThat(result.state()).isEqualTo(EngineExecutionState.SUCCEEDED);
        assertThat(result.startedAt()).isEqualTo(OffsetDateTime.ofInstant(START, ZoneOffset.UTC));
        assertThat(result.finishedAt()).isEqualTo(OffsetDateTime.ofInstant(FINISH, ZoneOffset.UTC));
        assertThat(result.duration()).isEqualTo(Duration.ofSeconds(3));

        ArgumentCaptor<PlaywrightActionExecutionContext> contexts =
                ArgumentCaptor.forClass(PlaywrightActionExecutionContext.class);
        ArgumentCaptor<PlaywrightActionMetricsAccumulator> metrics =
                ArgumentCaptor.forClass(PlaywrightActionMetricsAccumulator.class);
        verify(runner).execute(eq(manifest.scenarios()), contexts.capture(), metrics.capture());
        PlaywrightActionExecutionContext action = contexts.getValue();
        assertThat(action.scenarioId()).isEqualTo("scenario-1");
        assertThat(action.runtime()).isSameAs(session);
        assertThat(action.configuration()).isSameAs(configuration);
        assertThat(action.selectorResolver()).isSameAs(selectors);
        assertThat(action.interpolator().interpolate("${name}")).isEqualTo("alpha");
        assertThatThrownBy(() -> action.interpolator().interpolate("${secret}"))
                .isInstanceOf(PlaywrightActionException.class);
        assertThat(action.navigationPolicy().resolve("/health").toString())
                .isEqualTo("https://example.test/health");
        assertThat(metrics.getValue().freeze())
                .isEqualTo(new PlaywrightRuntimeMetrics(3, 0, 0, Duration.ZERO, Duration.ofMillis(25)));

        InOrder order = inOrder(parser, resolver, loader, runtime, session, runner, workspace, clock);
        order.verify(parser).parse(request.context());
        order.verify(clock).instant();
        order.verify(resolver).open(any());
        order.verify(loader).load(request.context().suite(), workspace);
        order.verify(runtime).open(configuration);
        order.verify(session).result();
        order.verify(runner).execute(eq(manifest.scenarios()), any(), any());
        order.verify(session).close();
        order.verify(workspace).close();
        order.verify(clock).instant();
    }

    @Test
    void adaptsRequestSecretAccessLazilyOnlyDuringScenarioExecution() {
        ExecutionSecretAccess access = mock(ExecutionSecretAccess.class);
        when(access.executionId()).thenReturn(request.executionId());
        ResolvedSecret resolved = ResolvedSecret.from("controlled-canary".toCharArray());
        when(access.resolve("login.password")).thenReturn(resolved);
        EngineExecutionRequest secretRequest = new EngineExecutionRequest(
                request.context(), request.preparation(), access);
        clearInvocations(access);
        when(runner.execute(any(), any(), any())).thenAnswer(invocation -> {
            verify(access, never()).resolve(any());
            PlaywrightActionExecutionContext context = invocation.getArgument(1);
            try (ResolvedSecret secret = context.sensitiveFillValueResolver()
                    .resolve("login.password")) {
                assertThat(secret).isSameAs(resolved);
            }
            return new PlaywrightScenarioExecutionOutcome(
                    PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED,
                    null,
                    new PlaywrightRuntimeMetrics(
                            3, 3, 0, Duration.ZERO, Duration.ofMillis(25)));
        });

        engine.execute(secretRequest);

        verify(access).resolve("login.password");
        assertThat(resolved.isClosed()).isTrue();
    }

    @Test
    void assertionMismatchReturnsFailedWithoutLeakingTerminalDetails() {
        PlaywrightActionOutcome terminal = PlaywrightActionOutcome.assertionFailed(
                "scenario-2", "step-1", "SECRET_SELECTOR_URL_TEXT");
        when(runner.execute(any(), any(), any())).thenReturn(new PlaywrightScenarioExecutionOutcome(
                PlaywrightScenarioExecutionOutcome.Status.ASSERTION_FAILED, terminal,
                new PlaywrightRuntimeMetrics(3, 2, 1, Duration.ofSeconds(1), Duration.ofMillis(25))));

        EngineExecutionResult result = engine.execute(request);

        assertThat(result.state()).isEqualTo(EngineExecutionState.FAILED);
        assertThat(result.toString()).doesNotContain("SECRET_SELECTOR_URL_TEXT", "scenario-2", "step-1");
        InOrder cleanup = inOrder(session, workspace);
        cleanup.verify(session).close();
        cleanup.verify(workspace).close();
    }

    @Test
    void manifestFailureClosesOnlyWorkspace() {
        when(loader.load(any(), eq(workspace))).thenThrow(new PlaywrightManifestException("RAW", "raw path"));

        assertFailure(() -> engine.execute(request), "PLAYWRIGHT_MANIFEST_LOAD_FAILED",
                "Playwright scenario manifest could not be loaded");
        verify(workspace).close();
        verifyNoInteractions(runtime, runner, session);
    }

    @Test
    void runtimeOpenFailureClosesWorkspaceAndNeverRuns() {
        when(runtime.open(configuration)).thenThrow(new PlaywrightRuntimeException("RAW", "raw runtime"));

        assertFailure(() -> engine.execute(request), "PLAYWRIGHT_RUNTIME_START_FAILED",
                "Playwright runtime could not be started");
        verify(workspace).close();
        verifyNoInteractions(runner, session);
    }

    @Test
    void runtimeCleanupFailureAfterSuccessPreventsResultAndStillClosesWorkspace() {
        doThrow(new PlaywrightRuntimeException("RAW", "runtime secret")).when(session).close();

        assertFailure(() -> engine.execute(request), "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED",
                "Playwright runtime cleanup failed");
        verify(workspace).close();
        verify(clock, times(1)).instant();
    }

    @Test
    void workspaceCleanupFailureAfterAssertionPreventsFailedResult() {
        when(runner.execute(any(), any(), any())).thenReturn(assertionOutcome());
        doThrow(new EngineWorkspaceAccessException("RAW", "workspace secret")).when(workspace).close();

        assertFailure(() -> engine.execute(request), "PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED",
                "Playwright workspace access cleanup failed");
        verify(session).close();
        verify(clock, times(1)).instant();
    }

    @Test
    void runnerAndBothCleanupFailuresHaveDeterministicSanitizedAcyclicChain() {
        when(runner.execute(any(), any(), any()))
                .thenThrow(new PlaywrightActionException("RAW", "runner secret"));
        doThrow(new PlaywrightRuntimeException("RAW", "runtime secret")).when(session).close();
        doThrow(new EngineWorkspaceAccessException("RAW", "workspace secret")).when(workspace).close();

        PlaywrightExecutionException thrown = capture(() -> engine.execute(request));

        assertThat(thrown.code()).isEqualTo("PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED");
        assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(PlaywrightExecutionException.class);
        PlaywrightExecutionException runtimeFailure = (PlaywrightExecutionException) thrown.getSuppressed()[0];
        assertThat(runtimeFailure.code()).isEqualTo("PLAYWRIGHT_RUNTIME_CLEANUP_FAILED");
        PlaywrightExecutionException executionFailure =
                (PlaywrightExecutionException) runtimeFailure.getSuppressed()[0];
        assertThat(executionFailure.code()).isEqualTo("PLAYWRIGHT_ACTION_EXECUTION_FAILED");
        assertThat(List.of(thrown, runtimeFailure, executionFailure)).doesNotHaveDuplicates();
        assertThat(thrown.toString() + runtimeFailure + executionFailure)
                .doesNotContain("runner secret", "runtime secret", "workspace secret");
        InOrder cleanup = inOrder(session, workspace);
        cleanup.verify(session).close();
        cleanup.verify(workspace).close();
    }

    @Test
    void runnerFailureAndWorkspaceCleanupFailureKeepSanitizedDeterministicPrecedence() {
        PlaywrightActionException runnerFailure =
                new PlaywrightActionException("RAW_RUNNER", "runner secret selector and URL");
        EngineWorkspaceAccessException workspaceFailure =
                new EngineWorkspaceAccessException("RAW_WORKSPACE_CLOSE", "workspace secret path");
        when(runner.execute(any(), any(), any())).thenThrow(runnerFailure);
        doThrow(workspaceFailure).when(workspace).close();

        PlaywrightExecutionException thrown = capture(() -> engine.execute(request));

        assertThat(thrown.code()).isEqualTo("PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED");
        assertThat(thrown).hasMessage("Playwright workspace access cleanup failed")
                .hasCause(workspaceFailure);
        assertThat(thrown.getMessage()).doesNotContain("workspace secret path");
        assertThat(thrown.getSuppressed()).hasSize(1);
        PlaywrightExecutionException suppressed =
                (PlaywrightExecutionException) thrown.getSuppressed()[0];
        assertThat(suppressed.code()).isEqualTo("PLAYWRIGHT_ACTION_EXECUTION_FAILED");
        assertThat(suppressed).hasMessage("Playwright action execution failed")
                .hasCause(runnerFailure);
        assertThat(suppressed.getMessage()).doesNotContain("runner secret selector and URL");
        assertThat(suppressed.getSuppressed()).isEmpty();
        assertThat(List.of(thrown, suppressed, workspaceFailure, runnerFailure)).doesNotHaveDuplicates();
        assertThat(thrown.getCause()).isNotSameAs(thrown).isNotSameAs(suppressed);
        assertThat(suppressed.getCause()).isNotSameAs(thrown).isNotSameAs(suppressed);
        verify(runner, times(1)).execute(any(), any(), any());
        InOrder cleanup = inOrder(session, workspace);
        cleanup.verify(session, times(1)).close();
        cleanup.verify(workspace, times(1)).close();
    }

    @Test
    void separateExecutionsReceiveIsolatedContextsMetricsAndResources() throws Exception {
        UUID execution2 = UUID.randomUUID();
        EngineExecutionRequest request2 = request(execution2, UUID.randomUUID(), "fedcba9876543210", "beta");
        EngineWorkspaceAccess workspace2 = mock(EngineWorkspaceAccess.class);
        PlaywrightRuntimeSession session2 = mock(PlaywrightRuntimeSession.class);
        PlaywrightScenarioManifest manifest2 = manifest("scenario-b", 1, "last-scenario-b", 3);
        PlaywrightExecutionConfiguration configuration2 = new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM, true, Duration.ofSeconds(30), Duration.ofSeconds(30),
                1280, 720, "en-US", PlaywrightNavigationPolicy.SAME_ORIGIN);
        when(parser.parse(request.context())).thenReturn(configuration);
        when(parser.parse(request2.context())).thenReturn(configuration2);
        when(workspace2.workspaceId())
                .thenReturn(request2.preparation().workspace().workspaceId());
        when(resolver.open(any())).thenAnswer(invocation -> {
            EngineWorkspaceAccessRequest accessRequest = invocation.getArgument(0);
            return accessRequest.executionId().equals(execution2) ? workspace2 : workspace;
        });
        when(loader.load(eq(request2.context().suite()), eq(workspace2))).thenReturn(manifest2);
        when(runtime.open(configuration)).thenReturn(session);
        when(runtime.open(configuration2)).thenReturn(session2);
        when(session2.result()).thenReturn(new PlaywrightRuntimeResult(
                PlaywrightRuntimeMetrics.startup(Duration.ofMillis(40))));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(runner.execute(any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            List<PlaywrightScenario> scenarios = invocation.getArgument(0);
            long actions = scenarios.stream().mapToLong(s -> s.steps().size()).sum();
            Duration startup = ((PlaywrightActionMetricsAccumulator) invocation.getArgument(2))
                    .freeze().browserStartupDuration();
            return successOutcome(actions, startup);
        });
        when(clock.instant()).thenReturn(START, START, FINISH, FINISH);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<EngineExecutionResult> first = pool.submit(() -> engine.execute(request));
            Future<EngineExecutionResult> second = pool.submit(() -> engine.execute(request2));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            EngineExecutionResult resultA = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            EngineExecutionResult resultB = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertResultIdentity(resultA, request, REVISION);
            assertResultIdentity(resultB, request2, "fedcba9876543210");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        ArgumentCaptor<PlaywrightActionExecutionContext> contexts =
                ArgumentCaptor.forClass(PlaywrightActionExecutionContext.class);
        ArgumentCaptor<PlaywrightActionMetricsAccumulator> metrics =
                ArgumentCaptor.forClass(PlaywrightActionMetricsAccumulator.class);
        ArgumentCaptor<List<PlaywrightScenario>> scenarios = ArgumentCaptor.forClass(List.class);
        verify(runner, times(2)).execute(scenarios.capture(), contexts.capture(), metrics.capture());
        List<RunnerInvocation> invocations = java.util.stream.IntStream.range(0, 2)
                .mapToObj(i -> new RunnerInvocation(
                        scenarios.getAllValues().get(i), contexts.getAllValues().get(i),
                        metrics.getAllValues().get(i)))
                .toList();
        RunnerInvocation tupleA = invocationFor(invocations, "scenario-1");
        RunnerInvocation tupleB = invocationFor(invocations, "scenario-b");
        assertInvocationTuple(tupleA, "scenario-1", "alpha", "beta", session,
                configuration, Duration.ofMillis(25), 3);
        assertInvocationTuple(tupleB, "scenario-b", "beta", "alpha", session2,
                configuration2, Duration.ofMillis(40), 4);
        assertThat(tupleA.metrics()).isNotSameAs(tupleB.metrics());
        assertThat(tupleA.context().runtime()).isNotSameAs(tupleB.context().runtime());
        assertThat(workspace).isNotSameAs(workspace2);
        InOrder cleanupA = inOrder(session, workspace);
        cleanupA.verify(session, times(1)).close();
        cleanupA.verify(workspace, times(1)).close();
        InOrder cleanupB = inOrder(session2, workspace2);
        cleanupB.verify(session2, times(1)).close();
        cleanupB.verify(workspace2, times(1)).close();
    }

    private RunnerInvocation invocationFor(List<RunnerInvocation> invocations, String scenarioId) {
        return invocations.stream()
                .filter(invocation -> invocation.scenarios().get(0).id().equals(scenarioId))
                .findFirst()
                .orElseThrow();
    }

    private void assertInvocationTuple(RunnerInvocation invocation, String scenarioId,
            String expectedVariable, String otherVariable, PlaywrightRuntimeSession expectedSession,
            PlaywrightExecutionConfiguration expectedConfiguration, Duration startup, long actions) {
        assertThat(invocation.scenarios().get(0).id()).isEqualTo(scenarioId);
        assertThat(invocation.context().scenarioId()).isEqualTo(scenarioId);
        assertThat(invocation.context().runtime()).isSameAs(expectedSession);
        assertThat(invocation.context().configuration()).isSameAs(expectedConfiguration);
        assertThat(invocation.context().selectorResolver()).isSameAs(selectors);
        assertThat(invocation.context().navigationPolicy().resolve("/tuple").toString())
                .isEqualTo("https://example.test/tuple");
        assertThat(invocation.context().interpolator().interpolate("${name}"))
                .isEqualTo(expectedVariable).isNotEqualTo(otherVariable);
        assertThat(invocation.metrics().freeze())
                .isEqualTo(new PlaywrightRuntimeMetrics(actions, 0, 0, Duration.ZERO, startup));
    }

    private void assertResultIdentity(EngineExecutionResult result, EngineExecutionRequest expected,
            String revision) {
        assertThat(result.executionId()).isEqualTo(expected.executionId());
        assertThat(result.workspaceId()).isEqualTo(expected.preparation().workspace().workspaceId());
        assertThat(result.resolvedRevision()).isEqualTo(revision);
        assertThat(result.engineName()).isEqualTo("playwright-java");
        assertThat(result.engineVersion()).isEqualTo("1.61.0");
        assertThat(result.state()).isEqualTo(EngineExecutionState.SUCCEEDED);
    }

    private record RunnerInvocation(
            List<PlaywrightScenario> scenarios,
            PlaywrightActionExecutionContext context,
            PlaywrightActionMetricsAccumulator metrics) {}

    private void stubSuccess(EngineExecutionRequest current, EngineWorkspaceAccess access,
            PlaywrightRuntimeSession currentSession, PlaywrightScenarioManifest currentManifest) {
        when(parser.parse(any())).thenReturn(configuration);
        when(resolver.open(any())).thenReturn(access);
        when(access.workspaceId()).thenReturn(current.preparation().workspace().workspaceId());
        when(loader.load(eq(current.context().suite()), eq(access))).thenReturn(currentManifest);
        when(runtime.open(configuration)).thenReturn(currentSession);
        when(currentSession.result()).thenReturn(new PlaywrightRuntimeResult(
                PlaywrightRuntimeMetrics.startup(Duration.ofMillis(25))));
        when(runner.execute(any(), any(), any())).thenReturn(successOutcome(3, Duration.ofMillis(25)));
    }

    private PlaywrightScenarioExecutionOutcome successOutcome(long actions, Duration startup) {
        return new PlaywrightScenarioExecutionOutcome(
                PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED, null,
                new PlaywrightRuntimeMetrics(actions, actions, 0, Duration.ofSeconds(1), startup));
    }

    private PlaywrightScenarioExecutionOutcome assertionOutcome() {
        return new PlaywrightScenarioExecutionOutcome(
                PlaywrightScenarioExecutionOutcome.Status.ASSERTION_FAILED,
                PlaywrightActionOutcome.assertionFailed("scenario-1", "step-1", "ASSERTION_MISMATCH"),
                new PlaywrightRuntimeMetrics(3, 2, 1, Duration.ofSeconds(1), Duration.ofMillis(25)));
    }

    private PlaywrightScenarioManifest manifest(String firstId, int firstSteps, String secondId, int secondSteps) {
        return new PlaywrightScenarioManifest("1.0", "Manifest", List.of(
                scenario(firstId, firstSteps), scenario(secondId, secondSteps)));
    }

    private PlaywrightScenario scenario(String id, int count) {
        return new PlaywrightScenario(id, id, java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new PlaywrightStep("step-" + i, PlaywrightActionType.NAVIGATE,
                        null, "/" + i, null, null, null)).toList());
    }

    private EngineExecutionRequest request(UUID executionId, UUID workspaceUuid,
            String revision, String variableValue) {
        WorkspaceId workspaceId = new WorkspaceId(workspaceUuid);
        OffsetDateTime now = OffsetDateTime.ofInstant(START, ZoneOffset.UTC);
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS, "repository", revision, null);
        WorkspaceDescriptor descriptor = WorkspaceDescriptor.planned(workspaceId, executionId,
                        new WorkspaceProviderId("local-filesystem"))
                .transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(WorkspaceState.READY, new WorkspaceMetadata(now, source));
        SourcePreparationResult preparation = new SourcePreparationResult(descriptor,
                new SourceMaterializationResult(workspaceId, SourceType.GIT_HTTPS, revision,
                        SourceMaterializationState.MATERIALIZED, now),
                SourcePreparationState.PREPARED, now);
        ExecutionContext context = baseContext(executionId, UUID.randomUUID(),
                PlaywrightEngineDescriptor.ENGINE_NAME, PlaywrightEngineDescriptor.ENGINE_VERSION,
                variableValue);
        return new EngineExecutionRequest(context, preparation);
    }

    private ExecutionContext context(EngineExecutionRequest original, String engineName,
            String engineVersion, UUID workspaceId) {
        return baseContext(original.executionId(), workspaceId, engineName, engineVersion, "alpha");
    }

    private ExecutionContext baseContext(UUID executionId, UUID workspaceId, String engineName,
            String engineVersion, String variableValue) {
        OffsetDateTime now = OffsetDateTime.ofInstant(START, ZoneOffset.UTC);
        return new ExecutionContext(executionId, UUID.randomUUID(), workspaceId,
                new ExecutionSuiteSnapshot(UUID.randomUUID(), "Suite", engineName, engineVersion,
                        "BROWSER", null, "scenario.json", Map.of(), Map.of()),
                new ExecutionEnvironmentSnapshot(UUID.randomUUID(), "QA", "TEST",
                        "https://example.test", Map.of(), Map.of()),
                List.of(new ExecutionSecretReference("secret", Map.of("provider", "vault"))),
                Map.of("name", new ExecutionVariable("name", variableValue, ExecutionVariableSource.EXECUTION),
                        "ignored", new ExecutionVariable("ignored", 42, ExecutionVariableSource.EXECUTION)),
                new ExecutionRunnerContext(UUID.randomUUID(), "runner", "1", "windows", "amd64",
                        Map.of(), Map.of()),
                new ExecutionMetadata(UUID.randomUUID(), now, now, Duration.ofMinutes(5),
                        ExecutionRetryPolicy.DISABLED));
    }

    private void assertFailure(ThrowingCall call, String code, String message) {
        PlaywrightExecutionException failure = capture(call);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure).hasMessage(message);
    }

    private PlaywrightExecutionException capture(ThrowingCall call) {
        final PlaywrightExecutionException[] captured = new PlaywrightExecutionException[1];
        assertThatThrownBy(call::run).isInstanceOf(PlaywrightExecutionException.class)
                .satisfies(t -> captured[0] = (PlaywrightExecutionException) t);
        return captured[0];
    }

    @FunctionalInterface
    private interface ThrowingCall { void run() throws Exception; }
}
