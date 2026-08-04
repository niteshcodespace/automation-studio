package com.automationstudio.api.execution.engine.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionEnvironmentSnapshot;
import com.automationstudio.api.execution.ExecutionMetadata;
import com.automationstudio.api.execution.ExecutionRetryPolicy;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionException;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionMetricsAccumulator;
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
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccess;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessException;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaywrightExecutionEngineSanitizationTest {

    private static final String SENSITIVE =
            "C:\\secret\\workspace https://user:password@example.test "
                    + "selector=#payment-card token=abc123 raw-playwright-diagnostic";
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String REVISION = "0123456789abcdef";

    private final PlaywrightConfigurationParser configurationParser =
            mock(PlaywrightConfigurationParser.class);
    private final EngineWorkspaceAccessResolver workspaceResolver =
            mock(EngineWorkspaceAccessResolver.class);
    private final PlaywrightScenarioManifestLoader manifestLoader =
            mock(PlaywrightScenarioManifestLoader.class);
    private final PlaywrightRuntime runtime = mock(PlaywrightRuntime.class);
    private final PlaywrightOrderedScenarioRunner runner =
            mock(PlaywrightOrderedScenarioRunner.class);
    private final SelectorResolver selectorResolver = mock(SelectorResolver.class);
    private final EngineWorkspaceAccess workspaceAccess = mock(EngineWorkspaceAccess.class);
    private final PlaywrightRuntimeSession session = mock(PlaywrightRuntimeSession.class);

    private PlaywrightExecutionEngine engine;
    private EngineExecutionRequest request;
    private PlaywrightExecutionConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM,
                true,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                1280,
                720,
                null,
                PlaywrightNavigationPolicy.SAME_ORIGIN);
        request = request();
        engine = new PlaywrightExecutionEngine(
                configurationParser,
                workspaceResolver,
                manifestLoader,
                runtime,
                runner,
                selectorResolver,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        stubSuccess();
    }

    @Test
    void sanitizesConfigurationFailure() {
        PlaywrightConfigurationException raw = new PlaywrightConfigurationException(SENSITIVE);
        when(configurationParser.parse(any())).thenThrow(raw);

        assertSanitized(
                () -> engine.validate(request.context()),
                "PLAYWRIGHT_CONFIGURATION_INVALID",
                "Playwright execution configuration is invalid",
                raw);
    }

    @Test
    void sanitizesWorkspaceAcquisitionFailure() {
        EngineWorkspaceAccessException raw =
                new EngineWorkspaceAccessException("RAW_WORKSPACE", SENSITIVE);
        when(workspaceResolver.open(any())).thenThrow(raw);

        assertSanitized(
                () -> engine.execute(request),
                "PLAYWRIGHT_WORKSPACE_ACCESS_FAILED",
                "Playwright execution workspace is unavailable",
                raw);
    }

    @Test
    void sanitizesWorkspaceUsageFailureWithoutManifestMisclassification() {
        EngineWorkspaceAccessException raw =
                new EngineWorkspaceAccessException("RAW_WORKSPACE_USAGE", SENSITIVE);
        when(workspaceAccess.isOpen()).thenReturn(true);
        when(workspaceAccess.sourceDirectory()).thenThrow(raw);
        PlaywrightExecutionEngine loaderBackedEngine = new PlaywrightExecutionEngine(
                configurationParser,
                workspaceResolver,
                new PlaywrightScenarioManifestLoader(),
                runtime,
                runner,
                selectorResolver,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        PlaywrightExecutionException thrown =
                catchEngineFailure(() -> loaderBackedEngine.execute(request));

        assertFailure(
                thrown,
                "PLAYWRIGHT_WORKSPACE_ACCESS_FAILED",
                "Playwright execution workspace is unavailable",
                raw);
        assertThat(thrown.code()).isNotEqualTo("PLAYWRIGHT_MANIFEST_LOAD_FAILED");
        assertThat(thrown.getSuppressed()).isEmpty();
        verify(workspaceAccess).close();
        verifyNoInteractions(runtime);
        verifyNoInteractions(runner);
    }

    @Test
    void sanitizesManifestLoadingFailure() {
        PlaywrightManifestException raw = new PlaywrightManifestException("RAW_MANIFEST", SENSITIVE);
        when(manifestLoader.load(any(), eq(workspaceAccess))).thenThrow(raw);

        assertSanitized(
                () -> engine.execute(request),
                "PLAYWRIGHT_MANIFEST_LOAD_FAILED",
                "Playwright scenario manifest could not be loaded",
                raw);
    }

    @Test
    void sanitizesRuntimeOpeningFailure() {
        PlaywrightRuntimeException raw = new PlaywrightRuntimeException("RAW_RUNTIME", SENSITIVE);
        when(runtime.open(configuration)).thenThrow(raw);

        assertSanitized(
                () -> engine.execute(request),
                "PLAYWRIGHT_RUNTIME_START_FAILED",
                "Playwright runtime could not be started",
                raw);
    }

    @Test
    void sanitizesPostStartRuntimeFailureAsRuntimeExecutionFailure() {
        PlaywrightRuntimeException raw =
                new PlaywrightRuntimeException("RAW_RUNTIME_RESULT", SENSITIVE);
        when(session.result()).thenThrow(raw);

        PlaywrightExecutionException thrown = catchEngineFailure(() -> engine.execute(request));

        assertFailure(
                thrown,
                "PLAYWRIGHT_RUNTIME_EXECUTION_FAILED",
                "Playwright runtime execution failed",
                raw);
        assertThat(thrown.code()).isNotEqualTo("PLAYWRIGHT_RUNTIME_START_FAILED");
        assertThat(thrown.getSuppressed()).isEmpty();
        InOrder cleanupOrder = org.mockito.Mockito.inOrder(session, workspaceAccess);
        cleanupOrder.verify(session).close();
        cleanupOrder.verify(workspaceAccess).close();
        verifyNoInteractions(runner);
    }

    @Test
    void classifiesNullRuntimeSessionAsRuntimeStartupFailure() {
        when(runtime.open(configuration)).thenReturn(null);

        PlaywrightExecutionException thrown = catchEngineFailure(() -> engine.execute(request));

        assertThat(thrown.code()).isEqualTo("PLAYWRIGHT_RUNTIME_START_FAILED");
        assertThat(thrown.code()).isNotEqualTo("PLAYWRIGHT_METRICS_INVALID");
        assertThat(thrown).hasMessage("Playwright runtime could not be started");
        assertThat(thrown.getMessage())
                .doesNotContain(
                        "secret",
                        "user:password",
                        "payment-card",
                        "abc123",
                        "raw-playwright-diagnostic");
        assertThat(thrown.getCause())
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Playwright runtime returned no session");
        assertThat(thrown.getCause().getMessage())
                .doesNotContain(
                        "secret",
                        "user:password",
                        "payment-card",
                        "abc123",
                        "raw-playwright-diagnostic");
        assertThat(thrown.getSuppressed()).isEmpty();
        verify(runtime).open(configuration);
        verify(workspaceAccess).close();
        verifyNoInteractions(session, runner);
    }

    @Test
    void sanitizesDefensiveExecuteTimeConfigurationFailure() {
        PlaywrightConfigurationException raw = new PlaywrightConfigurationException(SENSITIVE);
        when(configurationParser.parse(any())).thenThrow(raw);

        assertSanitized(
                () -> engine.execute(request),
                "PLAYWRIGHT_CONFIGURATION_INVALID",
                "Playwright execution configuration is invalid",
                raw);
        verify(workspaceResolver, never()).open(any());
    }

    @Test
    void sanitizesRunnerInfrastructureFailure() {
        PlaywrightActionException raw = new PlaywrightActionException("RAW_ACTION", SENSITIVE);
        when(runner.execute(any(), any(), any())).thenThrow(raw);

        assertSanitized(
                () -> engine.execute(request),
                "PLAYWRIGHT_ACTION_EXECUTION_FAILED",
                "Playwright action execution failed",
                raw);
        verify(runner).execute(any(), any(), any());
        InOrder cleanupOrder = org.mockito.Mockito.inOrder(session, workspaceAccess);
        cleanupOrder.verify(session).close();
        cleanupOrder.verify(workspaceAccess).close();
    }

    @Test
    void sanitizesRuntimeCleanupFailure() {
        PlaywrightRuntimeException raw =
                new PlaywrightRuntimeException("RAW_RUNTIME_CLOSE", SENSITIVE);
        doThrow(raw).when(session).close();

        assertSanitized(
                () -> engine.execute(request),
                "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED",
                "Playwright runtime cleanup failed",
                raw);
    }

    @Test
    void sanitizesWorkspaceCleanupFailure() {
        EngineWorkspaceAccessException raw =
                new EngineWorkspaceAccessException("RAW_WORKSPACE_CLOSE", SENSITIVE);
        doThrow(raw).when(workspaceAccess).close();

        assertSanitized(
                () -> engine.execute(request),
                "PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED",
                "Playwright workspace access cleanup failed",
                raw);
    }

    @Test
    void suppressesOnlySanitizedPrimaryExecutionFailureBehindCleanupFailure() {
        PlaywrightActionException execution =
                new PlaywrightActionException("RAW_ACTION", SENSITIVE);
        PlaywrightRuntimeException cleanup =
                new PlaywrightRuntimeException("RAW_RUNTIME_CLOSE", SENSITIVE);
        when(runner.execute(any(), any(), any())).thenThrow(execution);
        doThrow(cleanup).when(session).close();

        PlaywrightExecutionException thrown = catchEngineFailure(() -> engine.execute(request));

        assertFailure(
                thrown,
                "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED",
                "Playwright runtime cleanup failed",
                cleanup);
        assertThat(thrown.getSuppressed()).hasSize(1);
        PlaywrightExecutionException suppressed = sanitized(thrown.getSuppressed()[0]);
        assertFailure(
                suppressed,
                "PLAYWRIGHT_ACTION_EXECUTION_FAILED",
                "Playwright action execution failed",
                execution);
    }

    @Test
    void keepsBothCleanupFailuresAndExecutionFailureInSanitizedDeterministicChain() {
        PlaywrightActionException execution =
                new PlaywrightActionException("RAW_ACTION", SENSITIVE);
        PlaywrightRuntimeException runtimeCleanup =
                new PlaywrightRuntimeException("RAW_RUNTIME_CLOSE", SENSITIVE);
        EngineWorkspaceAccessException workspaceCleanup =
                new EngineWorkspaceAccessException("RAW_WORKSPACE_CLOSE", SENSITIVE);
        when(runner.execute(any(), any(), any())).thenThrow(execution);
        doThrow(runtimeCleanup).when(session).close();
        doThrow(workspaceCleanup).when(workspaceAccess).close();

        PlaywrightExecutionException thrown = catchEngineFailure(() -> engine.execute(request));

        assertFailure(
                thrown,
                "PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED",
                "Playwright workspace access cleanup failed",
                workspaceCleanup);
        assertThat(thrown.getSuppressed()).hasSize(1);
        PlaywrightExecutionException runtimeSuppressed = sanitized(thrown.getSuppressed()[0]);
        assertFailure(
                runtimeSuppressed,
                "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED",
                "Playwright runtime cleanup failed",
                runtimeCleanup);
        assertThat(runtimeSuppressed.getSuppressed()).hasSize(1);
        assertFailure(
                sanitized(runtimeSuppressed.getSuppressed()[0]),
                "PLAYWRIGHT_ACTION_EXECUTION_FAILED",
                "Playwright action execution failed",
                execution);
    }

    @Test
    void keepsBothCleanupFailuresAfterSuccessfulExecutionInSanitizedDeterministicChain() {
        PlaywrightRuntimeException runtimeCleanup =
                new PlaywrightRuntimeException("RAW_RUNTIME_CLOSE", SENSITIVE);
        EngineWorkspaceAccessException workspaceCleanup =
                new EngineWorkspaceAccessException("RAW_WORKSPACE_CLOSE", SENSITIVE);
        doThrow(runtimeCleanup).when(session).close();
        doThrow(workspaceCleanup).when(workspaceAccess).close();

        PlaywrightExecutionException thrown = catchEngineFailure(() -> engine.execute(request));

        assertFailure(
                thrown,
                "PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED",
                "Playwright workspace access cleanup failed",
                workspaceCleanup);
        assertThat(thrown.getSuppressed()).hasSize(1);
        PlaywrightExecutionException runtimeSuppressed = sanitized(thrown.getSuppressed()[0]);
        assertFailure(
                runtimeSuppressed,
                "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED",
                "Playwright runtime cleanup failed",
                runtimeCleanup);
        assertThat(runtimeSuppressed.getSuppressed()).isEmpty();
        InOrder cleanupOrder = org.mockito.Mockito.inOrder(session, workspaceAccess);
        cleanupOrder.verify(session).close();
        cleanupOrder.verify(workspaceAccess).close();
    }

    private void stubSuccess() {
        PlaywrightScenarioManifest manifest = manifest();
        PlaywrightRuntimeMetrics startup = PlaywrightRuntimeMetrics.startup(Duration.ofMillis(25));
        PlaywrightRuntimeMetrics completed = new PlaywrightRuntimeMetrics(
                1, 1, 0, Duration.ofMillis(50), Duration.ofMillis(25));
        when(configurationParser.parse(any())).thenReturn(configuration);
        when(workspaceResolver.open(any())).thenReturn(workspaceAccess);
        when(workspaceAccess.workspaceId())
                .thenReturn(request.preparation().workspace().workspaceId());
        when(manifestLoader.load(any(), eq(workspaceAccess))).thenReturn(manifest);
        when(runtime.open(configuration)).thenReturn(session);
        when(session.result()).thenReturn(new PlaywrightRuntimeResult(startup));
        when(runner.execute(
                        eq(manifest.scenarios()),
                        any(),
                        any(PlaywrightActionMetricsAccumulator.class)))
                .thenReturn(new PlaywrightScenarioExecutionOutcome(
                        PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED,
                        null,
                        completed));
    }

    private EngineExecutionRequest request() {
        UUID executionId = UUID.randomUUID();
        UUID workspaceUuid = UUID.randomUUID();
        WorkspaceId workspaceId = new WorkspaceId(workspaceUuid);
        ExecutionSourceReference sourceReference = new ExecutionSourceReference(
                SourceType.GIT_HTTPS, "repository", REVISION, null);
        WorkspaceDescriptor workspace = WorkspaceDescriptor.planned(
                        workspaceId,
                        executionId,
                        new WorkspaceProviderId("local-filesystem"))
                .transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(
                        WorkspaceState.READY,
                        new WorkspaceMetadata(NOW, sourceReference));
        SourcePreparationResult preparation = new SourcePreparationResult(
                workspace,
                new SourceMaterializationResult(
                        workspaceId,
                        SourceType.GIT_HTTPS,
                        REVISION,
                        SourceMaterializationState.MATERIALIZED,
                        NOW),
                SourcePreparationState.PREPARED,
                NOW);
        ExecutionContext context = new ExecutionContext(
                executionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ExecutionSuiteSnapshot(
                        UUID.randomUUID(),
                        "Suite",
                        PlaywrightEngineDescriptor.ENGINE_NAME,
                        PlaywrightEngineDescriptor.ENGINE_VERSION,
                        "BROWSER",
                        null,
                        "scenario.json",
                        Map.of(),
                        Map.of()),
                new ExecutionEnvironmentSnapshot(
                        UUID.randomUUID(),
                        "QA",
                        "TEST",
                        "https://example.test",
                        Map.of(),
                        Map.of()),
                List.of(),
                Map.of(),
                new ExecutionRunnerContext(
                        UUID.randomUUID(),
                        "runner",
                        "1",
                        "windows",
                        "amd64",
                        Map.of(),
                        Map.of()),
                new ExecutionMetadata(
                        UUID.randomUUID(),
                        NOW,
                        NOW,
                        Duration.ofMinutes(5),
                        ExecutionRetryPolicy.DISABLED));
        return new EngineExecutionRequest(context, preparation);
    }

    private PlaywrightScenarioManifest manifest() {
        PlaywrightStep step = new PlaywrightStep(
                "step-1", PlaywrightActionType.NAVIGATE, null, "/", null, null, null);
        PlaywrightScenario scenario =
                new PlaywrightScenario("scenario-1", "Scenario", List.of(step));
        return new PlaywrightScenarioManifest("1.0", "Manifest", List.of(scenario));
    }

    private void assertSanitized(
            ThrowingCall call, String code, String message, RuntimeException cause) {
        PlaywrightExecutionException thrown = catchEngineFailure(call);
        assertFailure(thrown, code, message, cause);
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    private PlaywrightExecutionException catchEngineFailure(ThrowingCall call) {
        final PlaywrightExecutionException[] captured = new PlaywrightExecutionException[1];
        assertThatThrownBy(call::run)
                .isInstanceOf(PlaywrightExecutionException.class)
                .satisfies(failure -> captured[0] = (PlaywrightExecutionException) failure);
        return captured[0];
    }

    private void assertFailure(
            PlaywrightExecutionException failure,
            String code,
            String message,
            RuntimeException cause) {
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure).hasMessage(message).hasCause(cause);
        assertThat(failure.getMessage())
                .doesNotContain(
                        "secret",
                        "user:password",
                        "payment-card",
                        "abc123",
                        "raw-playwright-diagnostic");
    }

    private PlaywrightExecutionException sanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(PlaywrightExecutionException.class);
        return (PlaywrightExecutionException) failure;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
