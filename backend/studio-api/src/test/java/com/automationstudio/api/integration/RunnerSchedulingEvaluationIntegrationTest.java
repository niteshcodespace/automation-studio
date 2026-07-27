package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.RunnerEligibilityFailure;
import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerSchedulingEligibility;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.service.RunnerSchedulingEvaluationService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RunnerSchedulingEvaluationIntegrationTest extends IntegrationTestBase {

    private static final String RUNNER_PREFIX = "as021d-runner-";
    private static final String TEST_ACTOR = "as-021d-capacity-test";
    private static final String WORKSPACE_PREFIX = "as021d-";

    @Autowired
    private RunnerSchedulingEvaluationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExecutionLeaseRepository leaseRepository;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution_lease
                WHERE execution_id IN (
                    SELECT id FROM execution WHERE requested_by = ?
                )
                """, TEST_ACTOR);
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
        jdbcTemplate.update(
                "DELETE FROM runner_runtime WHERE runner_id IN "
                        + "(SELECT id FROM runner WHERE runner_key LIKE ?)",
                RUNNER_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM runner WHERE runner_key LIKE ?", RUNNER_PREFIX + "%");
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
    void countsOnlyUnexpiredOwnershipBearingLeasesForRunner() {
        Fixture fixture = insertFixture();
        RunnerFixture runner = insertRunner(3, true);
        insertLease(fixture, runner.runnerKey(), "CLAIMED", "10 minutes");
        insertLease(fixture, runner.runnerKey(), "RUNNING", "10 minutes");
        insertLease(fixture, runner.runnerKey(), "CANCEL_REQUESTED", "-1 second");
        insertLease(fixture, runner.runnerKey(), "PASSED", "10 minutes");
        insertLease(fixture, "another-runner", "CLAIMED", "10 minutes");

        RunnerSchedulingEligibility result = service.evaluate(runner.runnerKey());

        assertThat(result.eligible()).isTrue();
        assertThat(result.health()).isEqualTo(RunnerHealth.ONLINE);
        assertThat(result.capacity().maxConcurrency()).isEqualTo(3);
        assertThat(result.capacity().activeLeaseCount()).isEqualTo(2);
        assertThat(result.capacity().availableCapacity()).isEqualTo(1);
    }

    @Test
    void exactFullCapacityIsIneligibleAndEvaluationUsesDatabaseTime() {
        Fixture fixture = insertFixture();
        RunnerFixture runner = insertRunner(2, true);
        insertLease(fixture, runner.runnerKey(), "CLAIMED", "10 minutes");
        insertLease(fixture, runner.runnerKey(), "RUNNING", "10 minutes");
        OffsetDateTime before = databaseTime();

        RunnerSchedulingEligibility result = service.evaluate(runner.runnerKey());

        OffsetDateTime after = databaseTime();
        assertThat(result.evaluatedAt()).isBetween(before, after);
        assertThat(result.capacity().availableCapacity()).isZero();
        assertThat(result.failures())
                .containsExactly(RunnerEligibilityFailure.CAPACITY_EXHAUSTED);
    }

    @Test
    void missingRuntimeFailsClosedWithoutRepair() {
        RunnerFixture runner = insertRunner(2, false);

        RunnerSchedulingEligibility result = service.evaluate(runner.runnerKey());

        assertThat(result.eligible()).isFalse();
        assertThat(result.failures()).contains(RunnerEligibilityFailure.RUNTIME_MISSING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM runner_runtime WHERE runner_id = ?",
                Integer.class,
                runner.runnerId())).isZero();
    }

    @Test
    void evaluationDoesNotMutateRunnerRuntimeExecutionOrLease() {
        Fixture fixture = insertFixture();
        RunnerFixture runner = insertRunner(2, true);
        UUID executionId =
                insertLease(fixture, runner.runnerKey(), "CLAIMED", "10 minutes");
        Map<String, Object> before = jdbcTemplate.queryForMap("""
                SELECT runner.version AS runner_version,
                       runtime.version AS runtime_version,
                       runtime.heartbeat_count,
                       execution.status,
                       execution.version AS execution_version,
                       lease.version AS lease_version,
                       lease.lease_expires_at
                FROM runner
                JOIN runner_runtime runtime ON runtime.runner_id = runner.id
                JOIN execution_lease lease ON lease.runner_id = runner.runner_key
                JOIN execution ON execution.id = lease.execution_id
                WHERE runner.id = ? AND execution.id = ?
                """, runner.runnerId(), executionId);

        service.evaluate(runner.runnerKey());

        assertThat(jdbcTemplate.queryForMap("""
                SELECT runner.version AS runner_version,
                       runtime.version AS runtime_version,
                       runtime.heartbeat_count,
                       execution.status,
                       execution.version AS execution_version,
                       lease.version AS lease_version,
                       lease.lease_expires_at
                FROM runner
                JOIN runner_runtime runtime ON runtime.runner_id = runner.id
                JOIN execution_lease lease ON lease.runner_id = runner.runner_key
                JOIN execution ON execution.id = lease.execution_id
                WHERE runner.id = ? AND execution.id = ?
                """, runner.runnerId(), executionId)).isEqualTo(before);
    }

    @Test
    void leaseExpiringExactlyAtEvaluationTimeDoesNotConsumeCapacity() {
        Fixture fixture = insertFixture();
        RunnerFixture runner = insertRunner(1, true);
        UUID executionId =
                insertLease(fixture, runner.runnerKey(), "CLAIMED", "10 minutes");
        OffsetDateTime evaluatedAt = databaseTime();
        jdbcTemplate.update("""
                UPDATE execution_lease
                SET claimed_at = ? - INTERVAL '1 minute',
                    last_heartbeat_at = ? - INTERVAL '1 minute',
                    lease_expires_at = ?,
                    updated_at = ?
                WHERE execution_id = ?
                """, evaluatedAt, evaluatedAt, evaluatedAt, evaluatedAt, executionId);

        long count = leaseRepository.countCapacityConsumingLeases(
                runner.runnerKey(),
                evaluatedAt,
                Set.of(
                        ExecutionStatus.CLAIMED,
                        ExecutionStatus.RUNNING,
                        ExecutionStatus.CANCEL_REQUESTED));

        assertThat(count).isZero();
    }

    private RunnerFixture insertRunner(int maxConcurrency, boolean withRuntime) {
        UUID runnerId = UUID.randomUUID();
        String runnerKey = RUNNER_PREFIX + runnerId;
        jdbcTemplate.update("""
                INSERT INTO runner (
                    id, runner_key, name, agent_version, hostname, operating_system,
                    architecture, max_concurrency, capabilities, labels, status,
                    registered_at, last_registered_at, version, created_at, updated_at
                ) VALUES (
                    ?, ?, 'AS-021D Runner', '1.0.0', 'runner.example.test', 'linux',
                    'amd64', ?, '{"engines":{"playwright-java":"1.52.0"}}'::jsonb,
                    '{"region":"eu"}'::jsonb, 'ACTIVE', clock_timestamp(),
                    clock_timestamp(), 0, clock_timestamp(), clock_timestamp()
                )
                """, runnerId, runnerKey, maxConcurrency);
        if (withRuntime) {
            jdbcTemplate.update("""
                    INSERT INTO runner_runtime (
                        runner_id, last_seen_at, heartbeat_count, version,
                        created_at, updated_at
                    ) VALUES (
                        ?, clock_timestamp(), 1, 0, clock_timestamp(), clock_timestamp()
                    )
                    """, runnerId);
        }
        return new RunnerFixture(runnerId, runnerKey);
    }

    private Fixture insertFixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'AS-021D Workspace', ?, 'ACTIVE')
                """, workspaceId, WORKSPACE_PREFIX + workspaceId);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'AS-021D Project', 'ACTIVE')
                """, projectId, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'AS-021D Environment',
                        'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (
                    ?, ?, 'AS-021D Suite', 'PLAYWRIGHT', 'tests/as021d', 'ACTIVE'
                )
                """, suiteId, projectId);
        return new Fixture(projectId, environmentId, suiteId);
    }

    private UUID insertLease(
            Fixture fixture, String runnerId, String status, String expiryInterval) {
        UUID executionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at
                ) VALUES (?, ?, ?, ?, 'SUITE', ?, ?, clock_timestamp())
                """, executionId, fixture.projectId(), fixture.environmentId(),
                fixture.suiteId(), status, TEST_ACTOR);
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 1, clock_timestamp() - INTERVAL '1 minute',
                    clock_timestamp() - INTERVAL '1 minute',
                    clock_timestamp() + CAST(? AS interval), 0,
                    clock_timestamp() - INTERVAL '1 minute', clock_timestamp()
                )
                """, executionId, runnerId, UUID.randomUUID(), expiryInterval);
        return executionId;
    }

    private OffsetDateTime databaseTime() {
        return jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", OffsetDateTime.class);
    }

    private record Fixture(UUID projectId, UUID environmentId, UUID suiteId) {
    }

    private record RunnerFixture(UUID runnerId, String runnerKey) {
    }
}
