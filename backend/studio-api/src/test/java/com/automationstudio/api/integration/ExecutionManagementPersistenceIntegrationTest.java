package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionTestCase;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ExecutionTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

class ExecutionManagementPersistenceIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-018b-persistence-test";
    private static final String WORKSPACE_SLUG_PREFIX = "as-018b-persistence-test-";

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionTestCaseRepository executionTestCaseRepository;

    @Autowired
    private AutomationTestCaseRepository automationTestCaseRepository;

    @Autowired
    private AutomationSuiteRepository automationSuiteRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution_test_case
                WHERE execution_id IN (
                    SELECT id FROM execution WHERE requested_by = ?
                )
                """, TEST_ACTOR);
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
        jdbcTemplate.update("""
                DELETE FROM automation_test_case
                WHERE test_suite_id IN (
                    SELECT test_suite.id
                    FROM test_suite
                    JOIN project ON project.id = test_suite.project_id
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id
                    FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id
                    FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project
                WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_SLUG_PREFIX + "%");
    }

    @Test
    void persistsSuiteSelectionSnapshotsCancellationMetadataAndCancelRequestedStatus() {
        Fixture fixture = createFixture();
        Execution execution = newExecution(fixture, ExecutionSelectionMode.SUITE);
        Map<String, Object> environmentSnapshot =
                new LinkedHashMap<>(Map.of("name", "QA", "secretReference", "vault://qa/key"));
        execution.setEnvironmentSnapshot(environmentSnapshot);
        execution.setSuiteSnapshot(Map.of("name", "Smoke", "engineType", "PLAYWRIGHT"));
        execution.setRequestSnapshot(Map.of("selectionMode", "SUITE"));
        OffsetDateTime requestedAt = OffsetDateTime.parse("2026-07-25T10:00:00Z");
        OffsetDateTime cancelledAt = OffsetDateTime.parse("2026-07-25T10:01:00Z");
        execution.claim();
        execution.requestCancellation(
                OffsetDateTime.parse("2026-01-02T10:00:00Z"),
                "operator@example.com",
                "Requested by operator");
        execution.setCancelRequestedAt(requestedAt);
        execution.setCancelledAt(cancelledAt);
        execution.setCancelledBy("quality-engineer");
        execution.setCancellationReason("Deployment started");

        environmentSnapshot.put("name", "mutated");
        UUID id = executionRepository.saveAndFlush(execution).getId();
        Execution loaded = executionRepository.findById(id).orElseThrow();

        assertThat(loaded.getSelectionMode()).isEqualTo(ExecutionSelectionMode.SUITE);
        assertThat(loaded.getStatus()).isEqualTo(ExecutionStatus.CANCEL_REQUESTED);
        assertThat(loaded.getEnvironmentSnapshot())
                .containsEntry("name", "QA")
                .containsEntry("secretReference", "vault://qa/key");
        assertThat(loaded.getSuiteSnapshot())
                .containsEntry("engineType", "PLAYWRIGHT");
        assertThat(loaded.getRequestSnapshot()).containsEntry("selectionMode", "SUITE");
        assertThat(loaded.getCancelRequestedAt()).isEqualTo(requestedAt);
        assertThat(loaded.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(loaded.getCancelledBy()).isEqualTo("quality-engineer");
        assertThat(loaded.getCancellationReason()).isEqualTo("Deployment started");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT selection_mode, status,
                       jsonb_typeof(environment_snapshot) AS environment_type,
                       jsonb_typeof(suite_snapshot) AS suite_type,
                       jsonb_typeof(request_snapshot) AS request_type
                FROM execution WHERE id = ?
                """, id))
                .containsEntry("selection_mode", "SUITE")
                .containsEntry("status", "CANCEL_REQUESTED")
                .containsEntry("environment_type", "object")
                .containsEntry("suite_type", "object")
                .containsEntry("request_type", "object");
    }

    @Test
    void persistsSelectedCasesAndRetrievesThemInSequenceOrder() {
        Fixture fixture = createFixture();
        AutomationTestCase secondCase = createTestCase(fixture.suite(), 1);
        Execution execution = newExecution(fixture, ExecutionSelectionMode.TEST_CASES);
        execution.setEnvironmentSnapshot(Map.of("name", "QA"));
        execution.setSuiteSnapshot(Map.of("name", "Smoke"));
        execution.setRequestSnapshot(Map.of(
                "selectionMode", "TEST_CASES",
                "testCaseIds", List.of(fixture.testCase().getId(), secondCase.getId())));
        execution = executionRepository.saveAndFlush(execution);

        ExecutionTestCase second = selection(execution, secondCase, 1, "Second");
        ExecutionTestCase first = selection(execution, fixture.testCase(), 0, "First");
        executionTestCaseRepository.saveAllAndFlush(List.of(second, first));

        List<ExecutionTestCase> loaded =
                executionTestCaseRepository.findByExecutionIdOrderBySequenceNumberAsc(
                        execution.getId());
        assertThat(loaded).extracting(ExecutionTestCase::getSequenceNumber)
                .containsExactly(0, 1);
        assertThat(loaded).extracting(item -> item.getAutomationTestCase().getId())
                .containsExactly(fixture.testCase().getId(), secondCase.getId());
        assertThat(loaded).extracting(item -> item.getTestCaseSnapshot().get("name"))
                .containsExactly("First", "Second");
        assertThat(loaded).allSatisfy(item -> assertThat(item.getCreatedAt()).isNotNull());
        assertThat(executionTestCaseRepository.existsByExecutionId(execution.getId())).isTrue();
        assertThat(executionTestCaseRepository.countByExecutionId(execution.getId())).isEqualTo(2);
    }

    @Test
    void scopesLookupListingAndStatusFilteringByProject() {
        Fixture firstFixture = createFixture();
        Fixture secondFixture = createFixture();
        Execution older = newExecution(firstFixture, ExecutionSelectionMode.SUITE);
        older.setRequestedAt(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        Execution newer = newExecution(firstFixture, ExecutionSelectionMode.SUITE);
        newer.setRequestedAt(OffsetDateTime.parse("2026-01-02T10:00:00Z"));
        newer.claim();
        Execution otherProject = newExecution(secondFixture, ExecutionSelectionMode.SUITE);
        executionRepository.saveAllAndFlush(List.of(older, newer, otherProject));

        assertThat(executionRepository.findByProjectIdAndId(
                firstFixture.project().getId(), otherProject.getId())).isEmpty();
        assertThat(executionRepository.findByProjectIdOrderByRequestedAtDescIdDesc(
                firstFixture.project().getId(), PageRequest.of(0, 10)).getContent())
                .extracting(Execution::getId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(executionRepository.findByProjectIdAndStatusOrderByRequestedAtDescIdDesc(
                firstFixture.project().getId(),
                ExecutionStatus.CLAIMED,
                PageRequest.of(0, 10)).getContent())
                .extracting(Execution::getId)
                .containsExactly(newer.getId());
    }

    @Test
    void lockingLookupIsProjectScopedAndLifecycleMutationIncrementsVersion() {
        Fixture fixture = createFixture();
        Fixture otherFixture = createFixture();
        Execution execution = executionRepository.saveAndFlush(
                newExecution(fixture, ExecutionSelectionMode.SUITE));
        long initialVersion = execution.getVersion();

        transactionTemplate.executeWithoutResult(status -> {
            Execution locked = executionRepository.findByProjectIdAndIdForUpdate(
                    fixture.project().getId(), execution.getId()).orElseThrow();
            assertThat(locked.getId()).isEqualTo(execution.getId());
            assertThat(executionRepository.findByProjectIdAndIdForUpdate(
                    otherFixture.project().getId(), execution.getId())).isEmpty();
            locked.claim();
        });
        entityManager.clear();

        Execution loaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ExecutionStatus.CLAIMED);
        assertThat(loaded.getVersion()).isGreaterThan(initialVersion);
    }

    @Test
    void snapshotAccessorsAreDefensiveAndNullableSnapshotsRoundTrip() {
        Fixture fixture = createFixture();
        Execution execution = newExecution(fixture, ExecutionSelectionMode.SUITE);
        execution.setEnvironmentSnapshot(Map.of("region", "eu"));
        UUID configuredId = executionRepository.saveAndFlush(execution).getId();
        UUID nullableId = executionRepository.saveAndFlush(
                newExecution(fixture, ExecutionSelectionMode.SUITE)).getId();

        Execution loaded = executionRepository.findById(configuredId).orElseThrow();
        loaded.getEnvironmentSnapshot().put("region", "mutated");
        executionRepository.flush();

        assertThat(executionRepository.findById(configuredId).orElseThrow()
                .getEnvironmentSnapshot()).containsEntry("region", "eu");
        Execution nullable = executionRepository.findById(nullableId).orElseThrow();
        assertThat(nullable.getEnvironmentSnapshot()).isNull();
        assertThat(nullable.getSuiteSnapshot()).isNull();
        assertThat(nullable.getRequestSnapshot()).isNull();
    }

    private Fixture createFixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-018B Persistence Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-018B Persistence Project " + suffix);
        Project project = projectRepository.findById(projectId).orElseThrow();

        Environment environment = new Environment();
        environment.setProject(project);
        environment.setName("AS-018B Environment " + suffix);
        environment.setBaseUrl("https://example.test");
        environment.setType(EnvironmentType.TEST);
        environment = environmentRepository.saveAndFlush(environment);

        AutomationSuite suite = new AutomationSuite();
        suite.setProject(project);
        suite.setName("AS-018B Suite " + suffix);
        suite.setEngineType("PLAYWRIGHT");
        suite.setSuiteReference("tests/" + suite.getName());
        suite = automationSuiteRepository.saveAndFlush(suite);

        AutomationTestCase testCase = createTestCase(suite, 0);
        return new Fixture(project, environment, suite, testCase);
    }

    private AutomationTestCase createTestCase(AutomationSuite suite, int position) {
        AutomationTestCase testCase = new AutomationTestCase();
        testCase.setAutomationSuite(suite);
        testCase.setName("AS-018B Case " + UUID.randomUUID());
        testCase.setCaseReference("case-" + UUID.randomUUID());
        testCase.setPosition(position);
        return automationTestCaseRepository.saveAndFlush(testCase);
    }

    private Execution newExecution(Fixture fixture, ExecutionSelectionMode selectionMode) {
        Execution execution = new Execution();
        execution.setProject(fixture.project());
        execution.setEnvironment(fixture.environment());
        execution.setAutomationSuite(fixture.suite());
        execution.setSelectionMode(selectionMode);
        execution.setRequestedBy(TEST_ACTOR);
        return execution;
    }

    private ExecutionTestCase selection(
            Execution execution,
            AutomationTestCase testCase,
            int sequence,
            String snapshotName) {
        ExecutionTestCase selected = new ExecutionTestCase();
        selected.setExecution(execution);
        selected.setAutomationTestCase(testCase);
        selected.setSequenceNumber(sequence);
        selected.setTestCaseSnapshot(Map.of(
                "id", testCase.getId().toString(),
                "name", snapshotName,
                "caseReference", testCase.getCaseReference()));
        return selected;
    }

    private record Fixture(
            Project project,
            Environment environment,
            AutomationSuite suite,
            AutomationTestCase testCase) {
    }
}
