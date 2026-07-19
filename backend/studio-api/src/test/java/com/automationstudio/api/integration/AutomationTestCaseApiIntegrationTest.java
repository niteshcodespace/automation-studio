package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.domain.SuiteType;
import com.automationstudio.api.dto.automation.suite.UpdateAutomationSuiteRequest;
import com.automationstudio.api.dto.automation.testcase.CreateAutomationTestCaseRequest;
import com.automationstudio.api.dto.automation.testcase.UpdateAutomationTestCaseRequest;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Workspace;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.repository.WorkspaceRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class AutomationTestCaseApiIntegrationTest extends IntegrationTestBase {

    private static final String WORKSPACE_SLUG_PREFIX = "as-016f-api-test-";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private AutomationSuiteRepository suiteRepository;
    @Autowired private AutomationTestCaseRepository caseRepository;

    @AfterEach
    void cleanDatabase() {
        String pattern = WORKSPACE_SLUG_PREFIX + "%";
        jdbcTemplate.update("""
                DELETE FROM automation_test_case WHERE test_suite_id IN (
                    SELECT test_suite.id FROM test_suite
                    JOIN project ON project.id = test_suite.project_id
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?)
                """, pattern);
        jdbcTemplate.update("""
                DELETE FROM execution WHERE project_id IN (
                    SELECT project.id FROM project JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?)
                """, pattern);
        jdbcTemplate.update("""
                DELETE FROM environment WHERE project_id IN (
                    SELECT project.id FROM project JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?)
                """, pattern);
        jdbcTemplate.update("""
                DELETE FROM test_suite WHERE project_id IN (
                    SELECT project.id FROM project JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?)
                """, pattern);
        jdbcTemplate.update("""
                DELETE FROM project WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?)
                """, pattern);
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE ?", pattern);
    }

    @Test
    void createCompleteDefaultNormalizedAndPostgreSqlState() throws Exception {
        Project project = saveProject("create");
        AutomationSuite suite = saveSuite(project, "Create suite");
        Map<String, Object> configuration = mixedConfiguration();
        CreateAutomationTestCaseRequest complete = new CreateAutomationTestCaseRequest(
                "Complete case", "Description", "native complete", configuration,
                AutomationTestCaseStatus.INACTIVE);

        MvcResult completeResult = mockMvc.perform(post(casesPath(project, suite))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(complete)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.automationSuiteId").value(suite.getId().toString()))
                .andExpect(jsonPath("$.name").value("Complete case"))
                .andExpect(jsonPath("$.description").value("Description"))
                .andExpect(jsonPath("$.caseReference").value("native complete"))
                .andExpect(jsonPath("$.configuration.nested.region").value("eu"))
                .andExpect(jsonPath("$.configuration.steps[1]").value("submit"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists()).andReturn();
        UUID completeId = responseId(completeResult);
        AutomationTestCase persisted = caseRepository.findById(completeId).orElseThrow();
        assertThat(persisted.getAutomationSuite().getId()).isEqualTo(suite.getId());
        assertThat(persisted.getConfiguration()).containsKey("nested");
        assertThat(persisted.getStatus()).isEqualTo(AutomationTestCaseStatus.INACTIVE);
        assertThat(persisted.getPosition()).isZero();
        assertThat(persisted.getVersion()).isZero();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(configuration) FROM automation_test_case WHERE id = ?",
                String.class, completeId)).isEqualTo("object");

        UUID defaultId = createCase(project, suite, "Default case", "default-ref", null, null);
        AutomationTestCase defaultCase = caseRepository.findById(defaultId).orElseThrow();
        assertThat(defaultCase.getDescription()).isNull();
        assertThat(defaultCase.getConfiguration()).isNull();
        assertThat(defaultCase.getStatus()).isEqualTo(AutomationTestCaseStatus.ACTIVE);
        assertThat(defaultCase.getPosition()).isEqualTo(1);

        UUID normalizedId = createCase(project, suite, "  Mixed  Case  ",
                "  Native  Reference  ", null, null);
        AutomationTestCase normalized = caseRepository.findById(normalizedId).orElseThrow();
        assertThat(normalized.getName()).isEqualTo("Mixed  Case");
        assertThat(normalized.getCaseReference()).isEqualTo("Native  Reference");
    }

    @Test
    void createEnforcesOwnershipValidationUniquenessAndPositionExhaustion() throws Exception {
        Project project = saveProject("create-errors");
        Project otherProject = saveProject("create-errors-other");
        AutomationSuite suite = saveSuite(project, "Primary suite");
        AutomationSuite otherSuite = saveSuite(otherProject, "Other suite");
        CreateAutomationTestCaseRequest request = request("Shared", "shared-ref", null);

        UUID missingProjectId = UUID.randomUUID();
        UUID missingSuiteId = UUID.randomUUID();
        assertApiError(mockMvc.perform(post(casesPath(missingProjectId, suite.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))), 404, "Not Found",
                "Project not found with id: " + missingProjectId);
        assertApiError(mockMvc.perform(post(casesPath(project.getId(), missingSuiteId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))), 404, "Not Found",
                missingSuite(project.getId(), missingSuiteId));
        assertApiError(mockMvc.perform(post(casesPath(project.getId(), otherSuite.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))), 404, "Not Found",
                missingSuite(project.getId(), otherSuite.getId()));
        assertThat(caseRepository.count()).isZero();

        createCase(project, suite, "Shared", "shared-ref", null, null);
        assertApiError(postCase(project, suite, request("Shared", "different-ref", null)),
                409, "Conflict", duplicateName("Shared", suite.getId()));
        assertApiError(postCase(project, suite, request("Different", "shared-ref", null)),
                409, "Conflict", duplicateReference("shared-ref", suite.getId()));
        createCase(otherProject, otherSuite, "Shared", "shared-ref", null, null);

        Map<String, String> invalidBodies = Map.of(
                "{\"name\":\" \",\"caseReference\":\"ref\"}",
                "name: Automation test case name must not be blank",
                "{\"name\":\"" + "n".repeat(151) + "\",\"caseReference\":\"ref\"}",
                "name: Automation test case name must not exceed 150 characters",
                "{\"name\":\"Case\",\"caseReference\":\" \"}",
                "caseReference: Automation test case reference must not be blank",
                "{\"name\":\"Case\",\"caseReference\":\"" + "r".repeat(301) + "\"}",
                "caseReference: Automation test case reference must not exceed 300 characters",
                "{\"name\":\"Case\",\"caseReference\":\"ref\",\"status\":\"UNKNOWN\"}",
                "Malformed or unreadable request body",
                "{\"name\":\"Case\",\"caseReference\":\"ref\",\"configuration\":[]}",
                "Malformed or unreadable request body",
                "{\"name\":\"Case\",\"caseReference\":\"ref\"",
                "Malformed or unreadable request body");
        long before = caseRepository.count();
        for (Map.Entry<String, String> invalid : invalidBodies.entrySet()) {
            assertApiError(mockMvc.perform(post(casesPath(project, suite))
                    .contentType(MediaType.APPLICATION_JSON).content(invalid.getKey())),
                    400, "Bad Request", invalid.getValue());
        }
        assertApiError(mockMvc.perform(post("/api/v1/projects/not-a-uuid/automation-suites/"
                        + suite.getId() + "/test-cases").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                , 400, "Bad Request", invalidUuidMessage("projectId"));
        assertThat(caseRepository.count()).isEqualTo(before);

        AutomationTestCase maximum = newCase(suite, "Maximum", "maximum-ref", Integer.MAX_VALUE);
        caseRepository.saveAndFlush(maximum);
        assertApiError(postCase(project, suite, request("Exhausted", "exhausted-ref", null)),
                409, "Conflict",
                "No additional test-case position is available in suite: " + suite.getId());
        assertThat(caseRepository.existsByAutomationSuiteIdAndName(suite.getId(), "Exhausted"))
                .isFalse();
    }

    @Test
    void sequentialCreateUsesMaxPlusOneAndDoesNotReuseDeletedGap() throws Exception {
        Project project = saveProject("append");
        AutomationSuite suite = saveSuite(project, "Append suite");
        UUID first = createCase(project, suite, "First", "first", null, null);
        UUID second = createCase(project, suite, "Second", "second", null, null);
        UUID third = createCase(project, suite, "Third", "third", null, null);
        assertThat(caseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(suite.getId()))
                .extracting(AutomationTestCase::getPosition).containsExactly(0, 1, 2);
        mockMvc.perform(delete(casePath(project, suite, second))).andExpect(status().isNoContent());
        UUID fourth = createCase(project, suite, "Fourth", "fourth", null, null);
        assertThat(caseRepository.findById(fourth).orElseThrow().getPosition()).isEqualTo(3);
        assertThat(caseRepository.findById(first)).isPresent();
        assertThat(caseRepository.findById(third)).isPresent();
    }

    @Test
    void getEnforcesCompleteOwnershipAndReturnsNullableFields() throws Exception {
        Project project = saveProject("get");
        Project otherProject = saveProject("get-other");
        AutomationSuite suite = saveSuite(project, "Get suite");
        AutomationSuite otherSuite = saveSuite(otherProject, "Other get suite");
        UUID caseId = createCase(project, suite, "Get case", "get-ref", null, null);
        CaseSnapshot persisted = snapshot(caseId);

        mockMvc.perform(get(casePath(project, suite, caseId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(caseId.toString()))
                .andExpect(jsonPath("$.automationSuiteId").value(suite.getId().toString()))
                .andExpect(jsonPath("$.name").value(persisted.name()))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.caseReference").value(persisted.caseReference()))
                .andExpect(jsonPath("$.configuration").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.status").value(persisted.status().name()))
                .andExpect(jsonPath("$.position").value(persisted.position()))
                .andExpect(jsonPath("$.version").value(persisted.version()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
        UUID missingProject = UUID.randomUUID();
        UUID missingSuite = UUID.randomUUID();
        UUID missingCase = UUID.randomUUID();
        for (ErrorScenario scenario : List.of(
                new ErrorScenario(casePath(missingProject, suite.getId(), caseId),
                        missingProject(missingProject)),
                new ErrorScenario(casePath(project.getId(), missingSuite, caseId),
                        missingSuite(project.getId(), missingSuite)),
                new ErrorScenario(casePath(project.getId(), suite.getId(), missingCase),
                        missingCase(suite.getId(), missingCase)),
                new ErrorScenario(casePath(project.getId(), otherSuite.getId(), caseId),
                        missingSuite(project.getId(), otherSuite.getId())),
                new ErrorScenario(casePath(otherProject.getId(), suite.getId(), caseId),
                        missingSuite(otherProject.getId(), suite.getId())))) {
            assertApiError(mockMvc.perform(get(scenario.path())), 404, "Not Found",
                    scenario.message());
        }
        assertApiError(mockMvc.perform(get(casesPath(project, suite) + "/not-a-uuid")),
                400, "Bad Request", invalidUuidMessage("caseId"));
    }

    @Test
    void listUsesDefaultOrderPaginationExplicitSortAndStatusFilters() throws Exception {
        Project project = saveProject("list");
        AutomationSuite suite = saveSuite(project, "List suite");
        AutomationSuite otherSuite = saveSuite(project, "Other list suite");
        createCase(project, suite, "Charlie", "c", null, AutomationTestCaseStatus.ACTIVE);
        createCase(project, suite, "Alpha", "a", null, AutomationTestCaseStatus.INACTIVE);
        createCase(project, suite, "Bravo", "b", null, AutomationTestCaseStatus.ARCHIVED);
        createCase(project, otherSuite, "Other", "other", null, AutomationTestCaseStatus.ARCHIVED);

        mockMvc.perform(get(casesPath(project, suite)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].name").value("Charlie"))
                .andExpect(jsonPath("$.content[1].name").value("Alpha"))
                .andExpect(jsonPath("$.content[2].name").value("Bravo"))
                .andExpect(jsonPath("$.number").value(0)).andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
        mockMvc.perform(get(casesPath(project, suite)).param("page", "0").param("size", "2")
                        .param("sort", "name,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Bravo"))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get(casesPath(project, suite)).param("page", "1").param("size", "2")
                        .param("sort", "name,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("Charlie"));
        for (AutomationTestCaseStatus status : AutomationTestCaseStatus.values()) {
            mockMvc.perform(get(casesPath(project, suite)).param("status", status.name()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].status").value(status.name()));
        }
        assertApiError(mockMvc.perform(get(casesPath(project, suite)).param("status", "UNKNOWN")),
                400, "Bad Request", "Invalid value for parameter 'status'");
    }

    @Test
    void listRejectsMissingAndCrossProjectHierarchyWithoutMutation() throws Exception {
        Project owner = saveProject("list-scope-owner");
        Project other = saveProject("list-scope-other");
        AutomationSuite suite = saveSuite(owner, "List scoped suite");
        UUID caseId = createCase(owner, suite, "Scoped", "scoped", null, null);
        CaseSnapshot before = snapshot(caseId);
        UUID missingProject = UUID.randomUUID();
        UUID missingSuite = UUID.randomUUID();
        for (ErrorScenario scenario : List.of(
                new ErrorScenario(casesPath(missingProject, suite.getId()),
                        missingProject(missingProject)),
                new ErrorScenario(casesPath(owner.getId(), missingSuite),
                        missingSuite(owner.getId(), missingSuite)),
                new ErrorScenario(casesPath(other.getId(), suite.getId()),
                        missingSuite(other.getId(), suite.getId())))) {
            assertApiError(mockMvc.perform(get(scenario.path())), 404, "Not Found",
                    scenario.message());
            assertThat(snapshot(caseId)).as("failed scoped list must not mutate case").isEqualTo(before);
        }
        Map<String, String> invalidListPaths = Map.of(
                "/api/v1/projects/not-a-uuid/automation-suites/" + suite.getId() + "/test-cases",
                "Invalid value for parameter 'projectId'",
                "/api/v1/projects/" + owner.getId() + "/automation-suites/not-a-uuid/test-cases",
                "Invalid value for parameter 'suiteId'");
        for (Map.Entry<String, String> scenario : invalidListPaths.entrySet()) {
            String path = scenario.getKey();
            assertApiError(mockMvc.perform(get(path)), 400, "Bad Request", scenario.getValue());
            assertThat(snapshot(caseId)).as("invalid UUID list must not mutate case").isEqualTo(before);
            assertThat(countCases(suite.getId())).as("invalid UUID list must preserve row count")
                    .isEqualTo(1);
        }
    }

    @Test
    void updateChangesOnlyMutableFieldsAndRollsBackDuplicateOrInvalidRequests() throws Exception {
        Project project = saveProject("update");
        AutomationSuite suite = saveSuite(project, "Update suite");
        AutomationSuite otherSuite = saveSuite(project, "Update other suite");
        UUID caseId = createCase(project, suite, "Original", "original", Map.of("old", true),
                AutomationTestCaseStatus.INACTIVE);
        createCase(project, suite, "Duplicate", "duplicate", null, null);
        createCase(project, otherSuite, "Updated", "updated", null, null);
        AutomationTestCase before = caseRepository.findById(caseId).orElseThrow();
        long version = before.getVersion();
        OffsetDateTime createdAt = before.getCreatedAt();
        OffsetDateTime updatedAt = before.getUpdatedAt();
        String raw = """
                {"name":"Updated","description":"Changed","caseReference":"updated",
                 "configuration":{"browser":"firefox"},"id":"%s",
                 "automationSuiteId":"%s","status":"ARCHIVED","position":99,"version":88,
                 "createdAt":"2000-01-01T00:00:00Z","updatedAt":"2000-01-01T00:00:00Z"}
                """.formatted(UUID.randomUUID(), otherSuite.getId());
        mockMvc.perform(put(casePath(project, suite, caseId)).contentType(MediaType.APPLICATION_JSON)
                        .content(raw)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.version").value(version + 1));
        AutomationTestCase updated = caseRepository.findById(caseId).orElseThrow();
        assertThat(updated.getAutomationSuite().getId()).isEqualTo(suite.getId());
        assertThat(updated.getStatus()).isEqualTo(AutomationTestCaseStatus.INACTIVE);
        assertThat(updated.getPosition()).isZero();
        assertThat(updated.getVersion()).isEqualTo(version + 1);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updatedAt);
        assertThat(updated.getConfiguration()).containsEntry("browser", "firefox");

        CaseSnapshot duplicateNameBefore = snapshot(caseId);
        long duplicateNameCount = countCases(suite.getId());
        assertApiError(putCase(project, suite, caseId,
                new UpdateAutomationTestCaseRequest("Duplicate", "changed description",
                        "unique-name-conflict", Map.of("changed", "name"))),
                409, "Conflict", duplicateName("Duplicate", suite.getId()));
        assertThat(snapshot(caseId)).as("duplicate-name PUT must roll back completely")
                .isEqualTo(duplicateNameBefore);
        assertThat(countCases(suite.getId())).as("duplicate-name PUT must not insert a row")
                .isEqualTo(duplicateNameCount);
        CaseSnapshot duplicateReferenceBefore = snapshot(caseId);
        long duplicateReferenceCount = countCases(suite.getId());
        assertApiError(putCase(project, suite, caseId,
                new UpdateAutomationTestCaseRequest("Changed name", "changed description",
                        "duplicate", Map.of("changed", "reference"))),
                409, "Conflict", duplicateReference("duplicate", suite.getId()));
        assertThat(snapshot(caseId)).as("duplicate-reference PUT must roll back completely")
                .isEqualTo(duplicateReferenceBefore);
        assertThat(countCases(suite.getId())).as("duplicate-reference PUT must not insert a row")
                .isEqualTo(duplicateReferenceCount);
        CaseSnapshot validationBefore = snapshot(caseId);
        long validationCount = countCases(suite.getId());
        assertApiError(mockMvc.perform(put(casePath(project, suite, caseId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","description":"must not persist",
                                 "caseReference":"changed-validation",
                                 "configuration":{"must":"not persist"}}
                                """)),
                400, "Bad Request", "name: Automation test case name must not be blank");
        assertThat(snapshot(caseId)).as("invalid PUT must roll back completely")
                .isEqualTo(validationBefore);
        assertThat(countCases(suite.getId())).as("invalid PUT must not insert a row")
                .isEqualTo(validationCount);
    }

    @Test
    void updateRejectsEveryInvalidHierarchyAndUuidWithoutMutation() throws Exception {
        Project owner = saveProject("put-scope-owner");
        Project other = saveProject("put-scope-other");
        AutomationSuite suite = saveSuite(owner, "PUT owner suite");
        AutomationSuite sibling = saveSuite(owner, "PUT sibling suite");
        AutomationSuite foreignSuite = saveSuite(other, "PUT foreign suite");
        UUID caseId = createCase(owner, suite, "PUT case", "put-case", null, null);
        UUID foreignId = createCase(other, foreignSuite, "Foreign PUT", "foreign-put", null, null);
        UpdateAutomationTestCaseRequest request = new UpdateAutomationTestCaseRequest(
                "Changed", "changed", "changed-ref", Map.of("changed", true));
        UUID missingProject = UUID.randomUUID();
        UUID missingSuite = UUID.randomUUID();
        UUID missingCase = UUID.randomUUID();
        for (ErrorScenario scenario : List.of(
                new ErrorScenario(casePath(missingProject, suite.getId(), caseId),
                        missingProject(missingProject)),
                new ErrorScenario(casePath(owner.getId(), missingSuite, caseId),
                        missingSuite(owner.getId(), missingSuite)),
                new ErrorScenario(casePath(owner.getId(), suite.getId(), missingCase),
                        missingCase(suite.getId(), missingCase)),
                new ErrorScenario(casePath(owner.getId(), sibling.getId(), caseId),
                        missingCase(sibling.getId(), caseId)),
                new ErrorScenario(casePath(owner.getId(), foreignSuite.getId(), foreignId),
                        missingSuite(owner.getId(), foreignSuite.getId())))) {
            Map<UUID, Long> countsBefore = suiteCounts(suite, sibling, foreignSuite);
            CaseSnapshot before = snapshot(caseId);
            CaseSnapshot foreignBefore = snapshot(foreignId);
            assertApiError(mockMvc.perform(put(scenario.path()).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))), 404, "Not Found",
                    scenario.message());
            assertThat(snapshot(caseId)).as("failed scoped PUT must preserve owner case")
                    .isEqualTo(before);
            assertThat(snapshot(foreignId)).as("failed scoped PUT must preserve foreign case")
                    .isEqualTo(foreignBefore);
            assertSuiteCountsUnchanged(countsBefore, "failed scoped PUT");
            assertThat(suiteRepository.findById(missingSuite))
                    .as("failed PUT must not create the missing suite").isEmpty();
        }
        for (ErrorScenario scenario : invalidCaseMutationScenarios(owner, suite, "")) {
            Map<UUID, Long> countsBefore = suiteCounts(suite, sibling, foreignSuite);
            CaseSnapshot before = snapshot(caseId);
            CaseSnapshot foreignBefore = snapshot(foreignId);
            assertApiError(mockMvc.perform(put(scenario.path()).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))),
                    400, "Bad Request", scenario.message());
            assertThat(snapshot(caseId)).as("invalid UUID PUT must preserve owner case")
                    .isEqualTo(before);
            assertThat(snapshot(foreignId)).as("invalid UUID PUT must preserve foreign case")
                    .isEqualTo(foreignBefore);
            assertSuiteCountsUnchanged(countsBefore, "invalid UUID PUT");
        }
    }

    @Test
    void statusPatchChangesOnlyStatusAndRejectsInvalidOrCrossScopeRequests() throws Exception {
        Project project = saveProject("status");
        AutomationSuite suite = saveSuite(project, "Status suite");
        AutomationSuite otherSuite = saveSuite(project, "Status other suite");
        UUID caseId = createCase(project, suite, "Status case", "status-ref",
                Map.of("region", "eu"), AutomationTestCaseStatus.ACTIVE);
        for (AutomationTestCaseStatus statusValue : List.of(
                AutomationTestCaseStatus.INACTIVE, AutomationTestCaseStatus.ARCHIVED,
                AutomationTestCaseStatus.ACTIVE)) {
            CaseSnapshot before = snapshot(caseId);
            MvcResult result = mockMvc.perform(patch(casePath(project, suite, caseId) + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + statusValue + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(statusValue.name()))
                    .andExpect(jsonPath("$.version").value(before.version() + 1)).andReturn();
            CaseSnapshot after = snapshot(caseId);
            JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.suiteId()).isEqualTo(before.suiteId());
            assertThat(after.name()).isEqualTo(before.name());
            assertThat(after.description()).isEqualTo(before.description());
            assertThat(after.caseReference()).isEqualTo(before.caseReference());
            assertThat(after.configurationJson()).isEqualTo(before.configurationJson());
            assertThat(after.position()).isEqualTo(before.position());
            assertThat(after.createdAt()).isEqualTo(before.createdAt());
            assertThat(after.status()).isEqualTo(statusValue);
            assertThat(after.version()).isEqualTo(before.version() + 1)
                    .isEqualTo(response.get("version").asLong());
            assertThat(after.updatedAt()).isAfterOrEqualTo(before.updatedAt());
        }
        Map<String, String> invalidStatusBodies = Map.of(
                "{}", "status: Automation test case status must not be null",
                "{\"status\":null}", "status: Automation test case status must not be null",
                "{\"status\":\"UNKNOWN\"}", "Malformed or unreadable request body");
        for (Map.Entry<String, String> invalid : invalidStatusBodies.entrySet()) {
            CaseSnapshot before = snapshot(caseId);
            assertApiError(mockMvc.perform(patch(casePath(project, suite, caseId) + "/status")
                    .contentType(MediaType.APPLICATION_JSON).content(invalid.getKey())),
                    400, "Bad Request", invalid.getValue());
            assertThat(snapshot(caseId)).as("failed PATCH must preserve complete state").isEqualTo(before);
        }
        CaseSnapshot crossScopeBefore = snapshot(caseId);
        assertApiError(mockMvc.perform(patch(casePath(project, otherSuite, caseId) + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}")),
                404, "Not Found", missingCase(otherSuite.getId(), caseId));
        assertThat(snapshot(caseId)).as("cross-scope PATCH must preserve complete state")
                .isEqualTo(crossScopeBefore);
    }

    @Test
    void statusPatchRejectsEveryMissingCrossScopeAndInvalidUuidWithoutMutation() throws Exception {
        Project owner = saveProject("patch-scope-owner");
        Project other = saveProject("patch-scope-other");
        AutomationSuite suite = saveSuite(owner, "PATCH owner suite");
        AutomationSuite sibling = saveSuite(owner, "PATCH sibling suite");
        AutomationSuite foreignSuite = saveSuite(other, "PATCH foreign suite");
        UUID caseId = createCase(owner, suite, "PATCH case", "patch-case", null, null);
        UUID foreignId = createCase(other, foreignSuite, "Foreign PATCH", "foreign-patch", null, null);
        CaseSnapshot before = snapshot(caseId);
        CaseSnapshot foreignBefore = snapshot(foreignId);
        UUID missingProject = UUID.randomUUID();
        UUID missingSuite = UUID.randomUUID();
        UUID missingCase = UUID.randomUUID();
        for (ErrorScenario scenario : List.of(
                new ErrorScenario(casePath(missingProject, suite.getId(), caseId),
                        missingProject(missingProject)),
                new ErrorScenario(casePath(owner.getId(), missingSuite, caseId),
                        missingSuite(owner.getId(), missingSuite)),
                new ErrorScenario(casePath(owner.getId(), suite.getId(), missingCase),
                        missingCase(suite.getId(), missingCase)),
                new ErrorScenario(casePath(owner.getId(), sibling.getId(), caseId),
                        missingCase(sibling.getId(), caseId)),
                new ErrorScenario(casePath(owner.getId(), foreignSuite.getId(), foreignId),
                        missingSuite(owner.getId(), foreignSuite.getId())))) {
            assertApiError(mockMvc.perform(patch(scenario.path() + "/status")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}")),
                    404, "Not Found", scenario.message());
            assertThat(snapshot(caseId)).as("failed scoped PATCH must preserve owner case").isEqualTo(before);
            assertThat(snapshot(foreignId)).as("failed scoped PATCH must preserve foreign case")
                    .isEqualTo(foreignBefore);
        }
        for (ErrorScenario scenario : invalidCaseMutationScenarios(owner, suite, "/status")) {
            assertApiError(mockMvc.perform(patch(scenario.path()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ARCHIVED\"}")),
                    400, "Bad Request", scenario.message());
            assertThat(snapshot(caseId)).as("invalid UUID PATCH must preserve case").isEqualTo(before);
        }
    }

    @Test
    void reorderCommitsExactArrayAndRejectsEveryInvalidMembershipAtomically() throws Exception {
        Project project = saveProject("reorder");
        AutomationSuite suite = saveSuite(project, "Reorder suite");
        AutomationSuite otherSuite = saveSuite(project, "Reorder other suite");
        UUID first = createCase(project, suite, "First", "first", null, null);
        UUID second = createCase(project, suite, "Second", "second", null, null);
        UUID third = createCase(project, suite, "Third", "third", null, null);
        UUID foreign = createCase(project, otherSuite, "Foreign", "foreign", null, null);
        List<UUID> order = List.of(third, first, second);
        Map<UUID, CaseSnapshot> before = Map.of(
                first, snapshot(first), second, snapshot(second), third, snapshot(third));
        CaseSnapshot foreignBefore = snapshot(foreign);
        MvcResult reorderResult = mockMvc.perform(put(casesPath(project, suite) + "/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(order)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(third.toString()))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[1].id").value(first.toString()))
                .andExpect(jsonPath("$[1].position").value(1))
                .andExpect(jsonPath("$[2].id").value(second.toString()))
                .andExpect(jsonPath("$[2].position").value(2)).andReturn();
        JsonNode response = objectMapper.readTree(reorderResult.getResponse().getContentAsString());
        Set<UUID> responseIds = new java.util.HashSet<>();
        for (int position = 0; position < order.size(); position++) {
            UUID id = order.get(position);
            CaseSnapshot persisted = snapshot(id);
            JsonNode item = response.get(position);
            assertThat(UUID.fromString(item.get("id").asText())).isEqualTo(id);
            assertThat(responseIds.add(id)).as("reorder response IDs must be unique").isTrue();
            assertThat(item.get("automationSuiteId").asText()).isEqualTo(suite.getId().toString());
            assertThat(item.get("name").asText()).isEqualTo(persisted.name());
            assertThat(item.get("description").isNull()).isTrue();
            assertThat(item.get("caseReference").asText()).isEqualTo(persisted.caseReference());
            assertThat(item.get("configuration").isNull()).isTrue();
            assertThat(item.get("status").asText()).isEqualTo(persisted.status().name());
            assertThat(item.get("position").asInt()).isEqualTo(position).isEqualTo(persisted.position());
            assertThat(item.get("version").asLong()).isEqualTo(persisted.version())
                    .isEqualTo(before.get(id).version() + 1);
            OffsetDateTime responseCreatedAt = OffsetDateTime.parse(item.get("createdAt").asText());
            OffsetDateTime responseUpdatedAt = OffsetDateTime.parse(item.get("updatedAt").asText());
            assertThat(responseCreatedAt.toInstant()).as("response createdAt for case " + id)
                    .isEqualTo(persisted.createdAt().toInstant());
            assertThat(responseUpdatedAt.toInstant()).as("response updatedAt for case " + id)
                    .isEqualTo(persisted.updatedAt().toInstant());
            assertThat(persisted.createdAt()).isEqualTo(before.get(id).createdAt());
            assertThat(persisted.updatedAt()).isAfterOrEqualTo(before.get(id).updatedAt());
        }
        assertThat(responseIds).containsExactlyInAnyOrderElementsOf(order);
        assertThat(caseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(suite.getId()))
                .extracting(AutomationTestCase::getId).containsExactlyElementsOf(order);
        assertThat(snapshot(foreign)).as("successful reorder must not change foreign suite")
                .isEqualTo(foreignBefore);

        Map<String, String> invalid = Map.of(
                orderBody(List.of()), completeMembershipMessage(),
                orderBody(List.of(third, third, second)), "caseIds must not contain duplicates",
                orderBody(List.of(third, first)), completeMembershipMessage(),
                orderBody(List.of(third, first, second, UUID.randomUUID())), completeMembershipMessage(),
                orderBody(List.of(third, first, foreign)), completeMembershipMessage(),
                "{\"caseIds\":[null]}", "caseIds[0]: Automation test case ID must not be null",
                "{\"caseIds\":[\"not-a-uuid\"]}", "Malformed or unreadable request body");
        for (Map.Entry<String, String> scenario : invalid.entrySet()) {
            List<CaseSnapshot> targetBefore = snapshots(order);
            List<CaseSnapshot> invalidForeignBefore = snapshotsForSuite(otherSuite.getId());
            assertApiError(mockMvc.perform(put(casesPath(project, suite) + "/order")
                            .contentType(MediaType.APPLICATION_JSON).content(scenario.getKey())),
                    400, "Bad Request", scenario.getValue());
            assertThat(snapshots(order)).as("invalid reorder must preserve every target case")
                    .containsExactlyElementsOf(targetBefore);
            assertThat(snapshotsForSuite(otherSuite.getId()))
                    .as("invalid reorder must preserve complete foreign suite")
                    .containsExactlyElementsOf(invalidForeignBefore);
        }
    }

    @Test
    void reorderRejectsMissingCrossProjectAndInvalidUuidWithoutMutation() throws Exception {
        Project owner = saveProject("reorder-scope-owner");
        Project other = saveProject("reorder-scope-other");
        AutomationSuite suite = saveSuite(owner, "Reorder scoped suite");
        AutomationSuite foreignSuite = saveSuite(other, "Reorder foreign suite");
        UUID caseId = createCase(owner, suite, "Scoped reorder", "scoped-reorder", null, null);
        UUID foreignId = createCase(other, foreignSuite, "Foreign reorder", "foreign-reorder", null, null);
        CaseSnapshot before = snapshot(caseId);
        CaseSnapshot foreignBefore = snapshot(foreignId);
        UUID missingProject = UUID.randomUUID();
        UUID missingSuite = UUID.randomUUID();
        for (ErrorScenario scenario : List.of(
                new ErrorScenario(casesPath(missingProject, suite.getId()) + "/order",
                        missingProject(missingProject)),
                new ErrorScenario(casesPath(owner.getId(), missingSuite) + "/order",
                        missingSuite(owner.getId(), missingSuite)),
                new ErrorScenario(casesPath(owner.getId(), foreignSuite.getId()) + "/order",
                        missingSuite(owner.getId(), foreignSuite.getId())))) {
            assertApiError(mockMvc.perform(put(scenario.path()).contentType(MediaType.APPLICATION_JSON)
                    .content(orderBody(List.of(caseId)))), 404, "Not Found", scenario.message());
            assertThat(snapshot(caseId)).as("failed scoped reorder must preserve owner case")
                    .isEqualTo(before);
            assertThat(snapshot(foreignId)).as("failed scoped reorder must preserve foreign case")
                    .isEqualTo(foreignBefore);
        }
        for (ErrorScenario scenario : List.of(
                new ErrorScenario("/api/v1/projects/not-a-uuid/automation-suites/"
                        + suite.getId() + "/test-cases/order", invalidUuidMessage("projectId")),
                new ErrorScenario("/api/v1/projects/" + owner.getId()
                        + "/automation-suites/not-a-uuid/test-cases/order",
                        invalidUuidMessage("suiteId")))) {
            List<CaseSnapshot> targetBefore = snapshots(List.of(caseId));
            List<CaseSnapshot> invalidForeignBefore = snapshotsForSuite(foreignSuite.getId());
            assertApiError(mockMvc.perform(put(scenario.path()).contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(List.of(caseId)))),
                    400, "Bad Request", scenario.message());
            assertThat(snapshots(List.of(caseId))).as("invalid UUID reorder must preserve target suite")
                    .containsExactlyElementsOf(targetBefore);
            assertThat(snapshotsForSuite(foreignSuite.getId()))
                    .as("invalid UUID reorder must preserve complete foreign suite")
                    .containsExactlyElementsOf(invalidForeignBefore);
        }
    }

    @Test
    void reorderEmptySuiteReturnsExactEmptyArray() throws Exception {
        Project project = saveProject("empty-reorder");
        AutomationSuite suite = saveSuite(project, "Empty reorder suite");
        mockMvc.perform(put(casesPath(project, suite) + "/order")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caseIds\":[]}"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
        assertThat(caseRepository.existsByAutomationSuiteId(suite.getId())).isFalse();
    }

    @Test
    void deletePhysicallyRemovesOnlySelectedCaseAndSubsequentGetIs404() throws Exception {
        Project project = saveProject("delete");
        AutomationSuite suite = saveSuite(project, "Delete suite");
        UUID removed = createCase(project, suite, "Removed", "removed", null, null);
        UUID retained = createCase(project, suite, "Retained", "retained", null, null);
        mockMvc.perform(delete(casePath(project, suite, removed)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(caseRepository.findById(removed)).isEmpty();
        assertThat(caseRepository.findById(retained)).isPresent();
        assertThat(suiteRepository.findById(suite.getId())).isPresent();
        assertApiError(mockMvc.perform(get(casePath(project, suite, removed))), 404, "Not Found",
                missingCase(suite.getId(), removed));
    }

    @Test
    void deleteRejectsEveryInvalidHierarchyAndUuidWithoutMutation() throws Exception {
        Project owner = saveProject("delete-scope-owner");
        Project other = saveProject("delete-scope-other");
        AutomationSuite suite = saveSuite(owner, "DELETE owner suite");
        AutomationSuite sibling = saveSuite(owner, "DELETE sibling suite");
        AutomationSuite foreignSuite = saveSuite(other, "DELETE foreign suite");
        UUID caseId = createCase(owner, suite, "DELETE case", "delete-case", null, null);
        UUID foreignId = createCase(other, foreignSuite, "Foreign DELETE", "foreign-delete", null, null);
        CaseSnapshot before = snapshot(caseId);
        CaseSnapshot foreignBefore = snapshot(foreignId);
        UUID missingProject = UUID.randomUUID();
        UUID missingSuite = UUID.randomUUID();
        UUID missingCase = UUID.randomUUID();
        for (ErrorScenario scenario : List.of(
                new ErrorScenario(casePath(missingProject, suite.getId(), caseId),
                        missingProject(missingProject)),
                new ErrorScenario(casePath(owner.getId(), missingSuite, caseId),
                        missingSuite(owner.getId(), missingSuite)),
                new ErrorScenario(casePath(owner.getId(), suite.getId(), missingCase),
                        missingCase(suite.getId(), missingCase)),
                new ErrorScenario(casePath(owner.getId(), sibling.getId(), caseId),
                        missingCase(sibling.getId(), caseId)),
                new ErrorScenario(casePath(owner.getId(), foreignSuite.getId(), foreignId),
                        missingSuite(owner.getId(), foreignSuite.getId())))) {
            assertApiError(mockMvc.perform(delete(scenario.path())), 404, "Not Found",
                    scenario.message());
            assertThat(snapshot(caseId)).as("failed scoped DELETE must preserve owner case").isEqualTo(before);
            assertThat(snapshot(foreignId)).as("failed scoped DELETE must preserve foreign case")
                    .isEqualTo(foreignBefore);
        }
        for (ErrorScenario scenario : invalidCaseMutationScenarios(owner, suite, "")) {
            assertApiError(mockMvc.perform(delete(scenario.path())), 400, "Bad Request",
                    scenario.message());
            assertThat(snapshot(caseId)).as("invalid UUID DELETE must preserve case").isEqualTo(before);
        }
    }

    @Test
    void suiteEngineAndDeletionGuardsIncludeArchivedCasesAndReleaseAfterCaseDeletion()
            throws Exception {
        Project project = saveProject("suite-guards");
        AutomationSuite suite = saveSuite(project, "Guard suite");
        UUID caseId = createCase(project, suite, "Guard case", "guard-ref", null, null);
        SuiteSnapshot activeSuiteBefore = suiteSnapshot(suite.getId());
        CaseSnapshot activeCaseBefore = snapshot(caseId);
        for (UpdateAutomationSuiteRequest update : List.of(
                suiteUpdate(suite, "PLAYWRIGHT", suite.getSuiteReference(), "changed-engine"),
                suiteUpdate(suite, "SELENIUM", suite.getSuiteReference(), suite.getEngineId()),
                suiteUpdate(suite, "PLAYWRIGHT", "tests/changed", suite.getEngineId()))) {
            assertApiError(mockMvc.perform(put(suitePath(project, suite))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update))), 409, "Conflict",
                    protectedEngineConflict(suite.getId()));
            assertThat(suiteSnapshot(suite.getId()))
                    .as("active-case guard must preserve complete suite state")
                    .isEqualTo(activeSuiteBefore);
            assertThat(snapshot(caseId)).as("active-case guard must preserve complete case state")
                    .isEqualTo(activeCaseBefore);
        }
        UpdateAutomationSuiteRequest metadata = new UpdateAutomationSuiteRequest(
                "Guard suite renamed", "metadata", suite.getEngineType(), suite.getSuiteReference(),
                suite.getEngineId(), suite.getSuiteType(), suite.getConfiguration());
        mockMvc.perform(put(suitePath(project, suite)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(metadata))).andExpect(status().isOk());
        SuiteSnapshot metadataSuite = suiteSnapshot(suite.getId());
        CaseSnapshot beforeActiveDelete = snapshot(caseId);
        assertApiError(mockMvc.perform(delete(suitePath(project, suite))), 409, "Conflict",
                suiteDeletionConflict(suite.getId()));
        assertThat(suiteSnapshot(suite.getId())).as("active-case DELETE must preserve suite")
                .isEqualTo(metadataSuite);
        assertThat(snapshot(caseId)).as("active-case DELETE must preserve case")
                .isEqualTo(beforeActiveDelete);
        mockMvc.perform(patch(casePath(project, suite, caseId) + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk());
        SuiteSnapshot archivedSuiteBefore = suiteSnapshot(suite.getId());
        CaseSnapshot archivedCaseBefore = snapshot(caseId);
        assertApiError(mockMvc.perform(delete(suitePath(project, suite))), 409, "Conflict",
                suiteDeletionConflict(suite.getId()));
        assertThat(suiteSnapshot(suite.getId())).as("archived-case DELETE must preserve suite")
                .isEqualTo(archivedSuiteBefore);
        assertThat(snapshot(caseId)).as("archived-case DELETE must preserve archived case")
                .isEqualTo(archivedCaseBefore);
        for (UpdateAutomationSuiteRequest update : List.of(
                new UpdateAutomationSuiteRequest(metadataSuite.name(), metadataSuite.description(),
                        metadataSuite.engineType(), metadataSuite.suiteReference(), "archived-engine-change",
                        suite.getSuiteType(), suite.getConfiguration()),
                new UpdateAutomationSuiteRequest(metadataSuite.name(), metadataSuite.description(),
                        "SELENIUM", metadataSuite.suiteReference(), metadataSuite.engineId(),
                        suite.getSuiteType(), suite.getConfiguration()),
                new UpdateAutomationSuiteRequest(metadataSuite.name(), metadataSuite.description(),
                        metadataSuite.engineType(), "tests/archived-change", metadataSuite.engineId(),
                        suite.getSuiteType(), suite.getConfiguration()))) {
            assertApiError(mockMvc.perform(put(suitePath(project, suite))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update))), 409, "Conflict",
                    protectedEngineConflict(suite.getId()));
            assertThat(suiteSnapshot(suite.getId()))
                    .as("archived-case guard must preserve complete suite state")
                    .isEqualTo(archivedSuiteBefore);
            assertThat(snapshot(caseId)).as("archived-case guard must preserve archived case")
                    .isEqualTo(archivedCaseBefore);
        }
        mockMvc.perform(delete(casePath(project, suite, caseId))).andExpect(status().isNoContent());
        UpdateAutomationSuiteRequest released = new UpdateAutomationSuiteRequest(
                "Guard suite renamed", "metadata", "SELENIUM", "tests/released", "selenium-java",
                suite.getSuiteType(), suite.getConfiguration());
        mockMvc.perform(put(suitePath(project, suite)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(released))).andExpect(status().isOk());
        SuiteSnapshot releasedSuite = suiteSnapshot(suite.getId());
        assertThat(releasedSuite.engineType()).isEqualTo("SELENIUM");
        assertThat(releasedSuite.suiteReference()).isEqualTo("tests/released");
        assertThat(releasedSuite.engineId()).isEqualTo("selenium-java");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution WHERE test_suite_id = ?", Long.class, suite.getId()))
                .as("suite deletion precondition requires no Execution reference").isZero();
        mockMvc.perform(delete(suitePath(project, suite))).andExpect(status().isNoContent());
        assertThat(suiteRepository.findById(suite.getId())).isEmpty();
    }

    @Test
    void concurrentHttpCreatesProduceDistinctCasesAtPositionsZeroAndOne() throws Exception {
        Project project = saveProject("concurrent");
        AutomationSuite suite = saveSuite(project, "Concurrent suite");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> concurrentCreate(
                    project, suite, "Concurrent first", "concurrent-first", ready, start));
            Future<MvcResult> second = executor.submit(() -> concurrentCreate(
                    project, suite, "Concurrent second", "concurrent-second", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("both concurrent HTTP workers must become ready").isTrue();
            start.countDown();
            MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertConcurrentCreateResponse(firstResult, suite, "Concurrent first", "concurrent-first");
            assertConcurrentCreateResponse(secondResult, suite, "Concurrent second", "concurrent-second");
            JsonNode firstBody = objectMapper.readTree(firstResult.getResponse().getContentAsString());
            JsonNode secondBody = objectMapper.readTree(secondResult.getResponse().getContentAsString());
            UUID firstId = UUID.fromString(firstBody.get("id").asText());
            UUID secondId = UUID.fromString(secondBody.get("id").asText());
            assertThat(firstId).as("first concurrent response ID").isNotEqualTo(secondId);
            assertThat(Set.of(firstBody.get("position").asInt(), secondBody.get("position").asInt()))
                    .as("concurrent response positions").containsExactlyInAnyOrder(0, 1);
            assertThat(snapshot(firstId).name()).isEqualTo("Concurrent first");
            assertThat(snapshot(firstId).caseReference()).isEqualTo("concurrent-first");
            assertThat(snapshot(firstId).position()).isEqualTo(firstBody.get("position").asInt());
            assertThat(snapshot(secondId).name()).isEqualTo("Concurrent second");
            assertThat(snapshot(secondId).caseReference()).isEqualTo("concurrent-second");
            assertThat(snapshot(secondId).position()).isEqualTo(secondBody.get("position").asInt());
            assertThat(caseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(suite.getId()))
                    .extracting(AutomationTestCase::getId)
                    .containsExactlyInAnyOrder(firstId, secondId);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        List<AutomationTestCase> cases =
                caseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(suite.getId());
        assertThat(cases).hasSize(2).extracting(AutomationTestCase::getPosition)
                .containsExactly(0, 1);
        assertThat(cases).extracting(AutomationTestCase::getId).doesNotHaveDuplicates();
    }

    private MvcResult concurrentCreate(Project project, AutomationSuite suite, String name,
            String reference, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent HTTP start");
        }
        return mockMvc.perform(post(casesPath(project, suite)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(name, reference, null))))
                .andReturn();
    }

    private void assertConcurrentCreateResponse(
            MvcResult result, AutomationSuite suite, String name, String reference) throws Exception {
        assertThat(result.getResponse().getStatus()).as("concurrent create HTTP status").isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("id").asText()).as("concurrent create response ID").isNotBlank();
        assertThat(body.get("automationSuiteId").asText()).isEqualTo(suite.getId().toString());
        assertThat(body.get("name").asText()).isEqualTo(name);
        assertThat(body.get("caseReference").asText()).isEqualTo(reference);
    }

    private Project saveProject(String label) {
        String suffix = UUID.randomUUID().toString();
        Workspace workspace = new Workspace();
        workspace.setName("AS-016F Workspace " + label + " " + suffix);
        workspace.setSlug(WORKSPACE_SLUG_PREFIX + suffix);
        workspace = workspaceRepository.saveAndFlush(workspace);
        Project project = new Project();
        project.setWorkspace(workspace);
        project.setName("AS-016F Project " + label + " " + suffix);
        return projectRepository.saveAndFlush(project);
    }

    private AutomationSuite saveSuite(Project project, String name) {
        AutomationSuite suite = new AutomationSuite();
        suite.setProject(project);
        suite.setName(name);
        suite.setDescription("Suite description");
        suite.setEngineType("PLAYWRIGHT");
        suite.setSuiteReference("tests/" + UUID.randomUUID());
        suite.setEngineId("playwright-java");
        suite.setSuiteType(SuiteType.UI);
        suite.setConfiguration(Map.of("browser", "chromium"));
        suite.setStatus(AutomationSuiteStatus.ACTIVE);
        return suiteRepository.saveAndFlush(suite);
    }

    private UUID createCase(Project project, AutomationSuite suite, String name, String reference,
            Map<String, Object> configuration, AutomationTestCaseStatus status) throws Exception {
        MvcResult result = mockMvc.perform(post(casesPath(project, suite))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAutomationTestCaseRequest(
                                name, null, reference, configuration, status))))
                .andExpect(status().isCreated()).andReturn();
        return responseId(result);
    }

    private UUID responseId(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private CreateAutomationTestCaseRequest request(
            String name, String reference, AutomationTestCaseStatus status) {
        return new CreateAutomationTestCaseRequest(name, null, reference, null, status);
    }

    private AutomationTestCase newCase(
            AutomationSuite suite, String name, String reference, int position) {
        AutomationTestCase testCase = new AutomationTestCase();
        testCase.setAutomationSuite(suite);
        testCase.setName(name);
        testCase.setCaseReference(reference);
        testCase.setPosition(position);
        return testCase;
    }

    private org.springframework.test.web.servlet.ResultActions postCase(
            Project project, AutomationSuite suite, CreateAutomationTestCaseRequest request)
            throws Exception {
        return mockMvc.perform(post(casesPath(project, suite)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private org.springframework.test.web.servlet.ResultActions putCase(
            Project project, AutomationSuite suite, UUID caseId,
            UpdateAutomationTestCaseRequest request) throws Exception {
        return mockMvc.perform(put(casePath(project, suite, caseId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private org.springframework.test.web.servlet.ResultActions assertApiError(
            org.springframework.test.web.servlet.ResultActions result, int code, String error,
            String expectedMessage) throws Exception {
        MvcResult mvcResult = result.andExpect(status().is(code))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.error").value(error)).andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist()).andReturn();
        JsonNode body = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(body.isObject()).as("ApiErrorResponse root must be an object").isTrue();
        assertThat(body.isArray()).as("ApiErrorResponse root must not be an array").isFalse();
        assertThat(body.size()).as("ApiErrorResponse must contain no partial case data").isEqualTo(5);
        assertThat(body.has("timestamp") && body.has("status") && body.has("error")
                && body.has("message") && body.has("path"))
                .as("ApiErrorResponse must contain exactly its five standard fields").isTrue();
        assertThat(body.get("timestamp").isTextual())
                .as("ApiErrorResponse timestamp must be textual").isTrue();
        assertThat(body.get("status").isIntegralNumber())
                .as("ApiErrorResponse status must be an integral number").isTrue();
        assertThat(body.get("error").isTextual())
                .as("ApiErrorResponse error must be textual").isTrue();
        assertThat(body.get("message").isTextual())
                .as("ApiErrorResponse message must be textual").isTrue();
        assertThat(body.get("path").isTextual())
                .as("ApiErrorResponse path must be textual").isTrue();
        for (String field : List.of("timestamp", "status", "error", "message", "path")) {
            assertThat(body.get(field).isValueNode())
                    .as("ApiErrorResponse field '%s' must be a scalar value", field).isTrue();
        }
        assertThat(body.get("path").asText()).as("exact ApiErrorResponse request path")
                .isEqualTo(mvcResult.getRequest().getRequestURI());
        assertMalformedUuidPath(expectedMessage, mvcResult.getRequest().getRequestURI());
        String message = body.get("message").asText();
        assertThat(message).as(
                        "Unexpected API error message for %s %s with expected status %s and message '%s'",
                        mvcResult.getRequest().getMethod(),
                        mvcResult.getRequest().getRequestURI(), code, expectedMessage)
                .isEqualTo(expectedMessage);
        String serialized = mvcResult.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        assertThat(serialized).as("error response must not expose internal implementation details")
                .doesNotMatch("(?s).*\\b(sql|sqlstate|psql|jdbc|jpa|hibernate)\\b.*")
                .doesNotContain("org.springframework", "org.hibernate", "java.lang", "\tat ",
                        "stacktrace", "stack trace", "datasource", "connectionimpl",
                        "hikariproxyconnection", "pgconnection", "postgresql",
                        "constraintviolationexception", "dataintegrityviolationexception",
                        "psqlexception", "uk_automation_test_case", "fk_automation_test_case",
                        "chk_automation_test_case", "uq_automation_test_case");
        return result;
    }

    private void assertMalformedUuidPath(String expectedMessage, String path) {
        String malformedValue = "not-a-uuid";
        if (expectedMessage.equals(invalidUuidMessage("projectId"))) {
            assertThat(path).as("projectId UUID conversion must reject exact value " + malformedValue)
                    .contains("/projects/" + malformedValue + "/automation-suites/");
        } else if (expectedMessage.equals(invalidUuidMessage("suiteId"))) {
            assertThat(path).as("suiteId UUID conversion must reject exact value " + malformedValue)
                    .contains("/automation-suites/" + malformedValue + "/test-cases");
        } else if (expectedMessage.equals(invalidUuidMessage("caseId"))) {
            assertThat(path).as("caseId UUID conversion must reject exact value " + malformedValue)
                    .contains("/test-cases/" + malformedValue);
        }
    }

    private CaseSnapshot snapshot(UUID caseId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, test_suite_id, name, description, case_reference,
                       configuration::text, status, position, version, created_at, updated_at
                FROM automation_test_case WHERE id = ?
                """, (rs, rowNum) -> new CaseSnapshot(
                        rs.getObject("id", UUID.class), rs.getObject("test_suite_id", UUID.class),
                        rs.getString("name"), rs.getString("description"),
                        rs.getString("case_reference"), rs.getString("configuration"),
                        AutomationTestCaseStatus.valueOf(rs.getString("status")),
                        rs.getInt("position"), rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)), caseId);
    }

    private List<CaseSnapshot> snapshots(List<UUID> ids) {
        return ids.stream().map(this::snapshot).toList();
    }

    private List<CaseSnapshot> snapshotsForSuite(UUID suiteId) {
        return caseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(suiteId).stream()
                .map(testCase -> snapshot(testCase.getId()))
                .toList();
    }

    private long countCases(UUID suiteId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_test_case WHERE test_suite_id = ?",
                Long.class, suiteId);
    }

    private Map<UUID, Long> suiteCounts(AutomationSuite... suites) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (AutomationSuite suite : suites) {
            counts.put(suite.getId(), countCases(suite.getId()));
        }
        return Map.copyOf(counts);
    }

    private void assertSuiteCountsUnchanged(Map<UUID, Long> before, String scenario) {
        before.forEach((suiteId, count) -> assertThat(countCases(suiteId))
                .as("%s must preserve row count for suite %s", scenario, suiteId)
                .isEqualTo(count));
    }

    private SuiteSnapshot suiteSnapshot(UUID suiteId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, project_id, name, description, engine_id, engine_type, suite_reference,
                       suite_type, configuration::text AS configuration_json, status, version,
                       created_at, updated_at
                FROM test_suite WHERE id = ?
                """, (rs, rowNum) -> new SuiteSnapshot(
                        rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getString("name"), rs.getString("description"), rs.getString("engine_id"),
                        rs.getString("engine_type"), rs.getString("suite_reference"),
                        rs.getString("suite_type"), rs.getString("configuration_json"),
                        rs.getString("status"), rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)), suiteId);
    }

    private String orderBody(List<UUID> ids) throws Exception {
        return objectMapper.writeValueAsString(Map.of("caseIds", ids));
    }

    private UpdateAutomationSuiteRequest suiteUpdate(
            AutomationSuite suite, String engineType, String suiteReference, String engineId) {
        return new UpdateAutomationSuiteRequest(
                suite.getName(), suite.getDescription(), engineType, suiteReference, engineId,
                suite.getSuiteType(), suite.getConfiguration());
    }

    private Map<String, Object> mixedConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("nested", Map.of("region", "eu"));
        configuration.put("steps", List.of("open", "submit"));
        configuration.put("string", "value");
        configuration.put("number", 3);
        configuration.put("boolean", true);
        configuration.put("nullable", null);
        return configuration;
    }

    private String casesPath(Project project, AutomationSuite suite) {
        return casesPath(project.getId(), suite.getId());
    }

    private String casesPath(UUID projectId, UUID suiteId) {
        return "/api/v1/projects/" + projectId + "/automation-suites/" + suiteId + "/test-cases";
    }

    private String casePath(Project project, AutomationSuite suite, UUID caseId) {
        return casePath(project.getId(), suite.getId(), caseId);
    }

    private String casePath(UUID projectId, UUID suiteId, UUID caseId) {
        return casesPath(projectId, suiteId) + "/" + caseId;
    }

    private List<ErrorScenario> invalidCaseMutationScenarios(
            Project project, AutomationSuite suite, String suffix) {
        String malformedProject = "not-a-uuid";
        String malformedSuite = "not-a-uuid";
        String malformedCase = "not-a-uuid";
        return List.of(
                new ErrorScenario("/api/v1/projects/" + malformedProject + "/automation-suites/"
                        + suite.getId() + "/test-cases/" + UUID.randomUUID() + suffix,
                        invalidUuidMessage("projectId")),
                new ErrorScenario("/api/v1/projects/" + project.getId()
                        + "/automation-suites/" + malformedSuite + "/test-cases/"
                        + UUID.randomUUID() + suffix, invalidUuidMessage("suiteId")),
                new ErrorScenario(casesPath(project, suite) + "/" + malformedCase + suffix,
                        invalidUuidMessage("caseId")));
    }

    private String suitePath(Project project, AutomationSuite suite) {
        return "/api/v1/projects/" + project.getId() + "/automation-suites/" + suite.getId();
    }

    private String missingSuite(UUID projectId, UUID suiteId) {
        return "Automation suite not found with id: " + suiteId + " in project: " + projectId;
    }

    private String missingProject(UUID projectId) {
        return "Project not found with id: " + projectId;
    }

    private String missingCase(UUID suiteId, UUID caseId) {
        return "Automation test case not found with id: " + caseId + " in suite: " + suiteId;
    }

    private String invalidUuidMessage(String parameter) {
        return "Invalid value for parameter '" + parameter + "'";
    }

    private String completeMembershipMessage() {
        return "caseIds must contain the complete current suite membership exactly once";
    }

    private String protectedEngineConflict(UUID suiteId) {
        return "Automation suite engine fields cannot change while test cases exist: " + suiteId;
    }

    private String suiteDeletionConflict(UUID suiteId) {
        return "Automation suite cannot be deleted while test cases exist: " + suiteId;
    }

    private String duplicateName(String name, UUID suiteId) {
        return "Automation test case with name '" + name + "' already exists in suite: " + suiteId;
    }

    private String duplicateReference(String reference, UUID suiteId) {
        return "Automation test case with reference '" + reference
                + "' already exists in suite: " + suiteId;
    }

    private record CaseSnapshot(
            UUID id, UUID suiteId, String name, String description, String caseReference,
            String configurationJson, AutomationTestCaseStatus status, int position, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private record ErrorScenario(String path, String message) {
    }

    private record SuiteSnapshot(
            UUID id, UUID projectId, String name, String description, String engineId,
            String engineType, String suiteReference, String suiteType, String configurationJson,
            String status, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
