package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.ExecutionHeartbeatException;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.ExecutionReclaimException;
import com.automationstudio.api.service.ExecutionReclaimService;
import com.automationstudio.api.service.HeartbeatFailure;
import com.automationstudio.api.service.ReclaimFailure;
import com.automationstudio.api.service.command.ReclaimExecutionLeaseCommand;
import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

class ExecutionReclaimIntegrationTest extends IntegrationTestBase {

    private static final String ACTOR = "as-019e-reclaim-test";
    private static final String RUNNER_PREFIX = "as-019e-";
    private static final String WORKSPACE_PREFIX = "as-019e-reclaim-";
    private static final Duration DURATION = Duration.ofMinutes(3);

    @Autowired
    private ExecutionReclaimService reclaimService;
    @Autowired
    private ExecutionHeartbeatService heartbeatService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @MockitoBean
    private ClaimTokenGenerator tokenGenerator;

    @BeforeEach
    void configureTokens() {
        reset(tokenGenerator);
        when(tokenGenerator.nextToken()).thenAnswer(invocation -> UUID.randomUUID());
    }

    @AfterEach
    void cleanDatabase() {
        reset(tokenGenerator);
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
    void reclaimsSameRowRotatesTokenAndFencesOldOwnerWithoutChangingExecution() {
        LeaseFixture fixture = insertLease("CLAIMED", "-1 second", 1);
        Map<String, Object> executionBefore = executionRow(fixture.executionId());
        Map<String, Object> leaseBefore = leaseRow(fixture.executionId());

        var result = reclaim("new-runner").orElseThrow();

        Map<String, Object> leaseAfter = leaseRow(fixture.executionId());
        assertThat(result.executionId()).isEqualTo(fixture.executionId());
        assertThat(result.runnerId()).isEqualTo(RUNNER_PREFIX + "new-runner");
        assertThat(result.claimToken()).isNotEqualTo(fixture.token());
        assertThat(result.claimToken().toString()).isEqualTo(
                UUID.fromString(result.claimToken().toString()).toString());
        assertThat(result.leaseGeneration()).isEqualTo(2);
        assertThat(result.leaseVersion()).isEqualTo(1);
        assertThat(result.claimedAt()).isEqualTo(result.lastHeartbeatAt());
        assertThat(result.leaseExpiresAt()).isEqualTo(result.claimedAt().plus(DURATION));
        assertThat(leaseAfter.get("runner_id")).isEqualTo(result.runnerId());
        assertThat(leaseAfter.get("claim_token")).isEqualTo(result.claimToken());
        assertThat(leaseAfter.get("lease_generation")).isEqualTo(2L);
        assertThat(leaseAfter.get("version")).isEqualTo(1L);
        assertThat(leaseAfter.get("created_at")).isEqualTo(leaseBefore.get("created_at"));
        assertThat(leaseAfter.get("updated_at")).isNotEqualTo(leaseBefore.get("updated_at"));
        assertThat(executionRow(fixture.executionId())).isEqualTo(executionBefore);

        assertHeartbeatFailure(
                command(fixture.executionId(), RUNNER_PREFIX + "old", fixture.token(), 1, 0),
                HeartbeatFailure.OWNERSHIP_MISMATCH);
        assertThat(heartbeatService.renew(command(
                fixture.executionId(), result.runnerId(), result.claimToken(), 2, 1))
                .leaseVersion()).isEqualTo(2);
    }

    @Test
    void treatsEqualityAsExpiredAndProtectsActiveOrIneligibleLeases() {
        LeaseFixture equality = insertLease("CLAIMED", "0 seconds", 1);
        assertThat(reclaim("boundary")).get().extracting("executionId")
                .isEqualTo(equality.executionId());

        LeaseFixture active = insertLease("CLAIMED", "1 second", 1);
        Map<String, Object> activeBefore = leaseRow(active.executionId());
        assertThat(reclaim("active")).isEmpty();
        assertThat(leaseRow(active.executionId())).isEqualTo(activeBefore);

        for (String status : List.of(
                "PENDING", "RUNNING", "CANCEL_REQUESTED", "PASSED",
                "FAILED", "CANCELLED", "ERROR")) {
            LeaseFixture fixture = insertLease(status, "-1 second", 1);
            Map<String, Object> before = leaseRow(fixture.executionId());
            assertThat(reclaim("excluded-" + status)).isEmpty();
            assertThat(leaseRow(fixture.executionId())).isEqualTo(before);
        }
    }

    @Test
    void ordersExpiredWorkAndSupportsRepeatedReclaimWithoutTokenReuse() {
        LeaseFixture later = insertLease("CLAIMED", "-1 minute", 1);
        LeaseFixture oldest = insertLease("CLAIMED", "-2 minutes", 1);

        var first = reclaim("first").orElseThrow();
        assertThat(first.executionId()).isEqualTo(oldest.executionId());
        UUID firstToken = first.claimToken();
        var other = reclaim("other").orElseThrow();
        assertThat(other.executionId()).isEqualTo(later.executionId());
        expire(oldest.executionId());
        var second = reclaim("second").orElseThrow();
        assertThat(second.executionId()).isEqualTo(oldest.executionId());
        assertThat(second.leaseGeneration()).isEqualTo(3);
        assertThat(second.leaseVersion()).isEqualTo(2);
        assertThat(second.claimToken())
                .isNotIn(oldest.token(), firstToken, later.token());
        assertThat(executionRow(oldest.executionId()).get("version")).isEqualTo(0L);
    }

    @Test
    void concurrentReclaimersCommitOneOwnershipEpochForOneLease() throws Exception {
        LeaseFixture fixture = insertLease("CLAIMED", "-1 second", 1);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return catchThrowable(() -> reclaim("concurrent-a"));
            });
            var second = executor.submit(() -> {
                start.await();
                return catchThrowable(() -> reclaim("concurrent-b"));
            });
            start.countDown();
            List<Throwable> outcomes = java.util.Arrays.asList(first.get(), second.get());
            assertThat(outcomes).filteredOn(item -> item == null).hasSize(2);
        }
        Map<String, Object> lease = leaseRow(fixture.executionId());
        assertThat(lease.get("lease_generation")).isEqualTo(2L);
        assertThat(lease.get("version")).isEqualTo(1L);
        assertThat(lease.get("claim_token")).isNotEqualTo(fixture.token());
    }

    @Test
    void concurrentWorkersReclaimDifferentExpiredLeases() throws Exception {
        LeaseFixture firstFixture = insertLease("CLAIMED", "-2 seconds", 1);
        LeaseFixture secondFixture = insertLease("CLAIMED", "-1 second", 1);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return reclaim("multi-a").orElseThrow();
            });
            var second = executor.submit(() -> {
                start.await();
                return reclaim("multi-b").orElseThrow();
            });
            start.countDown();
            var results = List.of(first.get(), second.get());
            assertThat(results).extracting("executionId")
                    .containsExactlyInAnyOrder(
                            firstFixture.executionId(), secondFixture.executionId());
            assertThat(results).extracting("claimToken").doesNotHaveDuplicates();
        }
    }

    @Test
    void tokenFailureAndGenerationOverflowRollBackAllLeaseAndExecutionState() {
        LeaseFixture tokenFixture = insertLease("CLAIMED", "-1 second", 1);
        Map<String, Object> tokenLeaseBefore = leaseRow(tokenFixture.executionId());
        Map<String, Object> tokenExecutionBefore = executionRow(tokenFixture.executionId());
        when(tokenGenerator.nextToken()).thenThrow(new IllegalStateException("generator failed"));

        ExecutionReclaimException tokenFailure = catchThrowableOfType(
                ExecutionReclaimException.class, () -> reclaim("token-failure"));
        assertThat(tokenFailure.getFailure()).isEqualTo(ReclaimFailure.TOKEN_GENERATION_FAILED);
        assertThat(tokenFailure.getMessage()).doesNotContain(tokenFixture.token().toString());
        assertThat(leaseRow(tokenFixture.executionId())).isEqualTo(tokenLeaseBefore);
        assertThat(executionRow(tokenFixture.executionId())).isEqualTo(tokenExecutionBefore);

        reset(tokenGenerator);
        when(tokenGenerator.nextToken()).thenAnswer(invocation -> UUID.randomUUID());
        LeaseFixture overflowFixture =
                insertLease("CLAIMED", "-2 seconds", Long.MAX_VALUE);
        Map<String, Object> overflowBefore = leaseRow(overflowFixture.executionId());
        ExecutionReclaimException overflow = catchThrowableOfType(
                ExecutionReclaimException.class, () -> reclaim("overflow"));
        assertThat(overflow.getFailure()).isEqualTo(ReclaimFailure.GENERATION_OVERFLOW);
        assertThat(leaseRow(overflowFixture.executionId())).isEqualTo(overflowBefore);
    }

    @Test
    void heartbeatCommitsBeforeReclaimEvaluationAndKeepsOriginalOwner() throws Exception {
        LeaseFixture fixture = insertLease("CLAIMED", "2 minutes", 1);
        long executionVersion =
                ((Number) executionRow(fixture.executionId()).get("version")).longValue();
        CountDownLatch rowsLocked = new CountDownLatch(1);
        CountDownLatch allowLockRelease = new CountDownLatch(1);
        CountDownLatch heartbeatStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT lease.execution_id
                                FROM execution_lease lease
                                JOIN execution ON execution.id = lease.execution_id
                                WHERE lease.execution_id = ?
                                FOR UPDATE OF lease, execution
                                """, UUID.class, fixture.executionId());
                        rowsLocked.countDown();
                        await(allowLockRelease, "release heartbeat-wins row locks");
                    }));
            await(rowsLocked, "acquire heartbeat-wins row locks");

            var heartbeat = executor.submit(() -> {
                heartbeatStarted.countDown();
                return heartbeatService.renew(command(
                        fixture.executionId(),
                        RUNNER_PREFIX + "old",
                        fixture.token(),
                        1,
                        0));
            });
            await(heartbeatStarted, "start heartbeat");
            allowLockRelease.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);
            var renewed = heartbeat.get(10, TimeUnit.SECONDS);

            Optional<com.automationstudio.api.service.result.ReclaimedExecutionLease> reclaimed =
                    reclaim("heartbeat-loser");
            Map<String, Object> lease = leaseRow(fixture.executionId());
            Map<String, Object> execution = executionRow(fixture.executionId());

            assertThat(reclaimed).isEmpty();
            assertThat(renewed.lastHeartbeatAt().toInstant()).isEqualTo(
                    ((java.sql.Timestamp) lease.get("last_heartbeat_at")).toInstant());
            assertThat(renewed.leaseExpiresAt()).isEqualTo(
                    renewed.lastHeartbeatAt().plus(DURATION));
            assertThat(lease)
                    .containsEntry("runner_id", RUNNER_PREFIX + "old")
                    .containsEntry("claim_token", fixture.token())
                    .containsEntry("lease_generation", 1L)
                    .containsEntry("version", 1L);
            assertThat(execution)
                    .containsEntry("status", "CLAIMED")
                    .containsEntry("version", executionVersion);
        }
    }

    @Test
    void reclaimCommitsBeforeOldHeartbeatAndNewOwnerRemainsAuthoritative()
            throws Exception {
        LeaseFixture fixture = insertLease("CLAIMED", "-1 second", 1);
        long executionVersion =
                ((Number) executionRow(fixture.executionId()).get("version")).longValue();
        UUID newToken = UUID.randomUUID();
        CountDownLatch reclaimOwnsLocks = new CountDownLatch(1);
        CountDownLatch allowToken = new CountDownLatch(1);
        CountDownLatch heartbeatStarted = new CountDownLatch(1);
        when(tokenGenerator.nextToken()).thenAnswer(invocation -> {
            reclaimOwnsLocks.countDown();
            await(allowToken, "allow reclaim token generation");
            return newToken;
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var reclaim = executor.submit(() -> reclaim("race-winner").orElseThrow());
            await(reclaimOwnsLocks, "reclaim execution and lease locks");

            var staleHeartbeat = executor.submit(() -> {
                heartbeatStarted.countDown();
                return catchThrowable(() -> heartbeatService.renew(command(
                        fixture.executionId(),
                        RUNNER_PREFIX + "old",
                        fixture.token(),
                        1,
                        0)));
            });
            await(heartbeatStarted, "start stale heartbeat");
            allowToken.countDown();

            var reclaimed = reclaim.get(10, TimeUnit.SECONDS);
            Throwable staleFailure = staleHeartbeat.get(10, TimeUnit.SECONDS);
            assertThat(staleFailure).isInstanceOfSatisfying(
                    ExecutionHeartbeatException.class,
                    exception -> {
                        assertThat(exception.getFailure())
                                .isEqualTo(HeartbeatFailure.OWNERSHIP_MISMATCH);
                        assertThat(exception.getMessage())
                                .doesNotContain(fixture.token().toString())
                                .doesNotContain(newToken.toString());
                    });

            Map<String, Object> afterReclaim = leaseRow(fixture.executionId());
            assertThat(reclaimed.claimToken()).isEqualTo(newToken);
            assertThat(afterReclaim)
                    .containsEntry("runner_id", RUNNER_PREFIX + "race-winner")
                    .containsEntry("claim_token", newToken)
                    .containsEntry("lease_generation", 2L)
                    .containsEntry("version", 1L);
            assertThat(reclaimed.claimedAt()).isEqualTo(reclaimed.lastHeartbeatAt());
            assertThat(reclaimed.leaseExpiresAt())
                    .isEqualTo(reclaimed.claimedAt().plus(DURATION));

            var renewed = heartbeatService.renew(command(
                    fixture.executionId(),
                    reclaimed.runnerId(),
                    reclaimed.claimToken(),
                    2,
                    1));
            Map<String, Object> finalLease = leaseRow(fixture.executionId());
            assertThat(renewed.leaseVersion()).isEqualTo(2);
            assertThat(renewed.leaseGeneration()).isEqualTo(2);
            assertThat(finalLease)
                    .containsEntry("runner_id", reclaimed.runnerId())
                    .containsEntry("claim_token", newToken)
                    .containsEntry("lease_generation", 2L)
                    .containsEntry("version", 2L);
            assertThat(executionRow(fixture.executionId()))
                    .containsEntry("status", "CLAIMED")
                    .containsEntry("version", executionVersion);
        }
    }

    @Test
    void expiredCandidatePlanUsesExistingIndexesAndExplicitTieBreakerSort() {
        insertLease("CLAIMED", "-1 second", 1);
        jdbcTemplate.execute("SET enable_seqscan = off");
        try {
            List<String> plan = jdbcTemplate.query("""
                    EXPLAIN
                    SELECT execution.id
                    FROM execution_lease lease
                    JOIN execution ON execution.id = lease.execution_id
                    WHERE execution.status = 'CLAIMED'
                      AND lease.lease_expires_at <= CURRENT_TIMESTAMP
                    ORDER BY lease.lease_expires_at ASC,
                             execution.requested_at ASC,
                             execution.id ASC
                    FOR UPDATE OF lease, execution SKIP LOCKED
                    LIMIT 1
                    """, (resultSet, rowNumber) -> resultSet.getString(1));

            assertThat(plan).anyMatch(line ->
                    line.contains("execution_lease_pkey")
                            || line.contains("idx_execution_lease_expiry"));
            assertThat(plan).anyMatch(line -> line.contains("Sort"));
            assertThat(plan).anyMatch(line ->
                    line.contains("execution_pkey") || line.contains("Index Scan"));
        } finally {
            jdbcTemplate.execute("RESET enable_seqscan");
        }
    }

    private Optional<com.automationstudio.api.service.result.ReclaimedExecutionLease> reclaim(
            String runnerSuffix) {
        return reclaimService.reclaimNext(new ReclaimExecutionLeaseCommand(
                RUNNER_PREFIX + runnerSuffix, DURATION));
    }

    private void assertHeartbeatFailure(
            RenewExecutionLeaseCommand command, HeartbeatFailure expected) {
        ExecutionHeartbeatException exception = catchThrowableOfType(
                ExecutionHeartbeatException.class, () -> heartbeatService.renew(command));
        assertThat(exception.getFailure()).isEqualTo(expected);
        assertThat(exception.getMessage()).doesNotContain(command.claimToken().toString());
    }

    private RenewExecutionLeaseCommand command(
            UUID executionId, String runner, UUID token, long generation, long version) {
        return new RenewExecutionLeaseCommand(
                executionId, runner, token, generation, version, DURATION);
    }

    private void expire(UUID executionId) {
        jdbcTemplate.update("""
                UPDATE execution_lease
                SET lease_expires_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE execution_id = ?
                """, executionId);
        entityManager.clear();
    }

    private LeaseFixture insertLease(String status, String expiryInterval, long generation) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'AS-019E Workspace', ?, 'ACTIVE')
                """, workspaceId, WORKSPACE_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'AS-019E Project', 'ACTIVE')
                """, projectId, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'AS-019E Environment', 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status)
                VALUES (?, ?, 'AS-019E Suite', 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "tests/" + suffix);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot)
                VALUES (?, ?, ?, ?, 'SUITE', ?, ?, CURRENT_TIMESTAMP,
                    '{"region":"eu"}', '{"engine":"PLAYWRIGHT"}',
                    '{"selectionMode":"SUITE"}')
                """, executionId, projectId, environmentId, suiteId, status, ACTOR);
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?,
                    CURRENT_TIMESTAMP - INTERVAL '5 minutes',
                    CURRENT_TIMESTAMP - INTERVAL '4 minutes',
                    CURRENT_TIMESTAMP + CAST(? AS INTERVAL), 0,
                    CURRENT_TIMESTAMP - INTERVAL '5 minutes',
                    CURRENT_TIMESTAMP - INTERVAL '4 minutes')
                """, executionId, RUNNER_PREFIX + "old", token, generation, expiryInterval);
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

    private record LeaseFixture(UUID executionId, UUID token) {
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting to " + operation);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting to " + operation, exception);
        }
    }
}
