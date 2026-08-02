package com.automationstudio.api.execution.engine.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.automationstudio.api.execution.engine.playwright.action.AssertTextActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.AssertUrlActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.AssertVisibleActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.ClickActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.CssSelectorResolver;
import com.automationstudio.api.execution.engine.playwright.action.FillActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.NavigateActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.NonSecretVariableInterpolator;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionExecutionContext;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionExecutorRegistry;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionMetricsAccumulator;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightOrderedScenarioRunner;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightScenarioExecutionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.SameOriginNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightBrowser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationParser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenario;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.engine.playwright.runtime.DefaultPlaywrightRuntime;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeSession;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspacePreparationRequest;
import com.automationstudio.api.execution.workspace.WorkspacePreparationResult;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceProvider;
import com.automationstudio.api.execution.workspace.local.WorkspaceRootProperties;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccess;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessRequest;
import com.automationstudio.api.execution.workspace.local.access.LocalEngineWorkspaceAccessResolver;
import com.automationstudio.api.security.SensitiveKeyDetector;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("real-browser")
class PlaywrightExecutionEngineEndToEndTest {

    private static final String REVISION = "as024g-real-browser-revision";

    @TempDir Path temporaryDirectory;
    private HttpServer server;
    private HttpServer otherOrigin;
    private String baseUrl;
    private final AtomicInteger laterActions = new AtomicInteger();

    @BeforeEach
    void startServers() throws Exception {
        RealBrowserTestSupport.configuredExecutableOrSkip();
        otherOrigin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        otherOrigin.createContext("/foreign", exchange -> respond(exchange, 200, "foreign"));
        otherOrigin.start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/form", exchange -> respond(exchange, 200, formPage()));
        server.createContext("/redirect", exchange -> redirect(exchange, "/form"));
        server.createContext("/cross", exchange -> redirect(exchange,
                "http://127.0.0.1:" + otherOrigin.getAddress().getPort() + "/foreign"));
        server.createContext("/later", exchange -> {
            laterActions.incrementAndGet();
            respond(exchange, 204, "");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServers() {
        if (server != null) server.stop(0);
        if (otherOrigin != null) otherOrigin.stop(0);
    }

    @Test
    void executesRealManifestThroughEngineAndReturnsProviderNeutralSuccess() throws Exception {
        Fixture fixture = fixture(successManifest());

        EngineExecutionResult result = fixture.engine().execute(fixture.request());

        assertIdentity(result, fixture, EngineExecutionState.SUCCEEDED);
        assertThat(result.toString()).doesNotContain(
                "#name", "Hello alpha", baseUrl, "chromium", "browserStartupDuration");
        assertWorkspaceRetainedAndHandleReusable(fixture);
    }

    @Test
    void assertionMismatchReturnsFailedStopsLaterActionAndRetainsNoDetails() throws Exception {
        Fixture fixture = fixture(assertionFailureManifest());

        EngineExecutionResult result = fixture.engine().execute(fixture.request());

        assertIdentity(result, fixture, EngineExecutionState.FAILED);
        assertThat(result.toString()).doesNotContain(
                "expected-secret-text", "#result", baseUrl, "foreign");
        assertThat(laterActions).hasValue(0);
        assertWorkspaceRetainedAndHandleReusable(fixture);
    }

    @Test
    void crossOriginRedirectFailsClosedWithSanitizedInfrastructureFailure() throws Exception {
        Fixture fixture = fixture(crossOriginManifest());

        assertThatThrownBy(() -> fixture.engine().execute(fixture.request()))
                .isInstanceOfSatisfying(PlaywrightExecutionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PLAYWRIGHT_ACTION_EXECUTION_FAILED");
                    assertThat(failure).hasMessage("Playwright action execution failed");
                    assertThat(failure.getMessage()).doesNotContain(baseUrl, "foreign", "127.0.0.1");
                });
        assertWorkspaceRetainedAndHandleReusable(fixture);
    }

    @Test
    void missingElementTimeoutIsBoundedSanitizedAndCleansResources() throws Exception {
        Fixture fixture = fixture(timeoutManifest());

        assertThatThrownBy(() -> fixture.engine().execute(fixture.request()))
                .isInstanceOfSatisfying(PlaywrightExecutionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PLAYWRIGHT_ACTION_EXECUTION_FAILED");
                    assertThat(failure).hasMessage("Playwright action execution failed");
                    assertThat(failure.getMessage()).doesNotContain("#never", baseUrl, "Timeout");
                });
        assertWorkspaceRetainedAndHandleReusable(fixture);
    }

    @Test
    void unresolvedVariableFailsBeforeLaterActionWithoutExposingItsValue() throws Exception {
        Fixture fixture = fixture(unresolvedVariableManifest());

        assertThatThrownBy(() -> fixture.engine().execute(fixture.request()))
                .isInstanceOfSatisfying(PlaywrightExecutionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PLAYWRIGHT_ACTION_EXECUTION_FAILED");
                    assertThat(failure).hasMessage("Playwright action execution failed");
                    assertThat(failure.getMessage()).doesNotContain(
                            "unresolved-private-value", "#name", baseUrl);
                });
        assertThat(laterActions).hasValue(0);
        assertWorkspaceRetainedAndHandleReusable(fixture);
    }

