package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class RunnerClaimApiIntegrationTest extends IntegrationTestBase {

    private static final String PATH = "/api/v1/runners/claim";
    private static final String ACTOR = "as-021f-api-test";
    private static final String PREFIX = "as021f-runner-";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_as021f_fail_lease ON execution_lease");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS as021f_fail_lease_insert()");
        jdbcTemplate.update("""
                DELETE FROM execution_lease
                WHERE execution_id IN (
                    SELECT id FROM execution WHERE requested_by = ?
                )
                """, ACTOR);
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", ACTOR);
        jdbcTemplate.update("""
                DELETE FROM runner_runtime WHERE runner_id IN (
                    SELECT id FROM runner WHERE runner_key LIKE ?
                )
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM runner WHERE runner_key LIKE ?", PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE 'as021f-%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM test_suite WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE 'as021f-%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM project WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE 'as021f-%'
                )
                """);
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE 'as021f-%'");
    }

    @Test
    void completeRestFlowClaimsExecutionAndReturnsLease() throws Exception {
        Fixture fixture = fixture();
        String runnerKey = runner(1, "ACTIVE", "playwright-java");
        UUID executionId = execution(fixture, "2026-07-27T10:00:00Z", "playwright-java");

        mockMvc.perform(claim(runnerKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.runnerId").value(runnerKey))
                .andExpect(jsonPath("$.claimToken").isNotEmpty())
                .andExpect(jsonPath("$.leaseExpiresAt").isNotEmpty());

        assertThat(executionStatus(executionId)).isEqualTo("CLAIMED");
        assertThat(leaseCount(executionId)).isEqualTo(1);
    }

    @Test
    void mapsNoWorkMissingIneligibleAndCapacityOutcomes() throws Exception {
        String noWork = runner(1, "ACTIVE", "playwright-java");
        mockMvc.perform(claim(noWork)).andExpect(status().isNoContent());

        mockMvc.perform(claim(PREFIX + "missing"))
                .andExpect(status().isNotFound());

        String disabled = runner(1, "DISABLED", "playwright-java");
        mockMvc.perform(claim(disabled))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Runner is not eligible to schedule work"));

        Fixture fixture = fixture();
        String full = runner(1, "ACTIVE", "playwright-java");
        UUID owned = execution(fixture, "2026-07-27T09:00:00Z", "playwright-java");
        jdbcTemplate.update("UPDATE execution SET status = 'CLAIMED' WHERE id = ?", owned);
        insertLease(owned, full);
        execution(fixture, "2026-07-27T10:00:00Z", "playwright-java");

        mockMvc.perform(claim(full))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Runner scheduling capacity is exhausted"));
    }

    @Test
    void concurrentRestRequestsDoNotExceedRunnerCapacity() throws Exception {
        Fixture fixture = fixture();
        String runnerKey = runner(1, "ACTIVE", "playwright-java");
        execution(fixture, "2026-07-27T09:00:00Z", "playwright-java");
        execution(fixture, "2026-07-27T10:00:00Z", "playwright-java");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        MvcResult result = mockMvc.perform(claim(runnerKey)).andReturn();
                        return result.getResponse().getStatus();
                    }))
                    .toList();
            ready.await();
            start.countDown();

            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease WHERE runner_id = ?",
                Integer.class,
                runnerKey)).isEqualTo(1);
    }

    @Test
    void persistenceFailureReturnsSanitizedErrorAndRollsBack() throws Exception {
        Fixture fixture = fixture();
        String runnerKey = runner(1, "ACTIVE", "playwright-java");
        UUID executionId = execution(fixture, "2026-07-27T10:00:00Z", "playwright-java");
        jdbcTemplate.execute("""
                CREATE FUNCTION as021f_fail_lease_insert()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'AS-021F sensitive database detail';
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_as021f_fail_lease
                BEFORE INSERT ON execution_lease
                FOR EACH ROW EXECUTE FUNCTION as021f_fail_lease_insert()
                """);

        mockMvc.perform(claim(runnerKey))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

        assertThat(executionStatus(executionId)).isEqualTo("PENDING");
        assertThat(leaseCount(executionId)).isZero();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder claim(
            String runnerKey) {
        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"runnerId":"%s","leaseDuration":"PT2M"}
                        """.formatted(runnerKey));
    }

    private Fixture fixture() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID suite = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, slug, status) VALUES (?, 'AS-021F', ?, 'ACTIVE')",
                workspace, "as021f-" + workspace);
        jdbcTemplate.update(
                "INSERT INTO project (id, workspace_id, name, status) VALUES (?, ?, 'AS-021F', 'ACTIVE')",
                project, workspace);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'AS-021F', 'https://example.test', 'TEST', 'ACTIVE')
                """, environment, project);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, engine_id, suite_reference, status
                ) VALUES (
                    ?, ?, 'AS-021F', 'PLAYWRIGHT', 'playwright-java',
                    'tests/as021f', 'ACTIVE'
                )
                """, suite, project);
        return new Fixture(project, environment, suite);
    }

    private String runner(int capacity, String status, String engine) {
        UUID id = UUID.randomUUID();
        String key = PREFIX + id;
        jdbcTemplate.update("""
                INSERT INTO runner (
                    id, runner_key, name, agent_version, hostname,
                    operating_system, architecture, max_concurrency,
                    capabilities, labels, status, registered_at,
                    last_registered_at, version, created_at, updated_at
                ) VALUES (
                    ?, ?, 'AS-021F', '1.0', 'runner.test',
                    'linux', 'amd64', ?, ?::jsonb, '{}'::jsonb, ?,
                    clock_timestamp(), clock_timestamp(), 0,
                    clock_timestamp(), clock_timestamp()
                )
                """, id, key, capacity, json(Map.of("engines", Map.of(engine, "1.0"))), status);
        jdbcTemplate.update("""
                INSERT INTO runner_runtime (
                    runner_id, last_seen_at, heartbeat_count, version, created_at, updated_at
                ) VALUES (?, clock_timestamp(), 1, 0, clock_timestamp(), clock_timestamp())
                """, id);
        return key;
    }

    private UUID execution(Fixture fixture, String requestedAt, String engine) {
        UUID id = UUID.randomUUID();
        OffsetDateTime time = OffsetDateTime.parse(requestedAt);
        Map<String, Object> environment = Map.of(
                "id", fixture.environment().toString(),
                "name", "QA",
                "type", "TEST",
                "baseUrl", "https://example.test",
                "configuration", Map.of(),
                "secretReferences", Map.of());
        Map<String, Object> suite = new LinkedHashMap<>();
        suite.put("id", fixture.suite().toString());
        suite.put("name", "Suite");
        suite.put("engineType", "PLAYWRIGHT");
        suite.put("engineId", engine);
        suite.put("suiteType", null);
        suite.put("suiteReference", "tests/as021f");
        suite.put("configuration", Map.of());
        Map<String, Object> request = Map.of(
                "selectionMode", "SUITE",
                "testCaseIds", List.of(),
                "requestedBy", ACTOR,
                "requestedAt", time.toString());
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id,
                    selection_mode, status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot
                ) VALUES (?, ?, ?, ?, 'SUITE', 'PENDING', ?, ?,
                    ?::jsonb, ?::jsonb, ?::jsonb)
                """, id, fixture.project(), fixture.environment(), fixture.suite(),
                ACTOR, time, json(environment), json(suite), json(request));
        return id;
    }

    private void insertLease(UUID executionId, String runnerKey) {
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 1, clock_timestamp(), clock_timestamp(),
                    clock_timestamp() + INTERVAL '5 minutes', 0,
                    clock_timestamp(), clock_timestamp()
                )
                """, executionId, runnerKey, UUID.randomUUID());
    }

    private String executionStatus(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM execution WHERE id = ?", String.class, executionId);
    }

    private int leaseCount(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease WHERE execution_id = ?",
                Integer.class, executionId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(UUID project, UUID environment, UUID suite) {
    }
}
