package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.domain.ExecutionSelectionMode;
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
import com.automationstudio.api.service.command.CreateExecutionCommand;
import com.automationstudio.api.source.SourceType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
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

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution_lease
                WHERE execution_id IN (
                    SELECT e.id FROM execution e
                    JOIN project p ON p.id = e.project_id
                    JOIN workspace w ON w.id = p.workspace_id
                    WHERE w.slug LIKE ?)
                """, PREFIX + "%");
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
}