    @Test
    void realOrderedRunnerRecordsTerminalAssertionMetricsAndStopsLaterAction() throws Exception {
        Path executable = RealBrowserTestSupport.configuredExecutableOrSkip();
        Fixture fixture = fixture(assertionFailureManifest());
        EngineWorkspaceAccess workspaceAccess = fixture.resolver().open(
                EngineWorkspaceAccessRequest.from(fixture.request().preparation()));
        PlaywrightExecutionConfiguration configuration = executionConfiguration();
        PlaywrightRuntimeSession session = null;
        try {
            session = new DefaultPlaywrightRuntime(
                    new PlaywrightRuntimeProperties(executable.toString(), Duration.ofSeconds(30)))
                    .open(configuration);
            PlaywrightActionMetricsAccumulator metrics = new PlaywrightActionMetricsAccumulator(
                    3, session.result().metrics().browserStartupDuration());
            PlaywrightScenario scenario = new PlaywrightScenario("metrics", "Metrics", List.of(
                    new PlaywrightStep("navigate", PlaywrightActionType.NAVIGATE, null, "/form",
                            null, null, Duration.ofSeconds(3)),
                    new PlaywrightStep("assert", PlaywrightActionType.ASSERT_TEXT,
                            new PlaywrightSelector("#result"), null, null,
                            "metrics-private-expected", Duration.ofSeconds(2)),
                    new PlaywrightStep("later", PlaywrightActionType.CLICK,
                            new PlaywrightSelector("#later"), null, null, null,
                            Duration.ofSeconds(2))));
            PlaywrightActionExecutionContext context = new PlaywrightActionExecutionContext(
                    scenario.id(), session, configuration, new CssSelectorResolver(),
                    new NonSecretVariableInterpolator(Map.of()),
                    new SameOriginNavigationPolicy(baseUrl));

            PlaywrightScenarioExecutionOutcome outcome = new PlaywrightOrderedScenarioRunner(
                    actionRegistry()).execute(List.of(scenario), context, metrics);

            assertThat(outcome.status())
                    .isEqualTo(PlaywrightScenarioExecutionOutcome.Status.ASSERTION_FAILED);
            assertThat(outcome.metrics().totalActions()).isEqualTo(3);
            assertThat(outcome.metrics().successfulActions()).isEqualTo(1);
            assertThat(outcome.metrics().failedActions()).isEqualTo(1);
            assertThat(outcome.metrics().browserStartupDuration())
                    .isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(outcome.metrics().totalExecutionDuration())
                    .isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(outcome.toString()).doesNotContain(
                    "metrics-private-expected", "#result", "#later", baseUrl, "Hello");
            assertThat(laterActions).hasValue(0);
        } finally {
            if (session != null) session.close();
            workspaceAccess.close();
        }
        assertThat(session).isNotNull();
        assertThat(session.isOpen()).isFalse();
        assertThat(workspaceAccess.isOpen()).isFalse();
        assertWorkspaceRetainedAndHandleReusable(fixture);
    }

