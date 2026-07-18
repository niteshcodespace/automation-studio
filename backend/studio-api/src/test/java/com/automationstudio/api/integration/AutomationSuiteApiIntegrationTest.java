package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.SuiteType;
import com.automationstudio.api.dto.automation.suite.AutomationSuiteStatusRequest;
import com.automationstudio.api.dto.automation.suite.CreateAutomationSuiteRequest;
import com.automationstudio.api.dto.automation.suite.UpdateAutomationSuiteRequest;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Workspace;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.repository.WorkspaceRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class AutomationSuiteApiIntegrationTest extends IntegrationTestBase {

    private static final String WORKSPACE_SLUG_PREFIX = "as-015f-api-test-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AutomationSuiteRepository automationSuiteRepository;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_SLUG_PREFIX + "%");
    }

    @Test
    void createCompleteSuitePersistsEveryField() throws Exception {
        Project project = saveProject("complete");
        CreateAutomationSuiteRequest request = createRequest(
                "Checkout suite", AutomationSuiteStatus.INACTIVE);

        mockMvc.perform(post(suitesPath(project.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.name").value("Checkout suite"))
                .andExpect(jsonPath("$.description").value("Suite description"))
                .andExpect(jsonPath("$.engineType").value("PLAYWRIGHT"))
                .andExpect(jsonPath("$.suiteReference").value("tests/checkout-suite"))
                .andExpect(jsonPath("$.engineId").value("playwright-java"))
                .andExpect(jsonPath("$.suiteType").value("UI"))
                .andExpect(jsonPath("$.configuration.browser").value("chromium"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        AutomationSuite persisted = suiteByName(project.getId(), "Checkout suite");
        assertThat(persisted.getProject().getId()).isEqualTo(project.getId());
        assertThat(persisted.getEngineId()).isEqualTo("playwright-java");
        assertThat(persisted.getSuiteType()).isEqualTo(SuiteType.UI);
        assertThat(persisted.getStatus()).isEqualTo(AutomationSuiteStatus.INACTIVE);
        assertThat(persisted.getConfiguration()).containsEntry("browser", "chromium");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(configuration) FROM test_suite WHERE id = ?",
                String.class, persisted.getId())).isEqualTo("object");
    }

    @Test
    void createWithNullableTransitionalFieldsDefaultsStatus() throws Exception {
        Project project = saveProject("nullable");
        CreateAutomationSuiteRequest request = new CreateAutomationSuiteRequest(
                "Legacy suite", null, "PLAYWRIGHT", "tests/legacy",
                null, null, null, null);

        mockMvc.perform(post(suitesPath(project.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.engineId").doesNotExist())
                .andExpect(jsonPath("$.suiteType").doesNotExist())
                .andExpect(jsonPath("$.configuration").doesNotExist());

        AutomationSuite persisted = suiteByName(project.getId(), "Legacy suite");
        assertThat(persisted.getStatus()).isEqualTo(AutomationSuiteStatus.ACTIVE);
        assertThat(persisted.getEngineId()).isNull();
        assertThat(persisted.getSuiteType()).isNull();
        assertThat(persisted.getConfiguration()).isNull();
    }

    @Test
    void createForMissingProjectReturns404WithoutRow() throws Exception {
        UUID missingProjectId = UUID.randomUUID();

        mockMvc.perform(post(suitesPath(missingProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                createRequest("Missing project suite", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));

        assertThat(automationSuiteRepository.countByProjectId(missingProjectId)).isZero();
    }

    @Test
    void duplicateNameIsRejectedWithinProjectButAllowedAcrossProjects() throws Exception {
        Project firstProject = saveProject("duplicate-a");
        Project secondProject = saveProject("duplicate-b");
        String body = objectMapper.writeValueAsString(createRequest("Shared suite", null));

        mockMvc.perform(post(suitesPath(firstProject.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post(suitesPath(firstProject.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mockMvc.perform(post(suitesPath(secondProject.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        assertThat(automationSuiteRepository.countByProjectId(firstProject.getId())).isEqualTo(1);
        assertThat(automationSuiteRepository.countByProjectId(secondProject.getId())).isEqualTo(1);
    }

    @Test
    void invalidCreateReturns400WithoutRow() throws Exception {
        Project project = saveProject("invalid-create");
        String body = """
                {"name":" ","engineType":"PLAYWRIGHT","suiteReference":"tests/invalid"}
                """;

        mockMvc.perform(post(suitesPath(project.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        assertThat(automationSuiteRepository.countByProjectId(project.getId())).isZero();
    }

    @Test
    void getReturnsCompleteSuiteAndConfiguration() throws Exception {
        Project project = saveProject("get");
        AutomationSuite suite = saveSuite(project, "Get suite", AutomationSuiteStatus.ACTIVE);

        mockMvc.perform(get(suitePath(project.getId(), suite.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(suite.getId().toString()))
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.name").value("Get suite"))
                .andExpect(jsonPath("$.engineId").value("playwright-java"))
                .andExpect(jsonPath("$.suiteType").value("UI"))
                .andExpect(jsonPath("$.configuration.browser").value("chromium"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(suite.getVersion()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getMissingAndCrossProjectSuiteReturns404WithoutMutation() throws Exception {
        Project owner = saveProject("get-owner");
        Project other = saveProject("get-other");
        AutomationSuite suite = saveSuite(owner, "Owned suite", AutomationSuiteStatus.ACTIVE);

        mockMvc.perform(get(suitePath(owner.getId(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(suitePath(other.getId(), suite.getId())))
                .andExpect(status().isNotFound());

        assertThat(automationSuiteRepository.findById(suite.getId())).isPresent()
                .get().extracting(AutomationSuite::getName).isEqualTo("Owned suite");
    }

    @Test
    void listPaginatesSortsAndExcludesOtherProjects() throws Exception {
        Project project = saveProject("list");
        Project other = saveProject("list-other");
        saveSuite(project, "Charlie", AutomationSuiteStatus.ACTIVE);
        saveSuite(project, "Alpha", AutomationSuiteStatus.ACTIVE);
        saveSuite(project, "Bravo", AutomationSuiteStatus.ACTIVE);
        saveSuite(other, "Aaron other", AutomationSuiteStatus.ACTIVE);

        mockMvc.perform(get(suitesPath(project.getId()))
                        .param("page", "0").param("size", "2").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Bravo"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get(suitesPath(project.getId()))
                        .param("page", "1").param("size", "2").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Charlie"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listFiltersStatusWithinProject() throws Exception {
        Project project = saveProject("filter");
        Project other = saveProject("filter-other");
        saveSuite(project, "Active", AutomationSuiteStatus.ACTIVE);
        saveSuite(project, "Inactive", AutomationSuiteStatus.INACTIVE);
        saveSuite(project, "Archived beta", AutomationSuiteStatus.ARCHIVED);
        saveSuite(project, "Archived alpha", AutomationSuiteStatus.ARCHIVED);
        saveSuite(other, "Archived other", AutomationSuiteStatus.ARCHIVED);

        mockMvc.perform(get(suitesPath(project.getId()))
                        .param("status", "ARCHIVED").param("page", "0")
                        .param("size", "20").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Archived alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Archived beta"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void invalidListStatusReturnsStandard400() throws Exception {
        Project project = saveProject("invalid-filter");
        String path = suitesPath(project.getId());

        mockMvc.perform(get(path).param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value(path));
    }

    @Test
    void updatePersistsMutableFieldsAndPreservesOwnershipStatusAndAuditData() throws Exception {
        Project project = saveProject("update");
        AutomationSuite original = saveSuite(project, "Original suite", AutomationSuiteStatus.INACTIVE);
        UUID suiteId = original.getId();
        long version = original.getVersion();
        OffsetDateTime createdAt = original.getCreatedAt();
        OffsetDateTime updatedAt = original.getUpdatedAt();
        UpdateAutomationSuiteRequest request = new UpdateAutomationSuiteRequest(
                "Updated suite", "Updated description", "SELENIUM", "tests/updated",
                "selenium-java", SuiteType.PERFORMANCE, Map.of("browser", "firefox"));

        mockMvc.perform(put(suitePath(project.getId(), suiteId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated suite"))
                .andExpect(jsonPath("$.engineType").value("SELENIUM"))
                .andExpect(jsonPath("$.engineId").value("selenium-java"))
                .andExpect(jsonPath("$.suiteType").value("PERFORMANCE"))
                .andExpect(jsonPath("$.configuration.browser").value("firefox"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.version").value(version + 1));

        AutomationSuite persisted = automationSuiteRepository.findById(suiteId).orElseThrow();
        assertThat(persisted.getProject().getId()).isEqualTo(project.getId());
        assertThat(persisted.getStatus()).isEqualTo(AutomationSuiteStatus.INACTIVE);
        assertThat(persisted.getName()).isEqualTo("Updated suite");
        assertThat(persisted.getCreatedAt()).isEqualTo(createdAt);
        assertThat(persisted.getUpdatedAt()).isAfterOrEqualTo(updatedAt);
        assertThat(persisted.getVersion()).isEqualTo(version + 1);
    }

    @Test
    void duplicateRenameReturns409AndPreservesBothRows() throws Exception {
        Project project = saveProject("rename");
        AutomationSuite first = saveSuite(project, "First suite", AutomationSuiteStatus.ACTIVE);
        AutomationSuite second = saveSuite(project, "Second suite", AutomationSuiteStatus.ACTIVE);
        UpdateAutomationSuiteRequest request = updateRequest("Second suite");

        mockMvc.perform(put(suitePath(project.getId(), first.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        assertThat(automationSuiteRepository.findById(first.getId()).orElseThrow().getName())
                .isEqualTo("First suite");
        assertThat(automationSuiteRepository.findById(second.getId()).orElseThrow().getName())
                .isEqualTo("Second suite");
    }

    @Test
    void crossProjectAndInvalidUpdateDoNotMutateSuite() throws Exception {
        Project owner = saveProject("update-owner");
        Project other = saveProject("update-other");
        AutomationSuite suite = saveSuite(owner, "Protected suite", AutomationSuiteStatus.ACTIVE);

        mockMvc.perform(put(suitePath(other.getId(), suite.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest("Cross update"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(suitePath(owner.getId(), suite.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"engineType\":\"PLAYWRIGHT\","
                                + "\"suiteReference\":\"tests/invalid\"}"))
                .andExpect(status().isBadRequest());

        AutomationSuite persisted = automationSuiteRepository.findById(suite.getId()).orElseThrow();
        assertThat(persisted.getName()).isEqualTo("Protected suite");
        assertThat(persisted.getProject().getId()).isEqualTo(owner.getId());
    }

    @Test
    void statusChangePersistsAndPreservesOtherFields() throws Exception {
        Project project = saveProject("status");
        AutomationSuite suite = saveSuite(project, "Status suite", AutomationSuiteStatus.ACTIVE);
        UUID originalId = suite.getId();
        UUID originalProjectId = suite.getProject().getId();
        String originalName = suite.getName();
        String originalDescription = suite.getDescription();
        String originalEngineType = suite.getEngineType();
        String originalSuiteReference = suite.getSuiteReference();
        String originalEngineId = suite.getEngineId();
        SuiteType originalSuiteType = suite.getSuiteType();
        Map<String, Object> originalConfiguration = suite.getConfiguration();
        AutomationSuiteStatus originalStatus = suite.getStatus();
        long originalVersion = suite.getVersion();
        OffsetDateTime originalCreatedAt = suite.getCreatedAt();
        OffsetDateTime originalUpdatedAt = suite.getUpdatedAt();

        mockMvc.perform(patch(suitePath(project.getId(), suite.getId()) + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AutomationSuiteStatusRequest(AutomationSuiteStatus.ARCHIVED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(originalId.toString()))
                .andExpect(jsonPath("$.projectId").value(originalProjectId.toString()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(originalVersion + 1));

        AutomationSuite persisted = automationSuiteRepository.findById(suite.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AutomationSuiteStatus.ARCHIVED);
        assertThat(persisted.getStatus()).isNotEqualTo(originalStatus);
        assertThat(persisted.getId()).isEqualTo(originalId);
        assertThat(persisted.getProject().getId()).isEqualTo(originalProjectId);
        assertThat(persisted.getProject().getId()).isEqualTo(project.getId());
        assertThat(persisted.getName()).isEqualTo(originalName);
        assertThat(persisted.getDescription()).isEqualTo(originalDescription);
        assertThat(persisted.getEngineType()).isEqualTo(originalEngineType);
        assertThat(persisted.getSuiteReference()).isEqualTo(originalSuiteReference);
        assertThat(persisted.getEngineId()).isEqualTo(originalEngineId);
        assertThat(persisted.getSuiteType()).isEqualTo(originalSuiteType);
        assertThat(persisted.getConfiguration()).isEqualTo(originalConfiguration);
        assertThat(persisted.getVersion()).isEqualTo(originalVersion + 1);
        assertThat(persisted.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(persisted.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
    }

    @Test
    void invalidAndCrossProjectStatusChangesDoNotMutateStatus() throws Exception {
        Project owner = saveProject("status-owner");
        Project other = saveProject("status-other");
        AutomationSuite suite = saveSuite(owner, "Stable status", AutomationSuiteStatus.ACTIVE);
        String ownerPath = suitePath(owner.getId(), suite.getId()) + "/status";

        mockMvc.perform(patch(ownerPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(ownerPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(suitePath(other.getId(), suite.getId()) + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isNotFound());

        assertThat(automationSuiteRepository.findById(suite.getId()).orElseThrow().getStatus())
                .isEqualTo(AutomationSuiteStatus.ACTIVE);
    }

    @Test
    void deleteRemovesSuiteAndReturnsEmpty204() throws Exception {
        Project project = saveProject("delete");
        AutomationSuite suite = saveSuite(project, "Delete suite", AutomationSuiteStatus.ACTIVE);

        mockMvc.perform(delete(suitePath(project.getId(), suite.getId())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(automationSuiteRepository.findById(suite.getId())).isEmpty();
    }

    @Test
    void missingAndCrossProjectDeleteReturn404AndPreserveOwnedSuite() throws Exception {
        Project owner = saveProject("delete-owner");
        Project other = saveProject("delete-other");
        AutomationSuite suite = saveSuite(owner, "Owned delete", AutomationSuiteStatus.ACTIVE);

        mockMvc.perform(delete(suitePath(owner.getId(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(suitePath(other.getId(), suite.getId())))
                .andExpect(status().isNotFound());

        assertThat(automationSuiteRepository.findById(suite.getId())).isPresent();
    }

    @Test
    void listForMissingProjectReturns404() throws Exception {
        UUID missingProjectId = UUID.randomUUID();
        String path = suitesPath(missingProjectId);

        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value(path));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_suite WHERE project_id = ?",
                Integer.class,
                missingProjectId)).isZero();
    }

    private String suitesPath(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/automation-suites";
    }

    private String suitePath(UUID projectId, UUID suiteId) {
        return suitesPath(projectId) + "/" + suiteId;
    }

    private Project saveProject(String label) {
        String suffix = UUID.randomUUID().toString();
        Workspace workspace = new Workspace();
        workspace.setName("AS-015F Workspace " + label + " " + suffix);
        workspace.setSlug(WORKSPACE_SLUG_PREFIX + suffix);
        workspace = workspaceRepository.saveAndFlush(workspace);

        Project project = new Project();
        project.setWorkspace(workspace);
        project.setName("AS-015F Project " + label + " " + suffix);
        return projectRepository.saveAndFlush(project);
    }

    private AutomationSuite saveSuite(
            Project project, String name, AutomationSuiteStatus status) {
        AutomationSuite suite = new AutomationSuite();
        suite.setProject(project);
        suite.setName(name);
        suite.setDescription("Suite description");
        suite.setEngineType("PLAYWRIGHT");
        suite.setSuiteReference("tests/" + name.toLowerCase().replace(' ', '-'));
        suite.setEngineId("playwright-java");
        suite.setSuiteType(SuiteType.UI);
        suite.setConfiguration(Map.of("browser", "chromium"));
        suite.setStatus(status);
        return automationSuiteRepository.saveAndFlush(suite);
    }

    private AutomationSuite suiteByName(UUID projectId, String name) {
        return automationSuiteRepository.findByProjectIdAndName(projectId, name).orElseThrow();
    }

    private CreateAutomationSuiteRequest createRequest(
            String name, AutomationSuiteStatus status) {
        return new CreateAutomationSuiteRequest(
                name, "Suite description", "PLAYWRIGHT",
                "tests/" + name.toLowerCase().replace(' ', '-'),
                "playwright-java", SuiteType.UI, Map.of("browser", "chromium"), status);
    }

    private UpdateAutomationSuiteRequest updateRequest(String name) {
        return new UpdateAutomationSuiteRequest(
                name, "Updated description", "SELENIUM", "tests/updated",
                "selenium-java", SuiteType.PERFORMANCE, Map.of("browser", "firefox"));
    }
}
