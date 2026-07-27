package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionVariableSource;
import com.automationstudio.api.service.ExecutionContextService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class ExecutionContextServiceIntegrationTest extends IntegrationTestBase {

    private static final String PREFIX = "as022b-";

    @Autowired private ExecutionContextService contextService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution_lease
                WHERE runner_id LIKE ?
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", PREFIX + "test");
        jdbcTemplate.update("""
                DELETE FROM runner_runtime
                WHERE runner_id IN (SELECT id FROM runner WHERE runner_key LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM runner WHERE runner_key LIKE ?", PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project
                WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE ?", PREFIX + "%");
    }

    @Test
    void loadsClaimTimeContextFromPostgreSqlWithoutMutatingSchedulingState() {
        Fixture fixture = insertFixture();

        ExecutionContext context = contextService.createContext(fixture.executionId());

        assertThat(context.executionId()).isEqualTo(fixture.executionId());
        assertThat(context.projectId()).isEqualTo(fixture.projectId());
        assertThat(context.workspaceId()).isEqualTo(fixture.workspaceId());
        assertThat(context.environment().environmentId()).isEqualTo(fixture.environmentId());
        assertThat(context.suite().suiteId()).isEqualTo(fixture.suiteId());
        assertThat(context.suite().engineVersion()).isEqualTo("1.52.0");
        assertThat(context.runner().runnerId()).isEqualTo(fixture.runnerId());
        assertThat(context.variables().get("baseUrl").value())
                .isEqualTo("https://override.test");
        assertThat(context.variables().get("baseUrl").source())
                .isEqualTo(ExecutionVariableSource.EXECUTION);
        assertThat(context.secretReferences()).singleElement()
                .satisfies(reference -> {
                    assertThat(reference.name()).isEqualTo("credential");
                    assertThat(reference.reference()).isEqualTo(
                            Map.of("provider", "vault", "path", "qa/api"));
                });
        assertThat(status(fixture.executionId())).isEqualTo("CLAIMED");
        assertThat(leaseCount(fixture.executionId())).isEqualTo(1);
    }

    private Fixture insertFixture() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID suite = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        UUID runner = UUID.randomUUID();
        String runnerKey = PREFIX + runner;
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, slug, status) VALUES (?, 'AS-022B', ?, 'ACTIVE')",
                workspace, PREFIX + workspace);
        jdbcTemplate.update(
                "INSERT INTO project (id, workspace_id, name, status) VALUES (?, ?, 'AS-022B', 'ACTIVE')",
                project, workspace);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'QA', 'https://example.test', 'TEST', 'ACTIVE')
                """, environment, project);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, engine_id, suite_reference, status
                ) VALUES (?, ?, 'Smoke', 'PLAYWRIGHT', 'playwright-java', 'tests/smoke', 'ACTIVE')
                """, suite, project);
        jdbcTemplate.update("""
                INSERT INTO runner (
                    id, runner_key, name, agent_version, hostname,
                    operating_system, architecture, max_concurrency,
                    capabilities, labels, status, registered_at, last_registered_at,
                    version, created_at, updated_at
                ) VALUES (
                    ?, ?, 'AS-022B', '1.0.0', 'runner.test',
                    'linux', 'amd64', 1, ?::jsonb, ?::jsonb, 'ACTIVE',
                    clock_timestamp(), clock_timestamp(), 0, clock_timestamp(), clock_timestamp()
                )
                """, runner, runnerKey,
                json(Map.of("engines", Map.of("playwright-java", "1.52.0"))),
                json(Map.of("region", "eu")));
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot
                ) VALUES (
                    ?, ?, ?, ?, 'SUITE', 'CLAIMED', ?, clock_timestamp(),
                    ?::jsonb, ?::jsonb, ?::jsonb
                )
                """, execution, project, environment, suite, PREFIX + "test",
                json(Map.of(
                        "id", environment.toString(),
                        "name", "QA",
                        "type", "TEST",
                        "baseUrl", "https://example.test",
                        "configuration", Map.of(
                                "variables", Map.of("baseUrl", "https://environment.test")),
                        "secretReferences", Map.of(
                                "credential", Map.of("provider", "vault", "path", "qa/api")))),
                json(Map.of(
                        "id", suite.toString(),
                        "name", "Smoke",
                        "engineType", "PLAYWRIGHT",
                        "engineId", "playwright-java",
                        "suiteReference", "tests/smoke",
                        "configuration", Map.of(
                                "variables", Map.of("baseUrl", "https://suite.test")))),
                json(Map.of(
                        "variables", Map.of("baseUrl", "https://override.test"),
                        "timeout", "PT15M")));
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
                """, execution, runnerKey, UUID.randomUUID());
        return new Fixture(workspace, project, environment, suite, execution, runner);
    }

    private String status(UUID executionId) {
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

    private record Fixture(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID suiteId,
            UUID executionId,
            UUID runnerId) {
    }
}
