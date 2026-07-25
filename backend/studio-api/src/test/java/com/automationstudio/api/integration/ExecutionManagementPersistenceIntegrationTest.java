package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.automationstudio.api.service.ExecutionService;
import com.automationstudio.api.service.command.CreateExecutionCommand;
import com.automationstudio.api.service.command.CancelExecutionCommand;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.exception.InvalidRequestException;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.Callable;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
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

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void applicationServiceCreatesSelectedExecutionWithImmutableSnapshots() {
        Fixture fixture = createFixture();
        AutomationTestCase secondCase = createTestCase(fixture.suite(), 1);

        Execution created = executionService.create(
                fixture.project().getId(),
                TEST_ACTOR,
                new CreateExecutionCommand(
                        fixture.environment().getId(),
                        fixture.suite().getId(),
                        ExecutionSelectionMode.TEST_CASES,
                        List.of(secondCase.getId(), fixture.testCase().getId())));

        assertThat(created.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(created.getEnvironmentSnapshot()).containsEntry("name",
                fixture.environment().getName());
        assertThat(created.getSuiteSnapshot()).containsEntry("name", fixture.suite().getName());
        assertThat(created.getRequestSnapshot())
                .containsEntry("selectionMode", "TEST_CASES")
                .containsEntry("requestedBy", TEST_ACTOR);
        assertThat(executionTestCaseRepository
                .findByExecutionIdOrderBySequenceNumberAsc(created.getId()))
                .extracting(item -> item.getAutomationTestCase().getId())
                .containsExactly(secondCase.getId(), fixture.testCase().getId());
    }

    @Test
    void applicationServiceScopesGetAndListsWithPaginationAndStatusFilter() {
        Fixture fixture = createFixture();
        Fixture otherFixture = createFixture();
        Execution first = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        Execution second = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, List.of()));
        second.claim();
        executionRepository.saveAndFlush(second);

        assertThat(executionService.get(fixture.project().getId(), first.getId()).getId())
                .isEqualTo(first.getId());
        assertThatThrownBy(() -> executionService.get(
                otherFixture.project().getId(), first.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(executionService.list(
                fixture.project().getId(), null, PageRequest.of(0, 1)).getContent())
                .hasSize(1);
        assertThat(executionService.list(
                fixture.project().getId(), ExecutionStatus.CLAIMED,
                PageRequest.of(0, 10)).getContent())
                .extracting(Execution::getId)
                .containsExactly(second.getId());
    }

    @Test
    void applicationServiceRejectsInconsistentSelection() {
        Fixture fixture = createFixture();

        assertThatThrownBy(() -> executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(
                        fixture.environment().getId(),
                        fixture.suite().getId(),
                        ExecutionSelectionMode.TEST_CASES,
                        List.of())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void executionRestApiCreatesGetsAndListsAgainstPostgreSql() throws Exception {
        Fixture fixture = createFixture();
        String path = "/api/v1/projects/" + fixture.project().getId() + "/executions";
        Map<String, Object> request = Map.of(
                "environmentId", fixture.environment().getId(),
                "automationSuiteId", fixture.suite().getId(),
                "selectionMode", "SUITE");

        String response = mockMvc.perform(post(path)
                        .header("X-Requested-By", TEST_ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get(path + "/" + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(fixture.project().getId().toString()));
        mockMvc.perform(get(path).param("status", "PENDING").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(executionId));
    }

    @Test
    void suiteHttpJourneyPersistsAndReturnsCompleteCancellationContract() throws Exception {
        Fixture fixture = createFixture();
        String collectionPath = "/api/v1/projects/" + fixture.project().getId() + "/executions";
        String createResponse = mockMvc.perform(post(collectionPath)
                        .header("X-Requested-By", TEST_ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "environmentId", fixture.environment().getId(),
                                "automationSuiteId", fixture.suite().getId(),
                                "selectionMode", "SUITE"))))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.selectionMode").value("SUITE"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.cancelRequestedAt").isEmpty())
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.cancelledBy").isEmpty())
                .andExpect(jsonPath("$.cancellationReason").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(createResponse).get("id").asText();
        String itemPath = collectionPath + "/" + executionId;

        mockMvc.perform(get(itemPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));

        String cancellationResponse = mockMvc.perform(post(itemPath + "/cancel")
                        .header("If-Match", "\"0\"")
                        .header("X-Requested-By", "suite-operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  release freeze  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestedAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelledBy").value("suite-operator"))
                .andExpect(jsonPath("$.cancellationReason").value("release freeze"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();

        String persistedResponse = mockMvc.perform(get(itemPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("suite-operator"))
                .andExpect(jsonPath("$.cancellationReason").value("release freeze"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(OffsetDateTime.parse(
                objectMapper.readTree(persistedResponse).get("cancelRequestedAt").asText()))
                .isCloseTo(
                        OffsetDateTime.parse(objectMapper.readTree(cancellationResponse)
                                .get("cancelRequestedAt").asText()),
                        org.assertj.core.api.Assertions.within(
                                1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(OffsetDateTime.parse(
                objectMapper.readTree(persistedResponse).get("cancelledAt").asText()))
                .isCloseTo(
                        OffsetDateTime.parse(objectMapper.readTree(cancellationResponse)
                                .get("cancelledAt").asText()),
                        org.assertj.core.api.Assertions.within(
                                1, java.time.temporal.ChronoUnit.MICROS));
        Execution persisted = executionRepository.findById(UUID.fromString(executionId))
                .orElseThrow();
        assertThat(persisted.getCancelRequestedAt()).isEqualTo(persisted.getCancelledAt());
        assertThat(persisted.getFinishedAt()).isEqualTo(persisted.getCancelledAt());
    }

    @Test
    void selectedCaseHttpJourneyPreservesRequestTimeSnapshotsAndHidesInternals(
            CapturedOutput output) throws Exception {
        Fixture fixture = createFixture();
        AutomationTestCase secondCase = createTestCase(fixture.suite(), 1);
        fixture.environment().setConfiguration(Map.of(
                "browser", "chromium",
                "nested", Map.of("password", "resolved-environment-secret")));
        fixture.environment().setSecretReferences(Map.of("login", "vault://qa/login"));
        environmentRepository.saveAndFlush(fixture.environment());
        fixture.suite().setConfiguration(Map.of(
                "workers", 2,
                "items", List.of(Map.of("api_key", "resolved-suite-secret"), "safe")));
        automationSuiteRepository.saveAndFlush(fixture.suite());
        fixture.testCase().setConfiguration(Map.of(
                "safe", "original", "accessToken", "resolved-case-secret"));
        automationTestCaseRepository.saveAndFlush(fixture.testCase());

        String path = "/api/v1/projects/" + fixture.project().getId() + "/executions";
        String response = mockMvc.perform(post(path)
                        .header("X-Requested-By", TEST_ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "environmentId", fixture.environment().getId(),
                                "automationSuiteId", fixture.suite().getId(),
                                "selectionMode", "TEST_CASES",
                                "testCaseIds",
                                List.of(secondCase.getId(), fixture.testCase().getId())))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.selectionMode").value("TEST_CASES"))
                .andReturn().getResponse().getContentAsString();
        UUID executionId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        assertThat(response)
                .doesNotContain("environmentSnapshot", "suiteSnapshot", "requestSnapshot",
                        "testCaseSnapshot", "resolved-environment-secret",
                        "resolved-suite-secret", "resolved-case-secret");
        List<ExecutionTestCase> selections =
                executionTestCaseRepository.findByExecutionIdOrderBySequenceNumberAsc(executionId);
        assertThat(selections).extracting(row -> row.getAutomationTestCase().getId())
                .containsExactly(secondCase.getId(), fixture.testCase().getId());
        Execution persisted = executionRepository.findById(executionId).orElseThrow();
        assertThat(persisted.getEnvironmentSnapshot().toString())
                .contains("vault://qa/login", "chromium")
                .doesNotContain("resolved-environment-secret");
        assertThat(persisted.getSuiteSnapshot().toString())
                .doesNotContain("resolved-suite-secret");
        assertThat(selections.get(1).getTestCaseSnapshot().toString())
                .contains("original")
                .doesNotContain("resolved-case-secret");

        Environment mutableEnvironment =
                environmentRepository.findById(fixture.environment().getId()).orElseThrow();
        AutomationSuite mutableSuite =
                automationSuiteRepository.findById(fixture.suite().getId()).orElseThrow();
        AutomationTestCase mutableCase =
                automationTestCaseRepository.findById(fixture.testCase().getId()).orElseThrow();
        mutableEnvironment.setName("mutated environment");
        mutableSuite.setName("mutated suite");
        mutableCase.setName("mutated case");
        environmentRepository.saveAndFlush(mutableEnvironment);
        automationSuiteRepository.saveAndFlush(mutableSuite);
        automationTestCaseRepository.saveAndFlush(mutableCase);
        entityManager.clear();

        Execution reloaded = executionRepository.findById(executionId).orElseThrow();
        List<ExecutionTestCase> reloadedSelections =
                executionTestCaseRepository.findByExecutionIdOrderBySequenceNumberAsc(executionId);
        assertThat(reloaded.getEnvironmentSnapshot().get("name"))
                .isNotEqualTo("mutated environment");
        assertThat(reloaded.getSuiteSnapshot().get("name")).isNotEqualTo("mutated suite");
        assertThat(reloadedSelections.get(1).getTestCaseSnapshot().get("name"))
                .isNotEqualTo("mutated case");
        mockMvc.perform(get(path + "/" + executionId))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("Snapshot", "resolved-", "vault://"));
        assertThat(output.getAll()).doesNotContain(
                "resolved-environment-secret",
                "resolved-suite-secret",
                "resolved-case-secret",
                "vault://qa/login");
    }

    @Test
    void cancellationPreconditionsReturnSafeErrorsWithoutMutation() throws Exception {
        Fixture fixture = createFixture();
        Execution execution = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        String path = "/api/v1/projects/" + fixture.project().getId()
                + "/executions/" + execution.getId() + "/cancel";

        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"resolved-secret\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.status").value(428))
                .andExpect(jsonPath("$.error").value("Precondition Required"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("resolved-secret", "stackTrace"));

        for (String malformed : List.of(
                "0", "W/\"0\"", "*", "\"0\", \"1\"", "\"\"",
                "\"-1\"", "\"abc\"", "\"9223372036854775808\"")) {
            mockMvc.perform(post(path).header("If-Match", malformed)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"resolved-secret\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.path").value(path))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("resolved-secret", "stackTrace"));
        }
        Execution unchanged = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(unchanged.getCancelRequestedAt()).isNull();
        assertThat(unchanged.getVersion()).isZero();
    }

    @Test
    void staleAndCrossProjectCancellationReturnSafeErrorsAndPreserveTarget() throws Exception {
        Fixture owner = createFixture();
        Fixture outsider = createFixture();
        Execution execution = executionService.create(
                owner.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(owner.environment().getId(), owner.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        String ownerPath = "/api/v1/projects/" + owner.project().getId()
                + "/executions/" + execution.getId();
        String outsiderPath = "/api/v1/projects/" + outsider.project().getId()
                + "/executions/" + execution.getId();

        mockMvc.perform(post(ownerPath + "/cancel").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mockMvc.perform(get(outsiderPath))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(post(outsiderPath + "/cancel").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/api/v1/projects/" + outsider.project().getId() + "/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        Execution unchanged = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(unchanged.getCancelRequestedAt()).isNull();
        assertThat(unchanged.getVersion()).isZero();
    }

    @Test
    void repeatedCancellationIsIdempotentButStillRejectsOldVersions() throws Exception {
        Fixture fixture = createFixture();
        for (boolean cooperative : List.of(false, true)) {
            Execution execution = executionService.create(
                    fixture.project().getId(), TEST_ACTOR,
                    new CreateExecutionCommand(
                            fixture.environment().getId(), fixture.suite().getId(),
                            ExecutionSelectionMode.SUITE, null));
            if (cooperative) {
                execution.claim();
                execution = executionRepository.saveAndFlush(execution);
            }
            String path = "/api/v1/projects/" + fixture.project().getId()
                    + "/executions/" + execution.getId() + "/cancel";
            long startingVersion = execution.getVersion();
            String first = mockMvc.perform(post(path)
                            .header("If-Match", "\"" + startingVersion + "\"")
                            .header("X-Requested-By", "original-actor")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"original reason\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long currentVersion = objectMapper.readTree(first).get("version").asLong();
            String originalStatus = cooperative ? "CANCEL_REQUESTED" : "CANCELLED";

            mockMvc.perform(post(path).header("If-Match", "\"" + currentVersion + "\"")
                            .header("X-Requested-By", "replacement-actor")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"replacement reason\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(originalStatus))
                    .andExpect(jsonPath("$.cancelledBy").value("original-actor"))
                    .andExpect(jsonPath("$.cancellationReason").value("original reason"))
                    .andExpect(jsonPath("$.version").value(currentVersion));
            mockMvc.perform(post(path).header("If-Match", "\"" + startingVersion + "\"")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict());
        }
    }

    @Test
    void terminalCancellationConflictsPreserveResultsAndLifecycleData() throws Exception {
        Fixture fixture = createFixture();
        for (ExecutionStatus terminalStatus : List.of(
                ExecutionStatus.PASSED, ExecutionStatus.FAILED, ExecutionStatus.ERROR)) {
            Execution execution = executionService.create(
                    fixture.project().getId(), TEST_ACTOR,
                    new CreateExecutionCommand(
                            fixture.environment().getId(), fixture.suite().getId(),
                            ExecutionSelectionMode.SUITE, null));
            execution.claim();
            OffsetDateTime startedAt = OffsetDateTime.now().minusMinutes(2);
            execution.start(startedAt);
            execution.setTotalTests(3);
            execution.setPassedTests(2);
            execution.setFailedTests(1);
            execution.setErrorMessage("safe runner result");
            OffsetDateTime finishedAt = OffsetDateTime.now().minusMinutes(1);
            switch (terminalStatus) {
                case PASSED -> execution.markPassed(finishedAt);
                case FAILED -> execution.markFailed(finishedAt);
                case ERROR -> execution.markError(finishedAt);
                default -> throw new IllegalStateException();
            }
            execution = executionRepository.saveAndFlush(execution);
            UUID executionId = execution.getId();
            entityManager.clear();
            Execution terminal = executionRepository.findById(executionId).orElseThrow();
            long version = terminal.getVersion();
            OffsetDateTime persistedStartedAt = terminal.getStartedAt();
            OffsetDateTime persistedFinishedAt = terminal.getFinishedAt();
            String path = "/api/v1/projects/" + fixture.project().getId()
                    + "/executions/" + executionId + "/cancel";

            mockMvc.perform(post(path).header("If-Match", "\"" + version + "\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"must-not-persist\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("must-not-persist", "safe runner result"));
            Execution unchanged = executionRepository.findById(executionId).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(terminalStatus);
            assertThat(unchanged.getStartedAt()).isEqualTo(persistedStartedAt);
            assertThat(unchanged.getFinishedAt()).isEqualTo(persistedFinishedAt);
            assertThat(unchanged.getTotalTests()).isEqualTo(3);
            assertThat(unchanged.getCancelRequestedAt()).isNull();
            assertThat(unchanged.getVersion()).isEqualTo(version);
        }
    }

    @Test
    void cancellationEndpointImmediatelyCancelsPendingAndEnforcesIfMatch() throws Exception {
        Fixture fixture = createFixture();
        Execution execution = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        String path = "/api/v1/projects/" + fixture.project().getId()
                + "/executions/" + execution.getId() + "/cancel";

        mockMvc.perform(post(path).header("If-Match", "\"0\"")
                        .header("X-Requested-By", TEST_ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  operator request  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value(TEST_ACTOR))
                .andExpect(jsonPath("$.cancellationReason").value("operator request"))
                .andExpect(jsonPath("$.version").value(1));

        Execution cancelled = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(cancelled.getCancelRequestedAt()).isEqualTo(cancelled.getCancelledAt());
        assertThat(cancelled.getFinishedAt()).isEqualTo(cancelled.getCancelledAt());

        mockMvc.perform(post(path).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(path).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(executionRepository.findById(execution.getId()).orElseThrow().getVersion())
                .isEqualTo(1);
    }

    @Test
    void cooperativeAndTerminalCancellationPersistExpectedState() {
        Fixture fixture = createFixture();
        Execution active = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        active.claim();
        active = executionRepository.saveAndFlush(active);
        long claimedVersion = active.getVersion();

        Execution requested = executionService.cancel(
                fixture.project().getId(), active.getId(), claimedVersion, TEST_ACTOR,
                new CancelExecutionCommand("cooperative"));
        assertThat(requested.getStatus()).isEqualTo(ExecutionStatus.CANCEL_REQUESTED);
        assertThat(requested.getCancelRequestedAt()).isNotNull();
        assertThat(requested.getCancelledAt()).isNull();
        assertThat(requested.getFinishedAt()).isNull();
        assertThat(requested.getVersion()).isEqualTo(claimedVersion + 1);

        Execution terminal = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        terminal.claim();
        terminal.start(OffsetDateTime.now().minusMinutes(2));
        terminal.markPassed(OffsetDateTime.now().minusMinutes(1));
        terminal = executionRepository.saveAndFlush(terminal);
        UUID terminalId = terminal.getId();
        long terminalVersion = terminal.getVersion();

        assertThatThrownBy(() -> executionService.cancel(
                fixture.project().getId(), terminalId, terminalVersion, TEST_ACTOR,
                new CancelExecutionCommand(null)))
                .isInstanceOf(com.automationstudio.api.exception.ResourceConflictException.class);
        Execution unchanged = executionRepository.findById(terminalId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(unchanged.getVersion()).isEqualTo(terminalVersion);
    }

    @Test
    void concurrentCancellationWithSameVersionAllowsOnlyOneMutation() throws Exception {
        Fixture fixture = createFixture();
        Execution execution = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> command = () -> {
            start.await();
            try {
                executionService.cancel(
                        fixture.project().getId(), execution.getId(), 0, TEST_ACTOR,
                        new CancelExecutionCommand(null));
                return true;
            } catch (com.automationstudio.api.exception.ResourceConflictException exception) {
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(command);
            var second = executor.submit(command);
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }

        Execution persisted = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(persisted.getVersion()).isEqualTo(1);
    }

    @Test
    void cancellationRacingWithClaimHasOneWinnerAndNoLostUpdate() throws Exception {
        Fixture fixture = createFixture();
        Execution execution = executionService.create(
                fixture.project().getId(), TEST_ACTOR,
                new CreateExecutionCommand(fixture.environment().getId(), fixture.suite().getId(),
                        ExecutionSelectionMode.SUITE, null));
        CyclicBarrier loaded = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        Callable<Void> cancel = () -> {
            raceLifecycleTransaction(execution.getId(), loaded, persisted ->
                    persisted.requestCancellation(
                            OffsetDateTime.parse("2026-07-25T12:00:00Z"),
                            "race-canceller", "race cancellation"), successes, conflicts);
            return null;
        };
        Callable<Void> claim = () -> {
            raceLifecycleTransaction(
                    execution.getId(), loaded, Execution::claim, successes, conflicts);
            return null;
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(cancel);
            var lifecycle = executor.submit(claim);
            cancellation.get();
            lifecycle.get();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        Execution persisted = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(persisted.getVersion()).isEqualTo(1);
        assertThat(persisted.getStatus())
                .isIn(ExecutionStatus.CANCELLED, ExecutionStatus.CLAIMED);
        if (persisted.getStatus() == ExecutionStatus.CANCELLED) {
            assertThat(persisted.getCancelledBy()).isEqualTo("race-canceller");
            assertThat(persisted.getCancellationReason()).isEqualTo("race cancellation");
            assertThat(persisted.getCancelRequestedAt()).isEqualTo(persisted.getCancelledAt());
        } else {
            assertThat(persisted.getCancelRequestedAt()).isNull();
            assertThat(persisted.getCancelledAt()).isNull();
            assertThat(persisted.getCancelledBy()).isNull();
            assertThat(persisted.getCancellationReason()).isNull();
        }
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

    private void raceLifecycleTransaction(
            UUID executionId,
            CyclicBarrier loaded,
            java.util.function.Consumer<Execution> mutation,
            AtomicInteger successes,
            AtomicInteger conflicts) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Execution persisted = executionRepository.findById(executionId).orElseThrow();
                await(loaded);
                mutation.accept(persisted);
                executionRepository.saveAndFlush(persisted);
            });
            successes.incrementAndGet();
        } catch (ObjectOptimisticLockingFailureException exception) {
            conflicts.incrementAndGet();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Race test interrupted", exception);
        } catch (BrokenBarrierException exception) {
            throw new IllegalStateException("Race test barrier failed", exception);
        }
    }

    private record Fixture(
            Project project,
            Environment environment,
            AutomationSuite suite,
            AutomationTestCase testCase) {
    }
}