    private Fixture fixture(String manifest) throws Exception {
        Path executable = RealBrowserTestSupport.configuredExecutableOrSkip();
        Clock clock = Clock.systemUTC();
        Path workspaceRoot = temporaryDirectory.resolve("workspaces-" + UUID.randomUUID());
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(workspaceRoot.toAbsolutePath().toString()), clock);
        LocalEngineWorkspaceAccessResolver resolver = new LocalEngineWorkspaceAccessResolver(provider);
        UUID executionId = UUID.randomUUID();
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS, "https://127.0.0.1/as024g", REVISION, null);
        WorkspaceDescriptor preparing = WorkspaceDescriptor.planned(
                        workspaceId, executionId, LocalWorkspaceProvider.PROVIDER_ID)
                .transitionTo(WorkspaceState.PREPARING, null);
        WorkspacePreparationResult prepared = provider.prepare(
                new WorkspacePreparationRequest(preparing, source));
        Path sourceDirectory = workspaceRoot.resolve(workspaceId.value().toString()).resolve("source");
        Files.writeString(sourceDirectory.resolve("scenario.json"), manifest);
        OffsetDateTime now = OffsetDateTime.now(clock);
        SourcePreparationResult preparation = new SourcePreparationResult(
                prepared.workspace(),
                new SourceMaterializationResult(workspaceId, SourceType.GIT_HTTPS, REVISION,
                        SourceMaterializationState.MATERIALIZED, now),
                SourcePreparationState.PREPARED, now);
        ExecutionContext context = context(executionId, workspaceId.value());
        PlaywrightActionExecutorRegistry registry = actionRegistry();
        PlaywrightExecutionEngine engine = new PlaywrightExecutionEngine(
                new PlaywrightConfigurationParser(new SensitiveKeyDetector()), resolver,
                new PlaywrightScenarioManifestLoader(),
                new DefaultPlaywrightRuntime(new PlaywrightRuntimeProperties(
                        executable.toString(), Duration.ofSeconds(30))),
                new PlaywrightOrderedScenarioRunner(registry), new CssSelectorResolver(), clock);
        return new Fixture(engine, new EngineExecutionRequest(context, preparation),
                resolver, workspaceRoot.resolve(workspaceId.value().toString()));
    }

    private PlaywrightActionExecutorRegistry actionRegistry() {
        return new PlaywrightActionExecutorRegistry(List.of(
                new NavigateActionExecutor(), new ClickActionExecutor(), new FillActionExecutor(),
                new AssertVisibleActionExecutor(), new AssertTextActionExecutor(),
                new AssertUrlActionExecutor()));
    }

    private PlaywrightExecutionConfiguration executionConfiguration() {
        return new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM, true, Duration.ofSeconds(2), Duration.ofSeconds(3),
                1024, 768, "en-US", PlaywrightNavigationPolicy.SAME_ORIGIN);
    }

    private ExecutionContext context(UUID executionId, UUID workspaceId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ExecutionContext(executionId, UUID.randomUUID(), workspaceId,
                new ExecutionSuiteSnapshot(UUID.randomUUID(), "AS-024G", "playwright-java", "1.61.0",
                        "BROWSER", null, "scenario.json",
                        Map.of("browser", "chromium", "headless", true,
                                "actionTimeoutMs", 2000, "navigationTimeoutMs", 3000), Map.of()),
                new ExecutionEnvironmentSnapshot(UUID.randomUUID(), "Local", "TEST", baseUrl,
                        Map.of(), Map.of()),
                List.of(new ExecutionSecretReference("unrelated", Map.of("provider", "unused"))),
                Map.of("startPath", new ExecutionVariable("startPath", "/redirect",
                                ExecutionVariableSource.EXECUTION),
                        "input", new ExecutionVariable("input", "alpha",
                                ExecutionVariableSource.EXECUTION)),
                new ExecutionRunnerContext(UUID.randomUUID(), "real-browser", "1", "windows",
                        "amd64", Map.of(), Map.of()),
                new ExecutionMetadata(UUID.randomUUID(), now, now, Duration.ofMinutes(1),
                        ExecutionRetryPolicy.DISABLED));
    }

    private void assertIdentity(EngineExecutionResult result, Fixture fixture, EngineExecutionState state) {
        assertThat(result.executionId()).isEqualTo(fixture.request().executionId());
        assertThat(result.workspaceId()).isEqualTo(fixture.request().preparation().workspace().workspaceId());
        assertThat(result.resolvedRevision()).isEqualTo(REVISION);
        assertThat(result.engineName()).isEqualTo("playwright-java");
        assertThat(result.engineVersion()).isEqualTo("1.61.0");
        assertThat(result.state()).isEqualTo(state);
    }

    private void assertWorkspaceRetainedAndHandleReusable(Fixture fixture) {
        assertThat(fixture.workspaceDirectory()).isDirectory();
        EngineWorkspaceAccess access = fixture.resolver().open(
                EngineWorkspaceAccessRequest.from(fixture.request().preparation()));
        assertThat(access.isOpen()).isTrue();
        access.close();
    }

    private String successManifest() {
        return manifest("""
                {"id":"navigate","action":"navigate","url":"${startPath}"},
                {"id":"redirect-url","action":"assert-url","expected":"%s/form"},
                {"id":"fill","action":"fill","selector":"#name","value":"${input}"},
                {"id":"click","action":"click","selector":"#submit"},
                {"id":"visible","action":"assert-visible","selector":"#result"},
                {"id":"text","action":"assert-text","selector":"#result","expected":"Hello ${input}"},
                {"id":"url","action":"assert-url","expected":"%s/done"}
                """.formatted(baseUrl, baseUrl));
    }

    private String assertionFailureManifest() {
        return manifest("""
                {"id":"navigate","action":"navigate","url":"/form"},
                {"id":"assert","action":"assert-text","selector":"#result","expected":"expected-secret-text"},
                {"id":"later","action":"click","selector":"#later"}
                """);
    }

    private String crossOriginManifest() {
        return manifest("""
                {"id":"navigate","action":"navigate","url":"/cross"}
                """);
    }

    private String timeoutManifest() {
        return manifest("""
                {"id":"navigate","action":"navigate","url":"/form"},
                {"id":"missing","action":"click","selector":"#never","timeoutMs":200}
                """);
    }

    private String unresolvedVariableManifest() {
        return manifest("""
                {"id":"navigate","action":"navigate","url":"/form"},
                {"id":"fill","action":"fill","selector":"#name","value":"${unresolved-private-value}"},
                {"id":"later","action":"click","selector":"#later"}
                """);
    }

    private String manifest(String steps) {
        return """
                {"schemaVersion":"1.0","name":"AS-024G","scenarios":[
                  {"id":"real-scenario","name":"Real scenario","steps":[%s]}
                ]}
                """.formatted(steps);
    }

    private String formPage() {
        return """
                <!doctype html><html><body>
                <input id="name"><button id="submit">Submit</button>
                <div id="result" style="display:none"></div>
                <a id="later" href="/later">Later</a>
                <script>
                document.querySelector('#submit').onclick=()=>{
                  const result=document.querySelector('#result');
                  result.textContent='Hello '+document.querySelector('#name').value;
                  result.style.display='block'; history.pushState({},'', '/done');
                };
                </script></body></html>
                """;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String text)
            throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        if (status != 204) exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void redirect(com.sun.net.httpserver.HttpExchange exchange, String location)
            throws java.io.IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private record Fixture(
            PlaywrightExecutionEngine engine,
            EngineExecutionRequest request,
            LocalEngineWorkspaceAccessResolver resolver,
            Path workspaceDirectory) {}
}
