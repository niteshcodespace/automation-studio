package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.domain.SchedulingOutcome;
import com.automationstudio.api.exception.SchedulingOperationException;
import com.automationstudio.api.service.RunnerSchedulingService;
import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.service.result.SchedulingResult;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class RunnerSchedulingServiceIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-021e-scheduling-test";
    private static final String RUNNER_PREFIX = "as021e-runner-";

    @Autowired private RunnerSchedulingService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS trg_as021e_fail_lease ON execution_lease");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS as021e_fail_lease_insert()");
        jdbcTemplate.update("""
                DELETE FROM execution_lease
                WHERE execution_id IN (
                    SELECT id FROM execution WHERE requested_by = ?
                )
                """, TEST_ACTOR);
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
        jdbcTemplate.update("""
                DELETE FROM runner_runtime
                WHERE runner_id IN (
                    SELECT id FROM runner WHERE runner_key LIKE ?
                )
                """, RUNNER_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM runner WHERE runner_key LIKE ?", RUNNER_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE 'as021e-%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE 'as021e-%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM project
                WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE 'as021e-%'
                )
                """);
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE 'as021e-%'");
    }

    @Test
    void schedulesOldestCompatibleExecutionAndPersistsOneLease() {
        Fixture fixture = fixture();
        RunnerFixture runner = runner(2, "playwright-java", "eu");
        UUID incompatible = execution(
                fixture, "2026-07-27T09:00:00Z", "selenium-java", Map.of(), Map.of(), true);
        UUID oldest = execution(
                fixture, "2026-07-27T10:00:00Z", "playwright-java",
                Map.of("features", List.of("docker")), Map.of("region", "eu"), true);
        execution(
                fixture, "2026-07-27T11:00:00Z", "playwright-java", Map.of(), Map.of(), true);

        SchedulingResult result = schedule(runner.key());

        assertThat(result.outcome()).isEqualTo(SchedulingOutcome.SCHEDULED);
        assertThat(result.scheduledExecution()).hasValueSatisfying(claimed -> {
            assertThat(claimed.executionId()).isEqualTo(oldest);
            assertThat(claimed.runnerId()).isEqualTo(runner.key());
            assertThat(claimed.leaseExpiresAt())
                    .isEqualTo(claimed.claimedAt().plusMinutes(2));
        });
        assertThat(status(oldest)).isEqualTo("CLAIMED");
        assertThat(status(incompatible)).isEqualTo("PENDING");
        assertThat(leaseCount(oldest)).isEqualTo(1);
    }

    @Test
    void fullCapacityAndNoCompatibleWorkDoNotMutateQueue() {
        Fixture fixture = fixture();
        RunnerFixture full = runner(1, "playwright-java", "eu");
        UUID owned = execution(
                fixture, "2026-07-27T08:00:00Z", "playwright-java", Map.of(), Map.of(), true);
        jdbcTemplate.update("UPDATE execution SET status = 'CLAIMED' WHERE id = ?", owned);
        insertLease(owned, full.key(), "5 minutes");
        UUID pending = execution(
                fixture, "2026-07-27T09:00:00Z", "playwright-java", Map.of(), Map.of(), true);

        assertThat(schedule(full.key()).outcome())
                .isEqualTo(SchedulingOutcome.CAPACITY_EXHAUSTED);
        assertThat(status(pending)).isEqualTo("PENDING");
        assertThat(leaseCount(pending)).isZero();

        RunnerFixture noMatch = runner(2, "selenium-java", "us");
        assertThat(schedule(noMatch.key()).outcome())
                .isEqualTo(SchedulingOutcome.NO_COMPATIBLE_EXECUTION);
        assertThat(status(pending)).isEqualTo("PENDING");
    }

    @Test
    void expiredLeaseDoesNotConsumeCapacityAndMalformedSnapshotFailsClosed() {
        Fixture fixture = fixture();
        RunnerFixture runner = runner(1, "playwright-java", "eu");
        UUID expired = execution(
                fixture, "2026-07-27T08:00:00Z", "playwright-java", Map.of(), Map.of(), true);
        jdbcTemplate.update("UPDATE execution SET status = 'CLAIMED' WHERE id = ?", expired);
        insertLease(expired, runner.key(), "-1 second");
        UUID malformed = execution(
                fixture, "2026-07-27T09:00:00Z", "playwright-java", Map.of(), Map.of(), false);
        UUID valid = execution(
                fixture, "2026-07-27T10:00:00Z", "playwright-java", Map.of(), Map.of(), true);

        SchedulingResult result = schedule(runner.key());

        assertThat(result.scheduledExecution().orElseThrow().executionId()).isEqualTo(valid);
        assertThat(status(malformed)).isEqualTo("PENDING");
    }

    @Test
    void concurrentSameRunnerRequestsRespectMaxConcurrency() throws Exception {
        Fixture fixture = fixture();
        RunnerFixture runner = runner(1, "playwright-java", "eu");
        execution(
                fixture, "2026-07-27T09:00:00Z", "playwright-java", Map.of(), Map.of(), true);
        execution(
                fixture, "2026-07-27T10:00:00Z", "playwright-java", Map.of(), Map.of(), true);

        List<SchedulingResult> results = concurrentSchedules(runner.key(), runner.key());

        assertThat(results).extracting(SchedulingResult::outcome)
                .containsExactlyInAnyOrder(
                        SchedulingOutcome.SCHEDULED,
                        SchedulingOutcome.CAPACITY_EXHAUSTED);
        assertThat(activeLeaseCount(runner.key())).isEqualTo(1);
    }

    @Test
    void concurrentRequestsNeverExceedMultiSlotCapacity() throws Exception {
        Fixture fixture = fixture();
        RunnerFixture runner = runner(2, "playwright-java", "eu");
        for (int index = 0; index < 3; index++) {
            execution(
                    fixture,
                    "2026-07-27T0" + (index + 7) + ":00:00Z",
                    "playwright-java",
                    Map.of(),
                    Map.of(),
                    true);
        }

        List<SchedulingResult> results =
                concurrentSchedules(runner.key(), runner.key(), runner.key());

        assertThat(results).extracting(SchedulingResult::outcome)
                .containsExactlyInAnyOrder(
                        SchedulingOutcome.SCHEDULED,
                        SchedulingOutcome.SCHEDULED,
                        SchedulingOutcome.CAPACITY_EXHAUSTED);
        assertThat(activeLeaseCount(runner.key())).isEqualTo(2);
    }

    @Test
    void concurrentDifferentRunnersCannotOwnSameExecution() throws Exception {
        Fixture fixture = fixture();
        RunnerFixture first = runner(1, "playwright-java", "eu");
        RunnerFixture second = runner(1, "playwright-java", "eu");
        UUID executionId = execution(
                fixture, "2026-07-27T09:00:00Z", "playwright-java", Map.of(), Map.of(), true);

        List<SchedulingResult> results = concurrentSchedules(first.key(), second.key());

        assertThat(results).extracting(SchedulingResult::outcome)
                .containsExactlyInAnyOrder(
                        SchedulingOutcome.SCHEDULED,
                        SchedulingOutcome.NO_COMPATIBLE_EXECUTION);
        assertThat(leaseCount(executionId)).isEqualTo(1);
        assertThat(status(executionId)).isEqualTo("CLAIMED");
    }

    @Test
    void leaseInsertionFailureRollsBackExecutionTransition() {
        Fixture fixture = fixture();
        RunnerFixture runner = runner(1, "playwright-java", "eu");
        UUID executionId = execution(
                fixture, "2026-07-27T09:00:00Z", "playwright-java", Map.of(), Map.of(), true);
        jdbcTemplate.execute("""
                CREATE FUNCTION as021e_fail_lease_insert()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'AS-021E injected lease failure';
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_as021e_fail_lease
                BEFORE INSERT ON execution_lease
                FOR EACH ROW EXECUTE FUNCTION as021e_fail_lease_insert()
                """);

        assertThatThrownBy(() -> schedule(runner.key()))
                .isInstanceOf(SchedulingOperationException.class)
                .hasMessage("Atomic execution scheduling failed");

        assertThat(status(executionId)).isEqualTo("PENDING");
        assertThat(leaseCount(executionId)).isZero();
    }

    private List<SchedulingResult> concurrentSchedules(String... runnerKeys)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(runnerKeys.length);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(runnerKeys.length)) {
            List<Future<SchedulingResult>> futures =
                    java.util.Arrays.stream(runnerKeys).map(runnerKey ->
                            executor.submit(() -> {
                                ready.countDown();
                                start.await();
                                return schedule(runnerKey);
                            })).toList();
            ready.await();
            start.countDown();
            java.util.ArrayList<SchedulingResult> results = new java.util.ArrayList<>();
            for (Future<SchedulingResult> future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        }
    }

    private SchedulingResult schedule(String runnerKey) {
        return service.scheduleNext(
                new ScheduleExecutionCommand(runnerKey, Duration.ofMinutes(2)));
    }

    private Fixture fixture() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID suite = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, slug, status) VALUES (?, 'AS-021E', ?, 'ACTIVE')",
                workspace, "as021e-" + workspace);
        jdbcTemplate.update(
                "INSERT INTO project (id, workspace_id, name, status) VALUES (?, ?, 'AS-021E', 'ACTIVE')",
                project, workspace);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'AS-021E', 'https://example.test', 'TEST', 'ACTIVE')
                """, environment, project);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, engine_id,
                    suite_reference, status
                ) VALUES (
                    ?, ?, 'AS-021E', 'PLAYWRIGHT', 'playwright-java',
                    'tests/as021e', 'ACTIVE'
                )
                """, suite, project);
        return new Fixture(project, environment, suite);
    }

    private RunnerFixture runner(int capacity, String engine, String region) {
        UUID id = UUID.randomUUID();
        String key = RUNNER_PREFIX + id;
        jdbcTemplate.update("""
                INSERT INTO runner (
                    id, runner_key, name, agent_version, hostname,
                    operating_system, architecture, max_concurrency,
                    capabilities, labels, status, registered_at,
                    last_registered_at, version, created_at, updated_at
                ) VALUES (
                    ?, ?, 'AS-021E', '1.0', 'runner.test',
                    'linux', 'amd64', ?,
                    ?::jsonb, ?::jsonb, 'ACTIVE', clock_timestamp(),
                    clock_timestamp(), 0, clock_timestamp(), clock_timestamp()
                )
                """, id, key, capacity,
                json(Map.of(
                        "engines", Map.of(engine, "1.0"),
                        "features", List.of("docker"))),
                json(Map.of("region", region)));
        jdbcTemplate.update("""
                INSERT INTO runner_runtime (
                    runner_id, last_seen_at, heartbeat_count, version,
                    created_at, updated_at
                ) VALUES (
                    ?, clock_timestamp(), 1, 0,
                    clock_timestamp(), clock_timestamp()
                )
                """, id);
        return new RunnerFixture(id, key);
    }

    private UUID execution(
            Fixture fixture,
            String requestedAtText,
            String engine,
            Map<String, Object> capabilities,
            Map<String, String> labels,
            boolean complete) {
        UUID id = UUID.randomUUID();
        OffsetDateTime requestedAt = OffsetDateTime.parse(requestedAtText);
        Map<String, Object> environment = new LinkedHashMap<>();
        Map<String, Object> suite = new LinkedHashMap<>();
        Map<String, Object> request = new LinkedHashMap<>();
        if (complete) {
            environment.put("id", fixture.environment().toString());
            environment.put("name", "QA");
            environment.put("type", "TEST");
            environment.put("baseUrl", "https://example.test");
            environment.put("configuration", Map.of());
            environment.put("secretReferences", Map.of());
            suite.put("id", fixture.suite().toString());
            suite.put("name", "Suite");
            suite.put("engineType", "PLAYWRIGHT");
            suite.put("engineId", engine);
            suite.put("suiteType", null);
            suite.put("suiteReference", "tests/as021e");
            suite.put("configuration", Map.of());
            request.put("selectionMode", "SUITE");
            request.put("testCaseIds", List.of());
            request.put("requestedBy", TEST_ACTOR);
            request.put("requestedAt", requestedAt.toString());
            if (!capabilities.isEmpty()) {
                request.put("requiredCapabilities", capabilities);
            }
            if (!labels.isEmpty()) {
                request.put("requiredLabels", labels);
            }
        } else {
            suite.put("engineId", engine);
        }
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id,
                    selection_mode, status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot
                ) VALUES (
                    ?, ?, ?, ?, 'SUITE', 'PENDING', ?, ?,
                    ?::jsonb, ?::jsonb, ?::jsonb
                )
                """, id, fixture.project(), fixture.environment(), fixture.suite(),
                TEST_ACTOR, requestedAt, json(environment), json(suite), json(request));
        return id;
    }

    private void insertLease(UUID executionId, String runnerKey, String expiry) {
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
                """, executionId, runnerKey, UUID.randomUUID(), expiry);
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

    private int activeLeaseCount(String runnerKey) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM execution_lease
                WHERE runner_id = ? AND lease_expires_at > clock_timestamp()
                """, Integer.class, runnerKey);
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

    private record RunnerFixture(UUID id, String key) {
    }
}
