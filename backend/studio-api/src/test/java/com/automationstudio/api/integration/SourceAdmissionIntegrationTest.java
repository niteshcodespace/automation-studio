package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Workspace;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.repository.WorkspaceRepository;
import com.automationstudio.api.service.ExecutionService;
import com.automationstudio.api.service.RunnerSchedulingService;
import com.automationstudio.api.service.command.CreateExecutionCommand;
import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.EngineExecutionState;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl;
import com.automationstudio.api.execution.business.OrangeHrmQualificationEvidence;
import com.automationstudio.api.execution.business.OrangeHrmQualificationDiagnosticProbe;
import com.automationstudio.api.execution.business.OrangeHrmQualificationExecutionEngine;
import com.automationstudio.api.execution.business.OrangeHrmQualificationPrerequisites;
import com.automationstudio.api.execution.engine.playwright.PlaywrightExecutionEngine;
import com.automationstudio.api.execution.engine.playwright.action.AssertTextActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.AssertUrlActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.AssertVisibleActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.ClickActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.CssSelectorResolver;
import com.automationstudio.api.execution.engine.playwright.action.FillActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.NavigateActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionExecutorRegistry;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightOrderedScenarioRunner;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationParser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.runtime.DefaultPlaywrightRuntime;
import com.automationstudio.api.execution.orchestration.AdmittedSourceSnapshotMapper;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestratorImpl;
import com.automationstudio.api.execution.orchestration.RunnerExecutionRequest;
import com.automationstudio.api.execution.orchestration.RunnerExecutionService;
import com.automationstudio.api.execution.orchestration.RunnerPipelineCoordinatorImpl;
import com.automationstudio.api.execution.preparation.SourcePreparationServiceImpl;
import com.automationstudio.api.execution.secret.ExecutionSecretProvider;
import com.automationstudio.api.execution.secret.ExecutionSecretProviderRegistry;
import com.automationstudio.api.execution.secret.ExecutionSecretScopeFactory;
import com.automationstudio.api.execution.secret.ResolvedSecret;
import com.automationstudio.api.execution.secret.provider.environment.OperatorEnvironmentSecretProvider;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceProvider;
import com.automationstudio.api.execution.workspace.local.WorkspaceRootProperties;
import com.automationstudio.api.execution.workspace.local.access.LocalEngineWorkspaceAccessResolver;
import com.automationstudio.api.security.SensitiveKeyDetector;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import com.automationstudio.api.source.SourceType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import java.util.stream.Stream;

@AutoConfigureMockMvc
@Import(SourceAdmissionIntegrationTest.ControlledEngineConfiguration.class)
class SourceAdmissionIntegrationTest extends IntegrationTestBase {

    private static final String PREFIX = "as-023b-admission-";
    private static final String FIRST_SHA =
            "1111111111111111111111111111111111111111";
    private static final String SECOND_SHA =
            "2222222222222222222222222222222222222222";

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private AutomationSuiteRepository suiteRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired private RunnerSchedulingService schedulingService;
    @Autowired private RunnerExecutionService runnerExecutionService;
    @Autowired private ControlledEngine controlledEngine;
    @Autowired private ExecutionEngineRegistryImpl executionEngineRegistry;
    @Autowired private OrangeHrmQualificationExecutionEngine qualificationExecutionEngine;

