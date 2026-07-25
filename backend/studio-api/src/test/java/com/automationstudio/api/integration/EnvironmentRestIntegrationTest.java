package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.repository.EnvironmentRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class EnvironmentRestIntegrationTest extends IntegrationTestBase {

    private static final String WORKSPACE_SLUG_PREFIX = "as-017f-rest-test-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EnvironmentRepository environmentRepository;

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
                DELETE FROM automation_test_case
                WHERE test_suite_id IN (
                    SELECT test_suite.id FROM test_suite
                    JOIN project ON project.id = test_suite.project_id
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
    void createCompleteAndDefaultedEnvironmentsRoundTripThroughPostgreSql() throws Exception {
        UUID projectId = insertProject("create");
        String completeBody = """
                {
                  "name":"QA",
                  "description":"Quality assurance",
                  "baseUrl":"https://qa.example.test/api",
                  "type":"QA",
                  "configuration":{"region":"eu-west-1","features":{"checkout":true}},
                  "secretReferences":{"token":"vault://qa/service-token"},
                  "status":"ACTIVE",
                  "isDefault":true
                }
                """;

        MvcResult created = mockMvc.perform(post(environmentsPath(projectId))
                        .contentType(MediaType.APPLICATION_JSON).content(completeBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.matchesPattern(
                                "http://localhost" + environmentsPath(projectId) + "/[0-9a-f-]+")))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("QA"))
                .andExpect(jsonPath("$.description").value("Quality assurance"))
                .andExpect(jsonPath("$.baseUrl").value("https://qa.example.test/api"))
                .andExpect(jsonPath("$.type").value("QA"))
                .andExpect(jsonPath("$.configuration.region").value("eu-west-1"))
                .andExpect(jsonPath("$.configuration.features.checkout").value(true))
                .andExpect(jsonPath("$.secretReferences.token")
                        .value("vault://qa/service-token"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn();

        JsonNode response = body(created);
        UUID environmentId = UUID.fromString(response.get("id").asText());
        Environment persisted = environmentRepository.findById(environmentId).orElseThrow();
        assertThat(persisted.getProject().getId()).isEqualTo(projectId);
        assertThat(persisted.getConfiguration()).containsEntry("region", "eu-west-1");
        assertThat(persisted.getSecretReferences())
                .containsOnlyKeys("token")
                .containsEntry("token", "vault://qa/service-token");
        assertThat(response.toString()).doesNotContain("resolved", "plaintext", "credential");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(configuration) FROM environment WHERE id = ?",
                String.class, environmentId)).isEqualTo("object");

        MvcResult defaulted = mockMvc.perform(post(environmentsPath(projectId))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Optional","baseUrl":"https://optional.example.test",
                                 "type":"TEST"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.configuration").isEmpty())
                .andExpect(jsonPath("$.secretReferences").isEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isDefault").value(false))
                .andReturn();
        Environment optional = environmentRepository.findById(
                UUID.fromString(body(defaulted).get("id").asText())).orElseThrow();
        assertThat(optional.getDescription()).isNull();
        assertThat(optional.getConfiguration()).isEmpty();
        assertThat(optional.getSecretReferences()).isEmpty();
    }

    @Test
    void missingAndCrossProjectRoutesRemainIndistinguishable404s() throws Exception {
        UUID owner = insertProject("scope-owner");
        UUID other = insertProject("scope-other");
        UUID missing = UUID.randomUUID();
        JsonNode created = create(owner, "Owned", false);
        UUID environmentId = UUID.fromString(created.get("id").asText());
        String updateBody = updateBody("Hidden");

        mockMvc.perform(post(environmentsPath(missing)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Missing", false))).andExpect(status().isNotFound());
        mockMvc.perform(get(environmentPath(owner, missing))).andExpect(status().isNotFound());
        mockMvc.perform(get(environmentsPath(missing))).andExpect(status().isNotFound());
        mockMvc.perform(get(environmentPath(other, environmentId))).andExpect(status().isNotFound());
        mockMvc.perform(put(environmentPath(other, environmentId)).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(environmentPath(other, environmentId) + "/status")
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}")).andExpect(status().isNotFound());
        mockMvc.perform(patch(environmentPath(other, environmentId) + "/default")
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isDefault\":true}")).andExpect(status().isNotFound());
        mockMvc.perform(delete(environmentPath(other, environmentId))
                        .header("If-Match", "\"0\"")).andExpect(status().isNotFound());

        assertThat(environmentRepository.findById(environmentId)).isPresent()
                .get().extracting(Environment::getName).isEqualTo("Owned");
    }

    @Test
    void listCombinesFiltersPaginatesSortsAndNeverLeaksProjects() throws Exception {
        UUID projectId = insertProject("list");
        UUID other = insertProject("list-other");
        create(projectId, "Charlie", false);
        create(projectId, "Alpha", true);
        JsonNode bravo = create(projectId, "Bravo", false);
        changeStatus(projectId, bravo, "INACTIVE");
        create(other, "Aaron other", true);

        mockMvc.perform(get(environmentsPath(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Bravo"))
                .andExpect(jsonPath("$.content[2].name").value("Charlie"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get(environmentsPath(projectId)).param("status", "ACTIVE")
                        .param("type", "TEST").param("isDefault", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get(environmentsPath(projectId)).param("page", "1").param("size", "2")
                        .param("sort", "name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void getReturnsCompleteStoredContract() throws Exception {
        UUID projectId = insertProject("get");
        JsonNode created = create(projectId, "Readable", true);

        mockMvc.perform(get(environmentPath(
                        projectId, UUID.fromString(created.get("id").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Readable"))
                .andExpect(jsonPath("$.baseUrl").value("https://readable.example.test"))
                .andExpect(jsonPath("$.type").value("TEST"))
                .andExpect(jsonPath("$.configuration.engine").value("playwright"))
                .andExpect(jsonPath("$.secretReferences.token")
                        .value("vault://synthetic/service-token"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void updateHonorsIfMatchVersionsUniquenessAndServerControlledFields() throws Exception {
        UUID projectId = insertProject("update");
        UUID other = insertProject("update-other");
        JsonNode target = create(projectId, "Original", true);
        create(projectId, "Duplicate", false);
        create(other, "Updated", false);
        UUID id = UUID.fromString(target.get("id").asText());
        Environment before = environmentRepository.findById(id).orElseThrow();
        OffsetDateTime createdAt = before.getCreatedAt();
        OffsetDateTime updatedAt = before.getUpdatedAt();
        String request = """
                {"name":"Updated","description":"Changed","baseUrl":"https://updated.example.test",
                 "type":"STAGING","configuration":{"region":"ap-south-1"},
                 "secretReferences":{"token":"kms://updated/token"},
                 "id":"00000000-0000-0000-0000-000000000000","status":"ARCHIVED",
                 "isDefault":false,"version":99}
                """;

        mockMvc.perform(put(environmentPath(projectId, id))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(environmentPath(projectId, id)).header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(environmentPath(projectId, id)).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.version").value(1));

        Environment updated = environmentRepository.findById(id).orElseThrow();
        assertThat(updated.getProject().getId()).isEqualTo(projectId);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updatedAt);
        assertThat(updated.getVersion()).isEqualTo(1);
        mockMvc.perform(put(environmentPath(projectId, id)).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("Stale")))
                .andExpect(status().isConflict());
        assertThat(environmentRepository.findById(id).orElseThrow().getName()).isEqualTo("Updated");
        mockMvc.perform(put(environmentPath(projectId, id)).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("Duplicate")))
                .andExpect(status().isConflict());
        assertThat(environmentRepository.findById(id).orElseThrow().getName()).isEqualTo("Updated");
    }

    @Test
    void lifecycleClearsDefaultIncrementsVersionAndNeverRestoresIt() throws Exception {
        UUID projectId = insertProject("lifecycle");
        JsonNode created = create(projectId, "Lifecycle", true);
        UUID id = UUID.fromString(created.get("id").asText());

        JsonNode inactive = changeStatus(projectId, created, "INACTIVE");
        assertThat(inactive.get("version").asLong()).isEqualTo(1);
        assertThat(inactive.get("isDefault").asBoolean()).isFalse();
        JsonNode archived = changeStatus(projectId, inactive, "ARCHIVED");
        assertThat(archived.get("version").asLong()).isEqualTo(2);
        JsonNode active = changeStatus(projectId, archived, "ACTIVE");
        assertThat(active.get("version").asLong()).isEqualTo(3);
        assertThat(active.get("isDefault").asBoolean()).isFalse();

        mockMvc.perform(patch(environmentPath(projectId, id) + "/status")
                        .header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isConflict());
        Environment persisted = environmentRepository.findById(id).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnvironmentStatus.ACTIVE);
        assertThat(persisted.isDefault()).isFalse();
        assertThat(persisted.getVersion()).isEqualTo(3);
    }

    @Test
    void defaultReplacementClearingIsolationAndConflictsFollowContract() throws Exception {
        UUID projectId = insertProject("default");
        UUID other = insertProject("default-other");
        JsonNode first = create(projectId, "First", true);
        JsonNode second = create(projectId, "Second", false);
        JsonNode otherDefault = create(other, "Other", true);
        UUID firstId = UUID.fromString(first.get("id").asText());
        UUID secondId = UUID.fromString(second.get("id").asText());

        MvcResult replaced = mockMvc.perform(patch(environmentPath(projectId, secondId) + "/default")
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isDefault\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.version").value(1)).andReturn();
        assertThat(body(replaced).get("id").asText()).isEqualTo(secondId.toString());
        assertThat(environmentRepository.findById(firstId).orElseThrow().isDefault()).isFalse();
        assertThat(environmentRepository.findById(firstId).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM environment WHERE project_id = ? AND is_default",
                Integer.class, projectId)).isEqualTo(1);
        assertThat(environmentRepository.findById(
                UUID.fromString(otherDefault.get("id").asText())).orElseThrow().isDefault()).isTrue();

        mockMvc.perform(patch(environmentPath(projectId, secondId) + "/default")
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isDefault\":false}")).andExpect(status().isConflict());
        mockMvc.perform(patch(environmentPath(projectId, secondId) + "/default")
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isDefault\":false}")).andExpect(status().isOk());
        assertThat(environmentRepository.findByProjectIdAndIsDefaultTrue(projectId)).isEmpty();

        JsonNode inactive = changeStatus(projectId,
                create(projectId, "Inactive", false), "INACTIVE");
        mockMvc.perform(patch(environmentPath(
                        projectId, UUID.fromString(inactive.get("id").asText())) + "/default")
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isDefault\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void deleteHonorsVersionsDefaultsAndExecutionReferences() throws Exception {
        UUID projectId = insertProject("delete");
        mockMvc.perform(delete(environmentPath(projectId, UUID.randomUUID()))
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNotFound());
        JsonNode removable = create(projectId, "Removable", true);
        UUID removableId = UUID.fromString(removable.get("id").asText());
        mockMvc.perform(delete(environmentPath(projectId, removableId))
                        .header("If-Match", "\"1\"")).andExpect(status().isConflict());
        assertThat(environmentRepository.findById(removableId)).isPresent();
        mockMvc.perform(delete(environmentPath(projectId, removableId))
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(environmentRepository.findById(removableId)).isEmpty();
        assertThat(environmentRepository.findByProjectIdAndIsDefaultTrue(projectId)).isEmpty();

        JsonNode referenced = create(projectId, "Referenced", false);
        UUID referencedId = UUID.fromString(referenced.get("id").asText());
        insertExecution(projectId, referencedId);
        MvcResult conflict = mockMvc.perform(delete(environmentPath(projectId, referencedId))
                        .header("If-Match", "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andReturn();
        assertThat(conflict.getResponse().getContentAsString())
                .doesNotContain("fk_execution_environment", "constraint", "SQL");
        assertThat(environmentRepository.findById(referencedId)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\" \",\"baseUrl\":\"https://valid.test\",\"type\":\"TEST\"}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"ftp://invalid.test\",\"type\":\"TEST\"}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://user@invalid.test\",\"type\":\"TEST\"}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://invalid.test/#fragment\",\"type\":\"TEST\"}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://valid.test\"}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://valid.test\",\"type\":\"UNKNOWN\"}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://valid.test\",\"type\":\"TEST\","
                    + "\"configuration\":[]}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://valid.test\",\"type\":\"TEST\","
                    + "\"secretReferences\":\"scalar\"}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://valid.test\",\"type\":\"TEST\","
                    + "\"configuration\":{\"nested\":{\"ApiKey\":\"do-not-echo-value\"}}}",
            "{\"name\":\"Invalid\",\"baseUrl\":\"https://valid.test\",\"type\":\"TEST\","
                    + "\"secretReferences\":{\"token\":\"not-qualified-do-not-echo\"}}"
    })
    void invalidCreateRequestsReturnSafe400AndPersistNothing(String request) throws Exception {
        UUID projectId = insertProject("invalid-" + UUID.randomUUID());

        MvcResult result = mockMvc.perform(post(environmentsPath(projectId))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value(environmentsPath(projectId)))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("do-not-echo-value", "not-qualified-do-not-echo");
        assertThat(environmentRepository.countByProjectId(projectId)).isZero();
    }

    @Test
    void malformedOversizedAndInvalidPatchBodiesAreSafeAndNonMutating() throws Exception {
        UUID projectId = insertProject("invalid-boundaries");
        JsonNode created = create(projectId, "Stable", false);
        UUID id = UUID.fromString(created.get("id").asText());
        String oversized = "x".repeat(65_537);

        mockMvc.perform(post(environmentsPath(projectId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Malformed\""))
                .andExpect(status().isBadRequest());
        MvcResult oversizedResult = mockMvc.perform(post(environmentsPath(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Oversized", "baseUrl", "https://large.test",
                                "type", "TEST", "configuration", Map.of("data", oversized)))))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(oversizedResult.getResponse().getContentAsString()).doesNotContain(oversized);
        mockMvc.perform(patch(environmentPath(projectId, id) + "/status")
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}")).andExpect(status().isBadRequest());
        mockMvc.perform(patch(environmentPath(projectId, id) + "/default")
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{}")).andExpect(status().isBadRequest());

        Environment persisted = environmentRepository.findById(id).orElseThrow();
        assertThat(persisted.getName()).isEqualTo("Stable");
        assertThat(persisted.getVersion()).isZero();
        assertThat(environmentRepository.countByProjectId(projectId)).isEqualTo(1);
    }

    @Test
    void createLengthLimitsReturn400WithoutPersistence() throws Exception {
        UUID projectId = insertProject("length-limits");
        Map<String, Object> valid = Map.of(
                "name", "Valid",
                "baseUrl", "https://valid.example.test",
                "type", "TEST");

        mockMvc.perform(post(environmentsPath(projectId)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "n".repeat(101),
                                "baseUrl", valid.get("baseUrl"),
                                "type", valid.get("type")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(environmentsPath(projectId)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", valid.get("name"),
                                "description", "d".repeat(1001),
                                "baseUrl", valid.get("baseUrl"),
                                "type", valid.get("type")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(environmentsPath(projectId)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", valid.get("name"),
                                "baseUrl", "https://" + "a".repeat(489) + ".test",
                                "type", valid.get("type")))))
                .andExpect(status().isBadRequest());

        assertThat(environmentRepository.countByProjectId(projectId)).isZero();
    }

    private JsonNode create(UUID projectId, String name, boolean isDefault) throws Exception {
        MvcResult result = mockMvc.perform(post(environmentsPath(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, isDefault)))
                .andExpect(status().isCreated()).andReturn();
        return body(result);
    }

    private JsonNode changeStatus(UUID projectId, JsonNode environment, String status)
            throws Exception {
        UUID id = UUID.fromString(environment.get("id").asText());
        long version = environment.get("version").asLong();
        MvcResult result = mockMvc.perform(patch(environmentPath(projectId, id) + "/status")
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return body(result);
    }

    private String createBody(String name, boolean isDefault) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", name,
                "description", "Environment " + name,
                "baseUrl", "https://" + name.toLowerCase().replace(' ', '-') + ".example.test",
                "type", EnvironmentType.TEST,
                "configuration", Map.of("engine", "playwright"),
                "secretReferences", Map.of("token", "vault://synthetic/service-token"),
                "status", EnvironmentStatus.ACTIVE,
                "isDefault", isDefault));
    }

    private String updateBody(String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", name,
                "description", "Updated",
                "baseUrl", "https://updated.example.test",
                "type", EnvironmentType.STAGING,
                "configuration", Map.of("region", "eu-west-1"),
                "secretReferences", Map.of("token", "vault://synthetic/updated-token")));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private UUID insertProject(String suffix) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String unique = suffix + "-" + workspaceId;
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-017F Workspace " + unique,
                WORKSPACE_SLUG_PREFIX + unique);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-017F Project " + unique);
        return projectId;
    }

    private void insertExecution(UUID projectId, UUID environmentId) {
        UUID suiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "AS-017F Suite " + suiteId, "tests/" + suiteId);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id,
                    selection_mode, status, requested_by
                ) VALUES (?, ?, ?, ?, 'SUITE', 'PENDING', 'as-017f-rest-test')
                """, UUID.randomUUID(), projectId, environmentId, suiteId);
    }

    private String environmentsPath(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/environments";
    }

    private String environmentPath(UUID projectId, UUID environmentId) {
        return environmentsPath(projectId) + "/" + environmentId;
    }
}
