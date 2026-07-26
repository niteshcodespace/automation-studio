package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ExecutionHeartbeatException;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.HeartbeatFailure;
import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import com.automationstudio.api.service.result.RenewedExecutionLease;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

class ExecutionHeartbeatIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-019d-heartbeat-test";
    private static final String TEST_RUNNER = "as-019d-runner";
    private static final String WORKSPACE_PREFIX = "as-019d-heartbeat-";
    private static final Duration DURATION = Duration.ofMinutes(3);

    @Autowired
    private ExecutionHeartbeatService heartbeatService;

    @Autowired
    private ExecutionLeaseRepository leaseRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM execution_lease WHERE runner_id LIKE 'as-019d-%'");
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
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
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_PREFIX + "%");
    }

    @Test
    void renewsActiveOwnerAndChangesOnlyLeaseHeartbeatState() {
        LeaseFixture fixture = insertLease("CLAIMED", "2 minutes");
        Map<String, Object> executionBefore = executionRow(fixture.executionId());
        Map<String, Object> leaseBefore = leaseRow(fixture.executionId());

        RenewedExecutionLease result = heartbeatService.renew(command(fixture, 0));

        Map<String, Object> executionAfter = executionRow(fixture.executionId());
        Map<String, Object> leaseAfter = leaseRow(fixture.executionId());
        assertThat(executionAfter).isEqualTo(executionBefore);
        assertThat(leaseAfter.get("runner_id")).isEqualTo(TEST_RUNNER);
        assertThat(leaseAfter.get("claim_token")).isEqualTo(fixture.token());
        assertThat(leaseAfter.get("lease_generation")).isEqualTo(1L);
        assertThat(leaseAfter.get("version")).isEqualTo(1L);
        assertThat(leaseAfter.get("claimed_at")).isEqualTo(leaseBefore.get("claimed_at"));
        assertThat(leaseAfter.get("created_at")).isEqualTo(leaseBefore.get("created_at"));
        assertThat(((Timestamp) leaseAfter.get("last_heartbeat_at")).toInstant())
                .isAfter(((Timestamp) leaseBefore.get("last_heartbeat_at")).toInstant());
        assertThat(leaseAfter.get("updated_at"))
                .isNotEqualTo(leaseBefore.get("updated_at"));
        assertThat(result.leaseVersion()).isEqualTo(1);
        assertThat(result.lastHeartbeatAt().toInstant()).isEqualTo(
                ((Timestamp) leaseAfter.get("last_heartbeat_at")).toInstant());
        assertThat(result.leaseExpiresAt().toInstant()).isEqualTo(
                ((Timestamp) leaseAfter.get("lease_expires_at")).toInstant());
        assertThat(result.leaseExpiresAt()).isEqualTo(result.lastHeartbeatAt().plus(DURATION));
    }

    @Test
    void rejectsMissingLeaseAndEveryOwnershipOrGenerationMismatchWithoutMutation() {
        LeaseFixture fixture = insertLease("CLAIMED", "2 minutes");
        Map<String, Object> before = leaseRow(fixture.executionId());

        assertFailure(new RenewExecutionLeaseCommand(
                UUID.randomUUID(), TEST_RUNNER, fixture.token(), 1, 0, DURATION),
                HeartbeatFailure.LEASE_NOT_FOUND);
        assertFailure(new RenewExecutionLeaseCommand(
                fixture.executionId(), "wrong-runner", fixture.token(), 1, 0, DURATION),
                HeartbeatFailure.OWNERSHIP_MISMATCH);
        assertFailure(new RenewExecutionLeaseCommand(
                fixture.executionId(), TEST_RUNNER, UUID.randomUUID(), 1, 0, DURATION),
                HeartbeatFailure.OWNERSHIP_MISMATCH);
        LeaseFixture other = insertLease("CLAIMED", "2 minutes");
        assertFailure(new RenewExecutionLeaseCommand(
                fixture.executionId(), TEST_RUNNER, other.token(), 1, 0, DURATION),
                HeartbeatFailure.OWNERSHIP_MISMATCH);
        assertFailure(new RenewExecutionLeaseCommand(
                fixture.executionId(), TEST_RUNNER, fixture.token(), 2, 0, DURATION),
                HeartbeatFailure.STALE_GENERATION);
        assertFailure(new RenewExecutionLeaseCommand(
                fixture.executionId(), TEST_RUNNER, fixture.token(), 1, 1, DURATION),
                HeartbeatFailure.OPTIMISTIC_LOCK_CONFLICT);

        assertThat(leaseRow(fixture.executionId())).isEqualTo(before);
    }

    @Test
    void appliesStrictExpiryBoundaryWithoutMutation() {
        for (String expiry : List.of("-1 second", "0 seconds")) {
            LeaseFixture fixture = insertLease("CLAIMED", expiry);
            Map<String, Object> before = leaseRow(fixture.executionId());

            assertFailure(command(fixture, 0), HeartbeatFailure.EXPIRED_LEASE);

            assertThat(leaseRow(fixture.executionId())).isEqualTo(before);
        }
    }

    @Test
    void permitsOnlyClaimedLifecycleState() {
        for (String status : List.of(
                "PENDING", "RUNNING", "CANCEL_REQUESTED", "PASSED",
                "FAILED", "CANCELLED", "ERROR")) {
            LeaseFixture fixture = insertLease(status, "2 minutes");
            Map<String, Object> before = leaseRow(fixture.executionId());

            assertFailure(
                    command(fixture, 0), HeartbeatFailure.EXECUTION_STATE_INELIGIBLE);

            assertThat(leaseRow(fixture.executionId())).isEqualTo(before);
        }
        LeaseFixture claimed = insertLease("CLAIMED", "2 minutes");
        assertThat(heartbeatService.renew(command(claimed, 0)).leaseVersion()).isEqualTo(1);
    }

    @Test
    void concurrentSameVersionHeartbeatsHaveExactlyOneWinner() throws Exception {
        LeaseFixture fixture = insertLease("CLAIMED", "2 minutes");
        long executionVersion = ((Number) executionRow(fixture.executionId())
                .get("version")).longValue();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return catchThrowable(() -> heartbeatService.renew(command(fixture, 0)));
            });
            var second = executor.submit(() -> {
                start.await();
                return catchThrowable(() -> heartbeatService.renew(command(fixture, 0)));
            });
            start.countDown();
            List<Throwable> outcomes = java.util.Arrays.asList(first.get(), second.get());

            assertThat(outcomes).filteredOn(item -> item == null).hasSize(1);
            assertThat(outcomes).filteredOn(ExecutionHeartbeatException.class::isInstance)
                    .singleElement()
                    .satisfies(error -> assertThat(
                            ((ExecutionHeartbeatException) error).getFailure())
                            .isEqualTo(HeartbeatFailure.OPTIMISTIC_LOCK_CONFLICT));
        }
        assertThat(leaseRow(fixture.executionId()).get("version")).isEqualTo(1L);
        assertThat(executionRow(fixture.executionId()).get("version"))
                .isEqualTo(executionVersion);
    }

    @Test
    void heartbeatLosesToDatabaseExpiryAndUnrelatedLeaseStillRenews() throws Exception {
        LeaseFixture expiring = insertLease("CLAIMED", "2 minutes");
        LeaseFixture unrelated = insertLease("CLAIMED", "2 minutes");
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch expireRow = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(3)) {
            var expiry = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("""
                        SELECT execution_id FROM execution_lease
                        WHERE execution_id = ? FOR UPDATE
                        """, UUID.class, expiring.executionId());
                rowLocked.countDown();
                await(expireRow);
                jdbcTemplate.update("""
                        UPDATE execution_lease
                        SET lease_expires_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE execution_id = ?
                        """, expiring.executionId());
            }));
            rowLocked.await();
            var blockedHeartbeat = executor.submit(() -> catchThrowable(
                    () -> heartbeatService.renew(command(expiring, 0))));
            var unrelatedHeartbeat = executor.submit(
                    () -> heartbeatService.renew(command(unrelated, 0)));

            RenewedExecutionLease unrelatedResult = unrelatedHeartbeat.get();
            assertThat(unrelatedResult.leaseVersion()).isEqualTo(1);
            expireRow.countDown();
            expiry.get();
            Throwable expiryFailure = blockedHeartbeat.get();
            assertThat(expiryFailure).isInstanceOfSatisfying(
                    ExecutionHeartbeatException.class,
                    error -> assertThat(error.getFailure())
                            .isEqualTo(HeartbeatFailure.EXPIRED_LEASE));
        }
        assertThat(leaseRow(expiring.executionId()).get("version")).isEqualTo(0L);
    }

    @Test
    @Transactional
    void managedLeaseAndExecutionRemainCurrentAfterRenewal() {
        LeaseFixture fixture = insertLease("CLAIMED", "2 minutes");
        ExecutionLease managedLease = leaseRepository.findById(fixture.executionId()).orElseThrow();
        Execution managedExecution = managedLease.getExecution();
        long executionVersion = managedExecution.getVersion();

        RenewedExecutionLease result = heartbeatService.renew(command(fixture, 0));

        assertThat(managedLease.getVersion()).isEqualTo(1);
        assertThat(managedLease.getLastHeartbeatAt()).isEqualTo(result.lastHeartbeatAt());
        assertThat(managedLease.getLeaseExpiresAt()).isEqualTo(result.leaseExpiresAt());
        assertThat(managedExecution.getVersion()).isEqualTo(executionVersion);
        assertThat(managedExecution.getStatus().name()).isEqualTo("CLAIMED");
    }

    @Test
    void primaryKeySupportsHeartbeatLookupWithoutAnotherIndex() {
        LeaseFixture fixture = insertLease("CLAIMED", "2 minutes");
        jdbcTemplate.execute("SET enable_seqscan = off");
        try {
            List<String> plan = jdbcTemplate.query("""
                    EXPLAIN SELECT lease.execution_id
                    FROM execution_lease lease
                    WHERE lease.execution_id = ?
                    FOR UPDATE
                    """, (resultSet, rowNumber) -> resultSet.getString(1),
                    fixture.executionId());
            assertThat(plan).anyMatch(line ->
                    line.contains("execution_lease_pkey")
                            || line.contains("Index Scan"));
        } finally {
            jdbcTemplate.execute("RESET enable_seqscan");
        }
    }

    private void assertFailure(
            RenewExecutionLeaseCommand command, HeartbeatFailure failure) {
        ExecutionHeartbeatException exception = catchThrowableOfType(
                ExecutionHeartbeatException.class, () -> heartbeatService.renew(command));
        assertThat(exception.getFailure()).isEqualTo(failure);
        assertThat(exception.getMessage()).doesNotContain(command.claimToken().toString());
    }

    private RenewExecutionLeaseCommand command(LeaseFixture fixture, long version) {
        return new RenewExecutionLeaseCommand(
                fixture.executionId(), TEST_RUNNER, fixture.token(), 1, version, DURATION);
    }

    private LeaseFixture insertLease(String status, String expiryInterval) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-019D Workspace", WORKSPACE_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'AS-019D Project', 'ACTIVE')
                """, projectId, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'AS-019D Environment', 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status)
                VALUES (?, ?, 'AS-019D Suite', 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "tests/" + suffix);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot)
                VALUES (?, ?, ?, ?, 'SUITE', ?, ?, CURRENT_TIMESTAMP,
                    '{"region":"eu"}', '{"engine":"PLAYWRIGHT"}',
                    '{"selectionMode":"SUITE"}')
                """, executionId, projectId, environmentId, suiteId, status, TEST_ACTOR);
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1,
                    CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    CURRENT_TIMESTAMP + CAST(? AS INTERVAL), 0,
                    CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    CURRENT_TIMESTAMP - INTERVAL '1 minute')
                """, executionId, TEST_RUNNER, token, expiryInterval);
        entityManager.clear();
        return new LeaseFixture(executionId, token);
    }

    private Map<String, Object> executionRow(UUID executionId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, version, requested_at, requested_by,
                       environment_snapshot, suite_snapshot, request_snapshot
                FROM execution WHERE id = ?
                """, executionId);
    }

    private Map<String, Object> leaseRow(UUID executionId) {
        return jdbcTemplate.queryForMap("""
                SELECT runner_id, claim_token, lease_generation, claimed_at,
                       last_heartbeat_at, lease_expires_at, version, created_at, updated_at
                FROM execution_lease WHERE execution_id = ?
                """, executionId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Heartbeat concurrency test interrupted", exception);
        }
    }

    private record LeaseFixture(UUID executionId, UUID token) {
    }
}
