package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.ExecutionClaimService;
import com.automationstudio.api.service.ExecutionService;
import com.automationstudio.api.service.command.CancelExecutionCommand;
import com.automationstudio.api.service.command.ClaimExecutionCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

class ExecutionClaimIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-019c-claim-test";
    private static final String RUNNER_PREFIX = "as-019c-runner";
    private static final String WORKSPACE_SLUG_PREFIX = "as-019c-claim-";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    @Autowired
    private ExecutionClaimService claimService;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionLeaseRepository leaseRepository;

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
        when(tokenGenerator.nextToken()).thenAnswer(invocation -> UUID.randomUUID());
    }

    @AfterEach
    void cleanDatabase() {
        reset(tokenGenerator);
        jdbcTemplate.update(
                "DELETE FROM execution_lease WHERE runner_id LIKE ?", RUNNER_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id
                    FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id
                    FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project
                WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_SLUG_PREFIX + "%");
    }

    @Test
    void claimsOldestPendingAndPersistsLifecycleLeaseAndSnapshots() {
        Fixture fixture = insertFixture();
        UUID newerId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:01:00Z");
        UUID oldestId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");
        Map<String, Object> before = jdbcTemplate.queryForMap("""
                SELECT project_id, environment_id, test_suite_id, selection_mode,
                       requested_at, requested_by, environment_snapshot::text,
                       suite_snapshot::text, request_snapshot::text
                FROM execution
                WHERE id = ?
                """, oldestId);

        ClaimedExecution claimed = claimService.claimNext(
                new ClaimExecutionCommand(RUNNER_PREFIX + "-oldest", LEASE_DURATION))
                .orElseThrow();

        assertThat(claimed.executionId()).isEqualTo(oldestId);
        assertThat(claimed.status()).isEqualTo(ExecutionStatus.CLAIMED);
        assertThat(claimed.executionVersion()).isEqualTo(1);
        assertThat(claimed.runnerId()).isEqualTo(RUNNER_PREFIX + "-oldest");
        assertThat(claimed.claimToken()).isNotNull();
        assertThat(claimed.leaseGeneration()).isEqualTo(1);
        assertThat(claimed.environmentSnapshot()).containsEntry("region", "eu");
        assertThat(claimed.suiteSnapshot()).containsEntry("engine", "PLAYWRIGHT");
        assertThat(claimed.requestSnapshot()).containsEntry("selectionMode", "SUITE");

        Map<String, Object> execution = jdbcTemplate.queryForMap("""
                SELECT status, version, project_id, environment_id, test_suite_id,
                       selection_mode, requested_at, requested_by,
                       environment_snapshot::text, suite_snapshot::text,
                       request_snapshot::text
                FROM execution
                WHERE id = ?
                """, oldestId);
        assertThat(execution)
                .containsEntry("status", "CLAIMED")
                .containsEntry("version", 1L);
        assertThat(execution)
                .containsAllEntriesOf(before);
        Map<String, Object> persistedLease = jdbcTemplate.queryForMap("""
                SELECT runner_id, claim_token, lease_generation, version,
                       claimed_at, last_heartbeat_at, lease_expires_at
                FROM execution_lease
                WHERE execution_id = ?
                """, oldestId);
        assertThat(claimed.leaseVersion()).isEqualTo(persistedLease.get("version"));
        assertThat(persistedLease)
                .satisfies(lease -> {
                    assertThat(lease.get("runner_id")).isEqualTo(RUNNER_PREFIX + "-oldest");
                    assertThat(lease.get("claim_token")).isEqualTo(claimed.claimToken());
                    assertThat(lease.get("lease_generation")).isEqualTo(1L);
                    assertThat(lease.get("version")).isEqualTo(0L);
                    assertThat(lease.get("claimed_at")).isEqualTo(lease.get("last_heartbeat_at"));
                    assertThat(((Timestamp) lease.get("lease_expires_at")).toInstant())
                            .isEqualTo(((Timestamp) lease.get("claimed_at")).toInstant()
                                    .plus(LEASE_DURATION));
                });
        assertThat(executionRepository.findById(newerId).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void usesExecutionIdAsTieBreaker() {
        Fixture fixture = insertFixture();
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertExecution(fixture, secondId, "PENDING", "2026-07-25T10:00:00Z");
        insertExecution(fixture, firstId, "PENDING", "2026-07-25T10:00:00Z");

        assertThat(claim(RUNNER_PREFIX + "-tie").orElseThrow().executionId())
                .isEqualTo(firstId);
    }

    @Test
    void excludesIneligibleStatusesAndPendingExecutionThatAlreadyHasLease() {
        Fixture fixture = insertFixture();
        for (String status : List.of(
                "CLAIMED", "RUNNING", "CANCEL_REQUESTED",
                "PASSED", "FAILED", "CANCELLED", "ERROR")) {
            insertExecution(fixture, UUID.randomUUID(), status, "2026-07-25T10:00:00Z");
        }
        UUID leasedPending = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T09:59:00Z");
        insertLease(leasedPending, UUID.randomUUID(), RUNNER_PREFIX + "-existing");

        assertThat(claim(RUNNER_PREFIX + "-empty")).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM execution
                WHERE requested_by = ? AND status = 'CLAIMED'
                """, Integer.class, TEST_ACTOR)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidInputDoesNotMutateQueue() {
        Fixture fixture = insertFixture();
        UUID executionId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");

        for (ClaimExecutionCommand command : List.of(
                new ClaimExecutionCommand(" ", Duration.ofMinutes(1)),
                new ClaimExecutionCommand("x".repeat(151), Duration.ofMinutes(1)),
                new ClaimExecutionCommand("runner", Duration.ZERO),
                new ClaimExecutionCommand("runner", Duration.ofHours(24).plusSeconds(1)))) {
            assertThatThrownBy(() -> claimService.claimNext(command))
                    .isInstanceOf(InvalidRequestException.class);
        }

        assertPendingWithoutLease(executionId);
    }

    @Test
    void duplicateTokenFailureRollsBackLifecycleTransitionAndLease() {
        Fixture fixture = insertFixture();
        UUID existingExecution = insertExecution(
                fixture, UUID.randomUUID(), "CLAIMED", "2026-07-25T09:59:00Z");
        UUID duplicateToken = UUID.randomUUID();
        insertLease(existingExecution, duplicateToken, RUNNER_PREFIX + "-existing");
        UUID pendingExecution = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");
        when(tokenGenerator.nextToken()).thenReturn(duplicateToken);

        assertThatThrownBy(() -> claim(RUNNER_PREFIX + "-duplicate"))
                .isInstanceOf(DataIntegrityViolationException.class);

        entityManager.clear();
        assertPendingWithoutLease(pendingExecution);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease", Integer.class)).isEqualTo(1);
    }

    @Test
    @Transactional
    void managedPendingExecutionIsUpdatedWithoutStaleStateOrDoubleVersionIncrement() {
        Fixture fixture = insertFixture();
        UUID executionId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");
        Execution managed = entityManager.find(Execution.class, executionId);
        assertThat(entityManager.contains(managed)).isTrue();
        assertThat(managed.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(managed.getVersion()).isZero();

        ClaimedExecution claimed = claim(RUNNER_PREFIX + "-managed").orElseThrow();

        assertThat(entityManager.contains(managed)).isTrue();
        assertThat(managed.getStatus()).isEqualTo(ExecutionStatus.CLAIMED);
        assertThat(managed.getVersion()).isEqualTo(1);
        assertThat(claimed.status()).isEqualTo(ExecutionStatus.CLAIMED);
        assertThat(claimed.executionVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, version FROM execution WHERE id = ?", executionId))
                .containsEntry("status", "CLAIMED")
                .containsEntry("version", 1L);
    }

    @Test
    void concurrentClaimersProduceOneOwnerForOneExecution() throws Exception {
        Fixture fixture = insertFixture();
        UUID executionId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<ClaimedExecution>> first =
                concurrentClaim(start, RUNNER_PREFIX + "-concurrent-1");
        Callable<Optional<ClaimedExecution>> second =
                concurrentClaim(start, RUNNER_PREFIX + "-concurrent-2");

        List<Optional<ClaimedExecution>> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(first);
            var secondResult = executor.submit(second);
            start.countDown();
            results = List.of(firstResult.get(), secondResult.get());
        }

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(results).filteredOn(Optional::isEmpty).hasSize(1);
        assertThat(results.stream().flatMap(Optional::stream)
                .map(ClaimedExecution::executionId)).containsExactly(executionId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease WHERE execution_id = ?",
                Integer.class, executionId)).isEqualTo(1);
    }

    @Test
    void concurrentClaimersCanClaimDifferentExecutions() throws Exception {
        Fixture fixture = insertFixture();
        UUID firstId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");
        UUID secondId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:01:00Z");
        CountDownLatch start = new CountDownLatch(1);

        List<Optional<ClaimedExecution>> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(
                    concurrentClaim(start, RUNNER_PREFIX + "-different-1"));
            var second = executor.submit(
                    concurrentClaim(start, RUNNER_PREFIX + "-different-2"));
            start.countDown();
            results = List.of(first.get(), second.get());
        }

        assertThat(results).allMatch(Optional::isPresent);
        assertThat(results.stream().flatMap(Optional::stream)
                .map(ClaimedExecution::executionId))
                .containsExactlyInAnyOrder(firstId, secondId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease", Integer.class)).isEqualTo(2);
    }

    @Test
    void skipsLockedOldestCandidateWithoutWaitingForIt() throws Exception {
        Fixture fixture = insertFixture();
        UUID oldestId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");
        UUID nextId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:01:00Z");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var lockOwner = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM execution WHERE id = ? FOR UPDATE",
                        UUID.class, oldestId);
                locked.countDown();
                await(release);
            }));
            locked.await();
            try {
                assertThat(claim(RUNNER_PREFIX + "-skip").orElseThrow().executionId())
                        .isEqualTo(nextId);
            } finally {
                release.countDown();
            }
            lockOwner.get();
        }

        assertPendingWithoutLease(oldestId);
    }

    @Test
    void cancellationBeforeClaimRemovesEligibilityAndClaimBeforeCancellationAdvancesVersion() {
        Fixture fixture = insertFixture();
        UUID cancelledFirstId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");
        executionService.cancel(
                fixture.projectId(), cancelledFirstId, 0, TEST_ACTOR,
                new CancelExecutionCommand("cancel first"));
        assertThat(claim(RUNNER_PREFIX + "-after-cancel")).isEmpty();

        UUID claimedFirstId = insertExecution(
                fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:01:00Z");
        ClaimedExecution claimed = claim(RUNNER_PREFIX + "-before-cancel").orElseThrow();
        assertThat(claimed.executionId()).isEqualTo(claimedFirstId);
        assertThat(claimed.executionVersion()).isEqualTo(1);
        assertThatThrownBy(() -> executionService.cancel(
                fixture.projectId(), claimedFirstId, 0, TEST_ACTOR,
                new CancelExecutionCommand("stale")))
                .isInstanceOf(ResourceConflictException.class);

        Execution cancellationRequested = executionService.cancel(
                fixture.projectId(), claimedFirstId, 1, TEST_ACTOR,
                new CancelExecutionCommand("current"));
        assertThat(cancellationRequested.getStatus())
                .isEqualTo(ExecutionStatus.CANCEL_REQUESTED);
        assertThat(cancellationRequested.getVersion()).isEqualTo(2);
    }

    @Test
    void pendingQueueQueryUsesV11PartialIndex() {
        Fixture fixture = insertFixture();
        insertExecution(fixture, UUID.randomUUID(), "PENDING", "2026-07-25T10:00:00Z");

        List<String> plan = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList("""
                    EXPLAIN (COSTS OFF)
                    SELECT execution.id
                    FROM execution
                    WHERE execution.status = 'PENDING'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM execution_lease
                          WHERE execution_lease.execution_id = execution.id
                      )
                    ORDER BY execution.requested_at ASC, execution.id ASC
                    LIMIT 1
                    """, String.class);
        });

        assertThat(plan).anyMatch(line -> line.contains("idx_execution_pending_queue"));
    }

    private Callable<Optional<ClaimedExecution>> concurrentClaim(
            CountDownLatch start, String runnerId) {
        return () -> {
            start.await();
            return claim(runnerId);
        };
    }

    private Optional<ClaimedExecution> claim(String runnerId) {
        return claimService.claimNext(new ClaimExecutionCommand(runnerId, LEASE_DURATION));
    }

    private void assertPendingWithoutLease(UUID executionId) {
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, version FROM execution WHERE id = ?", executionId))
                .containsEntry("status", "PENDING")
                .containsEntry("version", 0L);
        assertThat(leaseRepository.findById(executionId)).isEmpty();
    }

    private Fixture insertFixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-019C Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-019C Project " + suffix);
        jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId, "AS-019C Environment " + suffix);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "AS-019C Suite " + suffix, "tests/" + suffix);
        return new Fixture(projectId, environmentId, suiteId);
    }

    private UUID insertExecution(
            Fixture fixture, UUID executionId, String status, String requestedAt) {
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot
                ) VALUES (?, ?, ?, ?, 'SUITE', ?, ?, CAST(? AS TIMESTAMPTZ),
                          CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                """, executionId, fixture.projectId(), fixture.environmentId(),
                fixture.suiteId(), status, TEST_ACTOR, requestedAt,
                "{\"region\":\"eu\"}",
                "{\"engine\":\"PLAYWRIGHT\"}",
                "{\"selectionMode\":\"SUITE\"}");
        return executionId;
    }

    private void insertLease(UUID executionId, UUID token, String runnerId) {
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP + INTERVAL '2 minutes', 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, executionId, runnerId, token);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent claim test interrupted", exception);
        }
    }

    private record Fixture(UUID projectId, UUID environmentId, UUID suiteId) {
    }
}
