package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class ExecutionLeasePersistenceIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-019b-persistence-test";
    private static final String TEST_RUNNER = "runner-as-019b";
    private static final String WORKSPACE_SLUG_PREFIX = "as-019b-persistence-";

    @Autowired
    private ExecutionLeaseRepository leaseRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM execution_lease WHERE runner_id = ?", TEST_RUNNER);
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
    void persistsAndReadsLeaseByExecutionIdAndBasicLookups() {
        Execution execution = executionRepository.findById(insertExecution()).orElseThrow();
        UUID token = UUID.randomUUID();
        ExecutionLease saved = leaseRepository.saveAndFlush(newLease(execution, token));

        assertThat(saved.getExecutionId()).isEqualTo(execution.getId());
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        ExecutionLease loaded = leaseRepository.findById(execution.getId()).orElseThrow();
        assertThat(loaded.getExecution().getId()).isEqualTo(execution.getId());
        assertThat(loaded.getRunnerId()).isEqualTo(TEST_RUNNER);
        assertThat(loaded.getClaimToken()).isEqualTo(token);
        assertThat(loaded.getLeaseGeneration()).isEqualTo(1L);
        assertThat(leaseRepository.findByClaimToken(token))
                .hasValueSatisfying(lease ->
                        assertThat(lease.getExecutionId()).isEqualTo(execution.getId()));
        assertThat(leaseRepository.findByRunnerId(TEST_RUNNER))
                .extracting(ExecutionLease::getExecutionId)
                .containsExactly(execution.getId());
    }

    @Test
    void databaseEnforcesSingleLeaseUniqueTokenAndRestrictiveExecutionDelete() {
        UUID firstExecutionId = insertExecution();
        UUID secondExecutionId = insertExecution();
        UUID token = UUID.randomUUID();
        insertLease(firstExecutionId, token, 1, 0,
                "2026-07-25T10:00:00Z", "2026-07-25T10:00:00Z",
                "2026-07-25T10:01:00Z");

        assertThatThrownBy(() -> insertLease(firstExecutionId, UUID.randomUUID(), 1, 0,
                "2026-07-25T10:00:00Z", "2026-07-25T10:00:00Z",
                "2026-07-25T10:01:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLease(secondExecutionId, token, 1, 0,
                "2026-07-25T10:00:00Z", "2026-07-25T10:00:00Z",
                "2026-07-25T10:01:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM execution WHERE id = ?", firstExecutionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_lease", Integer.class)).isEqualTo(1);
        assertThat(executionRepository.findById(firstExecutionId)).isPresent();
    }

    @Test
    void databaseEnforcesGenerationVersionRunnerAndTimestampInvariants() {
        UUID executionId = insertExecution();

        assertInvalidLease(executionId, UUID.randomUUID(), 0, 0,
                "2026-07-25T10:00:00Z", "2026-07-25T10:00:00Z",
                "2026-07-25T10:01:00Z");
        assertInvalidLease(executionId, UUID.randomUUID(), 1, -1,
                "2026-07-25T10:00:00Z", "2026-07-25T10:00:00Z",
                "2026-07-25T10:01:00Z");
        assertInvalidLease(executionId, UUID.randomUUID(), 1, 0,
                "2026-07-25T10:00:01Z", "2026-07-25T10:00:00Z",
                "2026-07-25T10:01:00Z");
        assertInvalidLease(executionId, UUID.randomUUID(), 1, 0,
                "2026-07-25T10:00:00Z", "2026-07-25T10:00:00Z",
                "2026-07-25T10:00:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at
                ) VALUES (?, '   ', ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP + INTERVAL '1 minute', 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, executionId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(leaseRepository.findById(executionId)).isEmpty();
    }

    @Test
    void leaseRelationshipIsUnidirectionalAndExecutionHasNoOwnershipFields() {
        assertThat(Arrays.stream(ExecutionLease.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(Execution.class::equals)).isTrue();
        assertThat(Arrays.stream(Execution.class.getDeclaredFields())
                .map(Field::getType)
                .noneMatch(ExecutionLease.class::equals)).isTrue();
        assertThat(Arrays.stream(Execution.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain(
                        "runnerId",
                        "claimToken",
                        "leaseGeneration",
                        "claimedAt",
                        "lastHeartbeatAt",
                        "leaseExpiresAt");
    }

    private void assertInvalidLease(
            UUID executionId,
            UUID token,
            long generation,
            long version,
            String claimedAt,
            String heartbeatAt,
            String expiresAt) {
        assertThatThrownBy(() -> insertLease(
                executionId, token, generation, version, claimedAt, heartbeatAt, expiresAt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ExecutionLease newLease(Execution execution, UUID token) {
        OffsetDateTime claimedAt = OffsetDateTime.parse("2026-07-25T10:00:00Z");
        ExecutionLease lease = new ExecutionLease();
        lease.setExecution(execution);
        lease.setRunnerId(TEST_RUNNER);
        lease.setClaimToken(token);
        lease.setLeaseGeneration(1L);
        lease.setClaimedAt(claimedAt);
        lease.setLastHeartbeatAt(claimedAt);
        lease.setLeaseExpiresAt(claimedAt.plusMinutes(1));
        return lease;
    }

    private void insertLease(
            UUID executionId,
            UUID token,
            long generation,
            long version,
            String claimedAt,
            String heartbeatAt,
            String expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ),
                          CAST(? AS TIMESTAMPTZ), ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, executionId, TEST_RUNNER, token, generation, claimedAt, heartbeatAt,
                expiresAt, version);
    }

    private UUID insertExecution() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-019B Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-019B Project " + suffix);
        jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId, "AS-019B Environment " + suffix);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "AS-019B Suite " + suffix, "tests/" + suffix);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by
                ) VALUES (?, ?, ?, ?, 'SUITE', 'PENDING', ?)
                """, executionId, projectId, environmentId, suiteId, TEST_ACTOR);
        return executionId;
    }
}
