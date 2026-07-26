package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
class RunnerApiIntegrationTest extends IntegrationTestBase {

    private static final String BASE_PATH = "/api/v1/runners";
    private static final String ACTOR = "as-019f-runner-api-test";
    private static final String RUNNER_PREFIX = "as-019f-";
    private static final String WORKSPACE_PREFIX = "as-019f-runner-api-";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update(
                "DELETE FROM execution_lease WHERE runner_id LIKE ?", RUNNER_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", ACTOR);
        jdbcTemplate.update("""
                DELETE FROM environment WHERE project_id IN (
                  SELECT project.id FROM project JOIN workspace
                    ON workspace.id = project.workspace_id
                  WHERE workspace.slug LIKE ?)
                """, WORKSPACE_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite WHERE project_id IN (
                  SELECT project.id FROM project JOIN workspace
                    ON workspace.id = project.workspace_id
                  WHERE workspace.slug LIKE ?)
                """, WORKSPACE_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project WHERE workspace_id IN (
                  SELECT id FROM workspace WHERE slug LIKE ?)
                """, WORKSPACE_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_PREFIX + "%");
    }

    @Test
    void claimHeartbeatAndReclaimRoundTripPersistsFencedLeaseState() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseRequest(RUNNER_PREFIX + "empty")))
                .andExpect(status().isNoContent());

        UUID executionId = insertPendingExecution();
        MvcResult claimResult = mockMvc.perform(post(BASE_PATH + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseRequest(RUNNER_PREFIX + "owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.leaseGeneration").value(1))
                .andExpect(jsonPath("$.leaseVersion").isNumber())
                .andExpect(jsonPath("$.environmentSnapshot.region").value("eu"))
                .andExpect(jsonPath("$.suiteSnapshot.engine").value("PLAYWRIGHT"))
                .andReturn();
        JsonNode claim = objectMapper.readTree(claimResult.getResponse().getContentAsString());
        String token = claim.path("claimToken").asText();
        long claimedLeaseVersion = claim.path("leaseVersion").asLong();
        assertThat(token).isNotBlank();
        assertThat(claimedLeaseVersion).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT version FROM execution_lease WHERE execution_id = ?",
                Long.class,
                executionId));

        assertHeartbeatConflict(
                executionId,
                RUNNER_PREFIX + "wrong-runner",
                token,
                1,
                claimedLeaseVersion,
                "Execution lease ownership credentials do not match");
        assertHeartbeatConflict(
                executionId,
                RUNNER_PREFIX + "owner",
                UUID.randomUUID().toString(),
                1,
                claimedLeaseVersion,
                "Execution lease ownership credentials do not match");
        assertHeartbeatConflict(
                executionId,
                RUNNER_PREFIX + "owner",
                token,
                2,
                claimedLeaseVersion,
                "Execution lease generation does not match");

        mockMvc.perform(post(BASE_PATH + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatRequest(
                                executionId,
                                RUNNER_PREFIX + "owner",
                                token,
                                1,
                                claimedLeaseVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.leaseGeneration").value(1))
                .andExpect(jsonPath("$.leaseVersion").value(claimedLeaseVersion + 1))
                .andExpect(jsonPath("$.claimToken").doesNotExist())
                .andExpect(jsonPath("$.runnerId").doesNotExist());

        assertHeartbeatConflict(
                executionId,
                RUNNER_PREFIX + "owner",
                token,
                1,
                claimedLeaseVersion,
                "Execution lease version does not match");

        jdbcTemplate.update(
                """
                UPDATE execution_lease
                SET claimed_at = CURRENT_TIMESTAMP - INTERVAL '3 minutes',
                    last_heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE execution_id = ?
                """,
                executionId);

        assertHeartbeatConflict(
                executionId,
                RUNNER_PREFIX + "owner",
                token,
                1,
                claimedLeaseVersion + 1,
                "Execution lease has expired");

        MvcResult reclaimResult = mockMvc.perform(post(BASE_PATH + "/reclaim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseRequest(RUNNER_PREFIX + "replacement")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.runnerId").value(RUNNER_PREFIX + "replacement"))
                .andExpect(jsonPath("$.leaseGeneration").value(2))
                .andExpect(jsonPath("$.leaseVersion").value(claimedLeaseVersion + 2))
                .andReturn();
        JsonNode reclaim =
                objectMapper.readTree(reclaimResult.getResponse().getContentAsString());
        assertThat(reclaim.path("claimToken").asText()).isNotEqualTo(token);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT runner_id, lease_generation, version
                FROM execution_lease WHERE execution_id = ?
                """, executionId))
                .containsEntry("runner_id", RUNNER_PREFIX + "replacement")
                .containsEntry("lease_generation", 2L)
                .containsEntry("version", claimedLeaseVersion + 2);

        mockMvc.perform(post(BASE_PATH + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatRequest(
                                UUID.randomUUID(),
                                RUNNER_PREFIX + "missing",
                                UUID.randomUUID().toString(),
                                1,
                                0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution lease was not found"))
                .andExpect(jsonPath("$.claimToken").doesNotExist());
    }

    private UUID insertPendingExecution() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-019F Workspace " + suffix,
                WORKSPACE_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-019F Project " + suffix);
        jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId, "AS-019F Environment " + suffix);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "AS-019F Suite " + suffix, "tests/" + suffix);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot
                ) VALUES (?, ?, ?, ?, 'SUITE', 'PENDING', ?, CURRENT_TIMESTAMP,
                          CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                """, executionId, projectId, environmentId, suiteId, ACTOR,
                "{\"region\":\"eu\"}",
                "{\"engine\":\"PLAYWRIGHT\"}",
                "{\"selectionMode\":\"SUITE\"}");
        return executionId;
    }

    private static String leaseRequest(String runnerId) {
        return """
                {"runnerId":"%s","leaseDuration":"PT2M"}
                """.formatted(runnerId);
    }

    private static String heartbeatRequest(
            UUID executionId, String runnerId, String token, long generation, long version) {
        return """
                {
                  "executionId":"%s",
                  "runnerId":"%s",
                  "claimToken":"%s",
                  "leaseGeneration":%d,
                  "leaseVersion":%d,
                  "leaseDuration":"PT2M"
                }
                """.formatted(executionId, runnerId, token, generation, version);
    }

    private void assertHeartbeatConflict(
            UUID executionId,
            String runnerId,
            String token,
            long generation,
            long version,
            String message) throws Exception {
        mockMvc.perform(post(BASE_PATH + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatRequest(
                                executionId, runnerId, token, generation, version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.claimToken").doesNotExist());
    }
}
