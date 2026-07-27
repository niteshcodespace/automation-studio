package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.RunnerCapabilities;
import com.automationstudio.api.domain.SchedulingCandidate;
import com.automationstudio.api.repository.SchedulingCandidateRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class SchedulingCandidateRepositoryIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-021c-candidate-test";
    private static final String WORKSPACE_PREFIX = "as021c-";

    @Autowired
    private SchedulingCandidateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution_lease
                WHERE execution_id IN (
                    SELECT id FROM execution WHERE requested_by = ?
                )
                """, TEST_ACTOR);
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id
                    FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id
                    FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project
                WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, WORKSPACE_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_PREFIX + "%");
    }

    @Test
    void returnsOldestCompatiblePendingExecutionWithoutMutation() {
        Fixture fixture = insertFixture();
        UUID incompatible = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T09:00:00Z",
                "selenium-java", Map.of(), Map.of(), true);
        UUID expected = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T10:00:00Z",
                "playwright-java", Map.of("features", List.of("docker")),
                Map.of("region", "eu"), true);
        UUID newer = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T11:00:00Z",
                "playwright-java", Map.of(), Map.of(), true);

        SchedulingCandidate candidate =
                repository.findNextCompatible(compatibleRunner()).orElseThrow();

        assertThat(candidate.executionId()).isEqualTo(expected);
        assertThat(candidate.requirements().engineId()).isEqualTo("playwright-java");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM execution
                WHERE id IN (?, ?, ?) AND status = 'PENDING'
                """, Integer.class, incompatible, expected, newer)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease", Integer.class)).isZero();
    }

    @Test
    void usesExecutionIdAsFifoTieBreaker() {
        Fixture fixture = insertFixture();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertExecution(
                fixture, second, "PENDING", "2026-07-27T10:00:00Z",
                "playwright-java", Map.of(), Map.of(), true);
        insertExecution(
                fixture, first, "PENDING", "2026-07-27T10:00:00Z",
                "playwright-java", Map.of(), Map.of(), true);

        assertThat(repository.findNextCompatible(compatibleRunner()).orElseThrow()
                .executionId()).isEqualTo(first);
    }

    @Test
    void excludesNonPendingLeasedAndConstraintMismatchedExecutions() {
        Fixture fixture = insertFixture();
        for (String status : List.of(
                "CLAIMED", "RUNNING", "CANCEL_REQUESTED",
                "PASSED", "FAILED", "CANCELLED", "ERROR")) {
            insertExecution(
                    fixture, UUID.randomUUID(), status, "2026-07-27T09:00:00Z",
                    "playwright-java", Map.of(), Map.of(), true);
        }
        UUID leased = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T09:01:00Z",
                "playwright-java", Map.of(), Map.of(), true);
        insertLease(leased);
        insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T09:02:00Z",
                "playwright-java", Map.of("features", List.of("mobile")),
                Map.of(), true);
        insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T09:03:00Z",
                "playwright-java", Map.of(), Map.of("region", "us"), true);

        assertThat(repository.findNextCompatible(compatibleRunner())).isEmpty();
    }

    @Test
    void historicalIncompleteSnapshotsFailClosedWithoutBlockingLaterCandidate() {
        Fixture fixture = insertFixture();
        insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T09:00:00Z",
                "playwright-java", Map.of(), Map.of(), false);
        UUID valid = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T10:00:00Z",
                "playwright-java", Map.of(), Map.of(), true);

        assertThat(repository.findNextCompatible(compatibleRunner()).orElseThrow()
                .executionId()).isEqualTo(valid);
    }

    @Test
    void compatibleFifoPredicateUsesV13EngineQueueIndex() {
        Fixture fixture = insertFixture();
        insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-27T10:00:00Z",
                "playwright-java", Map.of(), Map.of(), true);

        List<String> plan = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList("""
                    EXPLAIN (COSTS OFF)
                    SELECT execution.id
                    FROM execution
                    WHERE execution.status = 'PENDING'
                      AND NULLIF(BTRIM(
                          execution.suite_snapshot ->> 'engineId'
                      ), '') IS NOT NULL
                      AND execution.suite_snapshot ->> 'engineId' = 'playwright-java'
                    ORDER BY execution.requested_at ASC, execution.id ASC
                    LIMIT 1
                    """, String.class);
        });

        assertThat(plan)
                .anyMatch(line -> line.contains("idx_execution_pending_engine_queue"));
    }

    private RunnerCapabilities compatibleRunner() {
        return new RunnerCapabilities(
                Map.of(
                        "engines", Map.of("playwright-java", "1.52.0"),
                        "features", List.of("docker", "headless")),
                Map.of("region", "eu"));
    }

    private Fixture insertFixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'AS-021C Workspace', ?, 'ACTIVE')
                """, workspaceId, WORKSPACE_PREFIX + workspaceId);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'AS-021C Project', 'ACTIVE')
                """, projectId, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'AS-021C Environment',
                        'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, engine_id, suite_reference, status
                ) VALUES (
                    ?, ?, 'AS-021C Suite', 'PLAYWRIGHT', 'playwright-java',
                    'tests/as021c', 'ACTIVE'
                )
                """, suiteId, projectId);
        return new Fixture(projectId, environmentId, suiteId);
    }

    private UUID insertExecution(
            Fixture fixture,
            UUID id,
            String status,
            String requestedAtValue,
            String engineId,
            Map<String, Object> requiredCapabilities,
            Map<String, String> requiredLabels,
            boolean completeSnapshots) {
        OffsetDateTime requestedAt = OffsetDateTime.parse(requestedAtValue);
        Map<String, Object> environment = new LinkedHashMap<>();
        Map<String, Object> suite = new LinkedHashMap<>();
        Map<String, Object> request = new LinkedHashMap<>();
        if (completeSnapshots) {
            environment.put("id", fixture.environmentId().toString());
            environment.put("name", "QA");
            environment.put("type", "TEST");
            environment.put("baseUrl", "https://example.test");
            environment.put("configuration", Map.of());
            environment.put("secretReferences", Map.of());

            suite.put("id", fixture.suiteId().toString());
            suite.put("name", "Checkout");
            suite.put("engineType", "PLAYWRIGHT");
            suite.put("engineId", engineId);
            suite.put("suiteType", null);
            suite.put("suiteReference", "tests/as021c");
            suite.put("configuration", Map.of());

            request.put("selectionMode", "SUITE");
            request.put("testCaseIds", List.of());
            request.put("requestedBy", TEST_ACTOR);
            request.put("requestedAt", requestedAt.toString());
            if (!requiredCapabilities.isEmpty()) {
                request.put("requiredCapabilities", requiredCapabilities);
            }
            if (!requiredLabels.isEmpty()) {
                request.put("requiredLabels", requiredLabels);
            }
        } else {
            environment.put("name", "Incomplete");
            suite.put("engineId", engineId);
            request.put("selectionMode", "SUITE");
        }

        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at, environment_snapshot,
                    suite_snapshot, request_snapshot
                ) VALUES (
                    ?, ?, ?, ?, 'SUITE', ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb
                )
                """, id, fixture.projectId(), fixture.environmentId(), fixture.suiteId(),
                status, TEST_ACTOR, requestedAt, json(environment), json(suite), json(request));
        return id;
    }

    private void insertLease(UUID executionId) {
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at
                ) VALUES (
                    ?, 'as021c-runner', ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '2 minutes', 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """, executionId, UUID.randomUUID());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(UUID projectId, UUID environmentId, UUID suiteId) {
    }
}