    @AfterEach
    void cleanDatabase() {
        qualificationExecutionEngine.clear();
        jdbcTemplate.update("""
                DELETE FROM execution_lease
                WHERE execution_id IN (
                    SELECT e.id FROM execution e
                    JOIN project p ON p.id = e.project_id
                    JOIN workspace w ON w.id = p.workspace_id
                    WHERE w.slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM runner_runtime WHERE runner_id IN "
                + "(SELECT id FROM runner WHERE runner_key LIKE ?)", PREFIX + "runner-%");
        jdbcTemplate.update("DELETE FROM runner WHERE runner_key LIKE ?", PREFIX + "runner-%");
        jdbcTemplate.update("""
                DELETE FROM execution
                WHERE project_id IN (
                    SELECT p.id FROM project p
                    JOIN workspace w ON w.id = p.workspace_id
                    WHERE w.slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT p.id FROM project p
                    JOIN workspace w ON w.id = p.workspace_id
                    WHERE w.slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT p.id FROM project p
                    JOIN workspace w ON w.id = p.workspace_id
                    WHERE w.slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project
                WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE ?", PREFIX + "%");
    }

    @Test
    void admissionSnapshotsSourceAndExecutionReadUsesImmutableValues() throws Exception {
        Fixture fixture = fixture(true, "PLAYWRIGHT", "PLAYWRIGHT", "tests/ui");
        Execution execution = create(fixture);

        assertThat(execution.getSourceSnapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "sourceType", "GIT_HTTPS",
                "repository", "https://github.com/acme/automation.git",
                "revision", FIRST_SHA,
                "sourceLocation", "tests/ui"));

        fixture.project().setSourceRepository("https://github.com/acme/replacement.git");
        fixture.project().setSourceRevision(SECOND_SHA);
        projectRepository.saveAndFlush(fixture.project());
        fixture.suite().setSourceLocation("tests/replacement");
        suiteRepository.saveAndFlush(fixture.suite());

        Execution unchanged = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(unchanged.getSourceSnapshot())
                .containsEntry("repository", "https://github.com/acme/automation.git")
                .containsEntry("revision", FIRST_SHA)
                .containsEntry("sourceLocation", "tests/ui");

        mockMvc.perform(get("/api/v1/projects/" + fixture.project().getId()
                        + "/executions/" + execution.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceSnapshot.sourceType").value("GIT_HTTPS"))
                .andExpect(jsonPath("$.sourceSnapshot.repository")
                        .value("https://github.com/acme/automation.git"))
                .andExpect(jsonPath("$.sourceSnapshot.revision").value(FIRST_SHA))
                .andExpect(jsonPath("$.sourceSnapshot.sourceLocation").value("tests/ui"));
    }

    @ParameterizedTest
    @MethodSource("controlledOutcomes")
    void admittedScheduledClaimExecutesThroughCoordinatorAndPersistsNormalizedOutcome(
            EngineExecutionState engineState,
            boolean secretFailure,
            ExecutionStatus expectedStatus,
            @TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(true, "PLAYWRIGHT", "controlled-pipeline", "tests/ui");
        fixture.environment().setSecretReferences(Map.of(
                "orangehrm.username", Map.of("provider", "controlled", "key", "username"),
                "orangehrm.password", Map.of("provider", "controlled", "key", "password")));
        environmentRepository.saveAndFlush(fixture.environment());
        Execution admitted = create(fixture);
        fixture.project().setSourceRevision(SECOND_SHA);
        projectRepository.saveAndFlush(fixture.project());
        String runnerKey = insertControlledRunner();
        var claimed = schedulingService.scheduleNext(
                        new ScheduleExecutionCommand(runnerKey, Duration.ofMinutes(2)))
                .scheduledExecution().orElseThrow();

        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(root.toString()), Clock.systemUTC());
        WorkspaceManager workspaceManager = new WorkspaceManager(provider);
        var preparation = new SourcePreparationServiceImpl(
                workspaceManager,
                request -> {
                    Path source = root.resolve(request.workspaceId().value().toString())
                            .resolve("source");
                    try {
                        Files.writeString(source.resolve("scenario.json"), "{}");
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    return new SourceMaterializationResult(
                            request.workspaceId(), request.sourceReference().sourceType(),
                            request.sourceReference().revision(),
                            SourceMaterializationState.MATERIALIZED, OffsetDateTime.now());
                },
                Clock.systemUTC());
        ControlledSecretProvider secrets = new ControlledSecretProvider();
        secrets.fail = secretFailure;
        controlledEngine.reset(engineState);
        var orchestrator = new ExecutionOrchestratorImpl(
                preparation,
                new ExecutionEngineRegistryImpl(List.of(controlledEngine)),
                workspaceManager,
                new ExecutionSecretScopeFactory(
                        new ExecutionSecretProviderRegistry(List.of(secrets))),
                Clock.systemUTC());
        var coordinator = new RunnerPipelineCoordinatorImpl(
                runnerExecutionService,
                orchestrator,
                new AdmittedSourceSnapshotMapper(new SourceConfigurationValidator()),
                provider.providerId());

        var result = coordinator.execute(new RunnerExecutionRequest(
                claimed.executionId(), claimed.runnerId(), claimed.claimToken(),
                claimed.leaseGeneration(), claimed.leaseVersion(), claimed.executionVersion()));

        assertThat(result.completion().status()).isEqualTo(expectedStatus);
        assertThat(executionRepository.findById(admitted.getId()).orElseThrow().getStatus())
                .isEqualTo(expectedStatus);
        assertThat(controlledEngine.resolvedRevision).isEqualTo(FIRST_SHA);
        assertThat(controlledEngine.secretNames).containsExactlyElementsOf(
                secretFailure
                        ? List.of("orangehrm.username")
                        : List.of("orangehrm.username", "orangehrm.password"));
        assertThat(secrets.values).allMatch(ResolvedSecret::isClosed);
        assertThat(root.resolve(admitted.getId().toString())).doesNotExist();
    }

    @Test
    @Tag("real-browser")
    void optInOrangeHrmQualificationUsesTheAuthoritativeControlledPipeline(@TempDir Path root)
            throws Exception {
        var qualification = OrangeHrmQualificationPrerequisites.configuredOrSkip();
        Fixture fixture = fixture(
                true, "PLAYWRIGHT", "playwright-java",
                "demo-projects/orangehrm-login-smoke");
        fixture.project().setSourceRevision(
                "65b9f5ea2a7118751c4fdcb89166b7e08fc30d05");
        fixture.suite().setSuiteReference("scenario.json");
        fixture.environment().setBaseUrl(qualification.target().toString());
        fixture.environment().setSecretReferences(Map.of(
                "orangehrm.username", Map.of(
                        "provider", "operator-environment", "key", qualification.usernameKey()),
                "orangehrm.password", Map.of(
                        "provider", "operator-environment", "key", qualification.passwordKey())));
        projectRepository.saveAndFlush(fixture.project());
        suiteRepository.saveAndFlush(fixture.suite());
        environmentRepository.saveAndFlush(fixture.environment());
        Execution admitted = create(fixture);
        String runnerKey = insertQualificationRunner();
        var claimed = schedulingService.scheduleNext(
                new ScheduleExecutionCommand(runnerKey, Duration.ofMinutes(2)))
                .scheduledExecution().orElseThrow();

        Clock clock = Clock.systemUTC();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(root.toString()), clock);
        WorkspaceManager workspaceManager = new WorkspaceManager(provider);
        var preparation = new SourcePreparationServiceImpl(
                workspaceManager,
                request -> {
                    Path destination = root.resolve(request.workspaceId().value().toString())
                            .resolve("source").resolve("scenario.json");
                    Path committedManifest = Path.of("..", "..", "demo-projects",
                            "orangehrm-login-smoke", "scenario.json").toAbsolutePath().normalize();
                    try {
                        Files.copy(committedManifest, destination);
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException("Qualification source is unavailable", failure);
                    }
                    return new SourceMaterializationResult(
                            request.workspaceId(), request.sourceReference().sourceType(),
                            request.sourceReference().revision(),
                            SourceMaterializationState.MATERIALIZED, OffsetDateTime.now(clock));
                },
                clock);
        PlaywrightActionExecutorRegistry actions = new PlaywrightActionExecutorRegistry(List.of(
                new NavigateActionExecutor(), new ClickActionExecutor(), new FillActionExecutor(),
                new AssertVisibleActionExecutor(), new AssertTextActionExecutor(),
                new AssertUrlActionExecutor()));
        PlaywrightExecutionEngine engine = new PlaywrightExecutionEngine(
                new PlaywrightConfigurationParser(new SensitiveKeyDetector()),
                new LocalEngineWorkspaceAccessResolver(provider),
                new PlaywrightScenarioManifestLoader(),
                new DefaultPlaywrightRuntime(new PlaywrightRuntimeProperties(
                        qualification.browserExecutable().toString(), Duration.ofSeconds(30))),
                new PlaywrightOrderedScenarioRunner(actions), new CssSelectorResolver(), clock);
        qualificationExecutionEngine.bind(engine);
        var orchestrator = new ExecutionOrchestratorImpl(
                preparation,
                executionEngineRegistry,
                workspaceManager,
                new ExecutionSecretScopeFactory(new ExecutionSecretProviderRegistry(List.of(
                        new OperatorEnvironmentSecretProvider(true, System::getenv)))),
                clock);
        var diagnosticProbe = new OrangeHrmQualificationDiagnosticProbe(orchestrator);
        var coordinator = new RunnerPipelineCoordinatorImpl(
                runnerExecutionService, diagnosticProbe,
                new AdmittedSourceSnapshotMapper(new SourceConfigurationValidator()),
                provider.providerId());

        var result = coordinator.execute(new RunnerExecutionRequest(
                claimed.executionId(), claimed.runnerId(), claimed.claimToken(),
                claimed.leaseGeneration(), claimed.leaseVersion(), claimed.executionVersion()));

        diagnosticProbe.diagnostic().ifPresent(diagnostic ->
                System.out.println("AS-025G sanitized diagnostic: " + diagnostic));
        assertThat(result.completion().status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(executionRepository.findById(admitted.getId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.PASSED);
        assertThat(root.resolve(admitted.getId().toString())).doesNotExist();
        OrangeHrmQualificationEvidence evidence = new OrangeHrmQualificationEvidence(
                System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), "1.61.0",
                qualification.browserProduct(), qualification.browserBuild(),
                "operator-provisioned", qualification.targetClassification(),
                admitted.getSourceSnapshot().get("revision").toString(), 1, 0, 0);
        System.out.println("AS-025G qualification evidence: " + evidence);
    }

    private static Stream<Arguments> controlledOutcomes() {
        return Stream.of(
                Arguments.of(EngineExecutionState.SUCCEEDED, false, ExecutionStatus.PASSED),
                Arguments.of(EngineExecutionState.FAILED, false, ExecutionStatus.FAILED),
                Arguments.of(EngineExecutionState.SUCCEEDED, true, ExecutionStatus.ERROR));
    }

    @Test
    void sourceBasedAdmissionFailsClosedWithoutProjectSource() {
        Fixture fixture = fixture(false, "PLAYWRIGHT", "PLAYWRIGHT", "tests/ui");

        assertThatThrownBy(() -> create(fixture))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageNotContaining("github")
                .hasMessageNotContaining("tests/ui");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM execution WHERE project_id = ?
                """, Integer.class, fixture.project().getId())).isZero();
    }

    @Test
    void builtinAndLegacyUnspecifiedEnginesRemainSourceIndependent() {
        Fixture builtin = fixture(false, "BUILTIN", "BUILTIN", null);
        Fixture legacy = fixture(false, "PLAYWRIGHT", null, null);

        assertThat(create(builtin).getSourceSnapshot()).isNull();
        assertThat(create(legacy).getSourceSnapshot()).isNull();
    }

    @Test
    void crossProjectSuiteCannotSupplySourceLocation() {
        Fixture owner = fixture(true, "PLAYWRIGHT", "PLAYWRIGHT", "tests/owner");
        Fixture outsider = fixture(true, "PLAYWRIGHT", "PLAYWRIGHT", "tests/outsider");

        assertThatThrownBy(() -> executionService.create(
                owner.project().getId(),
                "as-023b",
                new CreateExecutionCommand(
                        owner.environment().getId(),
                        outsider.suite().getId(),
                        ExecutionSelectionMode.SUITE,
                        null)))
                .isInstanceOf(com.automationstudio.api.exception.ResourceNotFoundException.class);
    }

    @Test
    void admissionWaitsForProjectSourceUpdateAndCapturesOneCompleteVersion() throws Exception {
        Fixture fixture = fixture(true, "PLAYWRIGHT", "PLAYWRIGHT", "tests/ui");
        CountDownLatch projectLocked = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Project locked = projectRepository.findByIdForUpdate(
                        fixture.project().getId()).orElseThrow();
                locked.setSourceRepository("https://github.com/acme/replacement.git");
                locked.setSourceRevision(SECOND_SHA);
                projectRepository.saveAndFlush(locked);
                projectLocked.countDown();
                await(allowCommit);
            }));
            assertThat(projectLocked.await(10, TimeUnit.SECONDS)).isTrue();

            var admission = executor.submit(() -> create(fixture));
            assertThat(admission.isDone()).isFalse();
            allowCommit.countDown();

            update.get(10, TimeUnit.SECONDS);
            Execution execution = admission.get(10, TimeUnit.SECONDS);
            assertThat(execution.getSourceSnapshot())
                    .containsEntry(
                            "repository",
                            "https://github.com/acme/replacement.git")
                    .containsEntry("revision", SECOND_SHA);
        }
    }

    private Execution create(Fixture fixture) {
        return executionService.create(
                fixture.project().getId(),
                "as-023b",
                new CreateExecutionCommand(
                        fixture.environment().getId(),
                        fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE,
                        null));
    }

    private String insertControlledRunner() {
        UUID id = UUID.randomUUID();
        String key = PREFIX + "runner-" + id;
        jdbcTemplate.update("""
                INSERT INTO runner (
                    id, runner_key, name, agent_version, hostname,
                    operating_system, architecture, max_concurrency,
                    capabilities, labels, status, registered_at,
                    last_registered_at, version, created_at, updated_at
                ) VALUES (?, ?, 'AS-025F', '1.0', 'controlled.test',
                    'linux', 'amd64', 1, ?::jsonb, '{}'::jsonb, 'ACTIVE',
                    clock_timestamp(), clock_timestamp(), 0,
                    clock_timestamp(), clock_timestamp())
                """, id, key, json(Map.of(
                        "engines", Map.of("controlled-pipeline", "1.0"))));
        jdbcTemplate.update("""
                INSERT INTO runner_runtime (
                    runner_id, last_seen_at, heartbeat_count, version, created_at, updated_at)
                VALUES (?, clock_timestamp(), 1, 0, clock_timestamp(), clock_timestamp())
                """, id);
        return key;
    }

    private String insertQualificationRunner() {
        UUID id = UUID.randomUUID();
        String key = PREFIX + "runner-" + id;
        jdbcTemplate.update("""
                INSERT INTO runner (
                    id, runner_key, name, agent_version, hostname,
                    operating_system, architecture, max_concurrency,
                    capabilities, labels, status, registered_at,
                    last_registered_at, version, created_at, updated_at
                ) VALUES (?, ?, 'AS-025G', '0.1.0', 'qualified.runner',
                    ?, ?, 1, ?::jsonb, '{}'::jsonb, 'ACTIVE',
                    clock_timestamp(), clock_timestamp(), 0,
                    clock_timestamp(), clock_timestamp())
                """, id, key, System.getProperty("os.name"), System.getProperty("os.arch"),
                json(Map.of("engines", Map.of("playwright-java", "1.61.0"))));
        jdbcTemplate.update("""
                INSERT INTO runner_runtime (
                    runner_id, last_seen_at, heartbeat_count, version, created_at, updated_at)
                VALUES (?, clock_timestamp(), 1, 0, clock_timestamp(), clock_timestamp())
                """, id);
        return key;
    }

    private String json(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Fixture fixture(
            boolean sourceConfigured,
            String engineType,
            String engineId,
            String sourceLocation) {
        String suffix = UUID.randomUUID().toString();
        Workspace workspace = new Workspace();
        workspace.setName("AS-023B " + suffix);
        workspace.setSlug(PREFIX + suffix);
        workspace = workspaceRepository.saveAndFlush(workspace);

        Project project = new Project();
        project.setWorkspace(workspace);
        project.setName("AS-023B " + suffix);
        if (sourceConfigured) {
            project.setSourceType(SourceType.GIT_HTTPS);
            project.setSourceRepository("https://github.com/acme/automation.git");
            project.setSourceRevision(FIRST_SHA);
        }
        project = projectRepository.saveAndFlush(project);

        Environment environment = new Environment();
        environment.setProject(project);
        environment.setName("AS-023B " + suffix);
        environment.setBaseUrl("https://example.test");
        environment.setType(EnvironmentType.TEST);
        environment = environmentRepository.saveAndFlush(environment);

        AutomationSuite suite = new AutomationSuite();
        suite.setProject(project);
        suite.setName("AS-023B " + suffix);
        suite.setEngineType(engineType);
        suite.setEngineId(engineId);
        suite.setSuiteReference("suite-" + suffix);
        suite.setSourceLocation(sourceLocation);
        suite = suiteRepository.saveAndFlush(suite);
        return new Fixture(project, environment, suite);
    }

    private record Fixture(
            Project project,
            Environment environment,
            AutomationSuite suite) {
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent source update");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source update test interrupted", exception);
        }
    }

    static final class ControlledEngine implements ExecutionEngine {
        private final List<String> secretNames = new ArrayList<>();
        private String resolvedRevision;
        private EngineExecutionState state = EngineExecutionState.SUCCEEDED;

        private void reset(EngineExecutionState nextState) {
            secretNames.clear();
            resolvedRevision = null;
            state = nextState;
        }

        @Override
        public ExecutionEngineDescriptor descriptor() {
            return new ExecutionEngineDescriptor(
                    "controlled-pipeline", "1.0", "Controlled", Set.of(), Set.of());
        }

        @Override
        public void validate(com.automationstudio.api.execution.ExecutionContext context) {
        }

        @Override
        public EngineExecutionResult execute(
                com.automationstudio.api.execution.engine.EngineExecutionRequest request) {
            resolvedRevision = request.preparation().source().resolvedRevision();
            for (String name : List.of("orangehrm.username", "orangehrm.password")) {
                secretNames.add(name);
                try (ResolvedSecret ignored = request.secretAccess().resolve(name)) {
                    // The controlled engine proves lazy named access without reading the value.
                }
            }
            OffsetDateTime now = OffsetDateTime.now();
            return new EngineExecutionResult(
                    request.executionId(), descriptor().engineName(), descriptor().engineVersion(),
                    request.preparation().workspace().workspaceId(), resolvedRevision,
                    state, now, now, Duration.ZERO);
        }
    }

    static final class ControlledSecretProvider implements ExecutionSecretProvider {
        private final List<ResolvedSecret> values = new ArrayList<>();
        private boolean fail;

        @Override
        public String providerId() {
            return "controlled";
        }

        @Override
        public ResolvedSecret resolve(Object reference) {
            if (fail) {
                throw new IllegalStateException("synthetic provider failure");
            }
            ResolvedSecret value = ResolvedSecret.from("synthetic-canary".toCharArray());
            values.add(value);
            return value;
        }
    }

    @TestConfiguration
    static class ControlledEngineConfiguration {
        @Bean
        ControlledEngine controlledEngine() {
            return new ControlledEngine();
        }

        @Bean
        OrangeHrmQualificationExecutionEngine qualificationExecutionEngine() {
            return new OrangeHrmQualificationExecutionEngine();
        }
    }
}
