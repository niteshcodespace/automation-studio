package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class RunnerManagementApiIntegrationTest extends IntegrationTestBase {

    private static final String BASE_PATH = "/api/v1/runners";
    private static final String RUNNER_PREFIX = "as-020f-";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM runner_runtime
                WHERE runner_id IN (SELECT id FROM runner WHERE runner_key LIKE ?)
                """, RUNNER_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM runner WHERE runner_key LIKE ?", RUNNER_PREFIX + "%");
    }

    @Test
    void registrationGetAndListExposeDerivedHealthWithoutEntities() throws Exception {
        String key = RUNNER_PREFIX + "registry";
        var registration = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(key)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.runnerKey").value(key))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.health").value("ONLINE"))
                .andExpect(jsonPath("$.heartbeatCount").value(0))
                .andExpect(jsonPath("$.availableForDispatch").value(true))
                .andReturn();

        UUID runnerId = UUID.fromString(objectMapper
                .readTree(registration.getResponse().getContentAsString())
                .path("id")
                .asText());

        mockMvc.perform(get(BASE_PATH + "/" + runnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runnerId.toString()))
                .andExpect(jsonPath("$.runnerKey").value(key))
                .andExpect(jsonPath("$.runnerId").doesNotExist());
        mockMvc.perform(get(BASE_PATH)
                        .param("status", "ACTIVE")
                        .param("sort", "runnerKey,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].runnerKey").value(key));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM runner_runtime WHERE runner_id = ?",
                Integer.class,
                runnerId)).isEqualTo(1);
    }

    @Test
    void heartbeatUsesUuidAndKeyAndPreservesDisabledLifecycle() throws Exception {
        String key = RUNNER_PREFIX + "heartbeat";
        UUID runnerId = register(key);

        mockMvc.perform(post(BASE_PATH + "/" + runnerId + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runnerKey\":\"" + key.toUpperCase() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heartbeatCount").value(1))
                .andExpect(jsonPath("$.heartbeatVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch(BASE_PATH + "/" + runnerId + "/status")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.availableForDispatch").value(false));

        mockMvc.perform(post(BASE_PATH + "/" + runnerId + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runnerKey\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heartbeatCount").value(2))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void identityMismatchUnknownRunnerAndDeregisteredHeartbeatUseStructuredErrors()
            throws Exception {
        String key = RUNNER_PREFIX + "errors";
        UUID runnerId = register(key);

        mockMvc.perform(post(BASE_PATH + "/" + runnerId + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runnerKey\":\"other\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path")
                        .value(BASE_PATH + "/" + runnerId + "/heartbeats"));

        mockMvc.perform(get(BASE_PATH + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(patch(BASE_PATH + "/" + runnerId + "/status")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEREGISTERED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE_PATH + "/" + runnerId + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runnerKey\":\"" + key + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    private UUID register(String key) throws Exception {
        var result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(key)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    private static String registrationJson(String runnerKey) {
        return """
                {
                  "runnerKey":"%s",
                  "name":"AS-020F Runner",
                  "agentVersion":"1.0.0",
                  "hostname":"runner.internal",
                  "operatingSystem":"linux",
                  "architecture":"amd64",
                  "maxConcurrency":4,
                  "capabilities":{"features":["docker"]},
                  "labels":{"region":"test"}
                }
                """.formatted(runnerKey);
    }
}
