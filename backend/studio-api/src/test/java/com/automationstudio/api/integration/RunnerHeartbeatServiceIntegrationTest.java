package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.RunnerRegistrationService;
import com.automationstudio.api.service.command.RecordRunnerHeartbeatCommand;
import com.automationstudio.api.service.command.RegisterRunnerCommand;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class RunnerHeartbeatServiceIntegrationTest extends IntegrationTestBase {

    private static final String KEY_PREFIX = "as020e-";

    @Autowired
    private RunnerHeartbeatService heartbeatService;

    @Autowired
    private RunnerRegistrationService registrationService;

    @Autowired
    private RunnerRepository runnerRepository;

    @Autowired
    private RunnerRuntimeRepository runtimeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("""
                DELETE FROM runner_runtime
                WHERE runner_id IN (
                    SELECT id FROM runner WHERE runner_key LIKE ?
                )
                """, KEY_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM runner WHERE runner_key LIKE ?", KEY_PREFIX + "%");
    }

    @Test
    void activeHeartbeatUpdatesOnlyRuntimeWithDatabaseTime() {
        Runner runner = register("active");
        Runner before = runnerRepository.findById(runner.getId()).orElseThrow();
        var runtimeBefore = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();

        var result = heartbeatService.recordHeartbeat(command("active"));

        Runner after = runnerRepository.findById(runner.getId()).orElseThrow();
        var runtimeAfter = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        assertThat(result.lastSeenAt()).isAfterOrEqualTo(runtimeBefore.getLastSeenAt());
        assertThat(result.heartbeatCount()).isEqualTo(1);
        assertThat(result.runtimeVersion()).isEqualTo(runtimeBefore.getVersion() + 1);
        assertThat(runtimeAfter.getLastSeenAt()).isEqualTo(result.lastSeenAt());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getName()).isEqualTo(before.getName());
        assertThat(after.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(runtimeRepository.count()).isEqualTo(1);
    }

    @Test
    void disabledHeartbeatIsAcceptedWithoutReactivation() {
        Runner runner = register("disabled");
        setStatus(runner.getId(), RunnerStatus.DISABLED);
        Runner before = runnerRepository.findById(runner.getId()).orElseThrow();

        var result = heartbeatService.recordHeartbeat(command("disabled"));

        Runner after = runnerRepository.findById(runner.getId()).orElseThrow();
        assertThat(result.heartbeatCount()).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(RunnerStatus.DISABLED);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
    }

    @Test
    void deregisteredHeartbeatIsRejectedWithoutRuntimeMutation() {
        Runner runner = register("deregistered");
        setStatus(runner.getId(), RunnerStatus.DEREGISTERED);
        var before = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();

        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> heartbeatService.recordHeartbeat(command("deregistered")));

        var after = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        assertThat(after.getHeartbeatCount()).isEqualTo(before.getHeartbeatCount());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getLastSeenAt()).isEqualTo(before.getLastSeenAt());
    }

    @Test
    void unknownRunnerAndMissingRuntimeAreDistinguished() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> heartbeatService.recordHeartbeat(command("unknown")));

        Runner runner = register("missing-runtime");
        runtimeRepository.deleteById(runner.getId());
        runtimeRepository.flush();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> heartbeatService.recordHeartbeat(
                        command("missing-runtime")));
        assertThat(runtimeRepository.findByRunnerId(runner.getId())).isEmpty();
    }

    @Test
    void sequentialHeartbeatsPreserveMonotonicState() {
        Runner runner = register("sequential");
        List<OffsetDateTime> timestamps = new ArrayList<>();

        for (int index = 0; index < 5; index++) {
            timestamps.add(heartbeatService.recordHeartbeat(command("sequential")).lastSeenAt());
        }

        assertThat(timestamps).isSorted();
        var runtime = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        assertThat(runtime.getHeartbeatCount()).isEqualTo(5);
        assertThat(runtime.getVersion()).isEqualTo(5);
    }

    @Test
    void concurrentHeartbeatsPreserveEveryIncrementWithoutDeadlock() throws Exception {
        Runner runner = register("concurrent");
        int participants = 8;
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<OffsetDateTime>> tasks = new ArrayList<>();
        for (int index = 0; index < participants; index++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return heartbeatService.recordHeartbeat(command("concurrent")).lastSeenAt();
            });
        }

        List<OffsetDateTime> acceptedTimes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(participants)) {
            List<Future<OffsetDateTime>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();
            ready.await();
            start.countDown();
            for (Future<OffsetDateTime> future : futures) {
                acceptedTimes.add(future.get());
            }
        }

        var runtime = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        assertThat(runtime.getHeartbeatCount()).isEqualTo(participants);
        assertThat(runtime.getVersion()).isEqualTo(participants);
        assertThat(runtime.getLastSeenAt()).isEqualTo(acceptedTimes.stream().max(
                OffsetDateTime::compareTo).orElseThrow());
        assertThat(runtimeRepository.count()).isEqualTo(1);
        assertThat(runnerRepository.findById(runner.getId()).orElseThrow().getVersion())
                .isZero();
    }

    @Test
    void heartbeatPersistenceFailureRollsBackRuntimeMutation() {
        Runner runner = register("rollback");
        var before = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION reject_as020e_heartbeat()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.runner_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'synthetic heartbeat failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(runner.getId()));
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_as020e_heartbeat_trigger
                BEFORE UPDATE ON runner_runtime
                FOR EACH ROW EXECUTE FUNCTION reject_as020e_heartbeat()
                """);
        try {
            assertThatExceptionOfType(ResourceConflictException.class)
                    .isThrownBy(() -> heartbeatService.recordHeartbeat(command("rollback")));
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER reject_as020e_heartbeat_trigger ON runner_runtime");
            jdbcTemplate.execute("DROP FUNCTION reject_as020e_heartbeat()");
        }

        var after = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        assertThat(after.getHeartbeatCount()).isEqualTo(before.getHeartbeatCount());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getLastSeenAt()).isEqualTo(before.getLastSeenAt());
    }

    @Test
    void healthBoundariesAreDerivedReadOnlyFromRuntimeState() {
        Runner runner = register("health");
        OffsetDateTime evaluatedAt = OffsetDateTime.parse("2026-07-26T12:00:00Z");

        assertHealth(runner, evaluatedAt.minusMinutes(1), evaluatedAt, RunnerHealth.ONLINE);
        assertHealth(
                runner,
                evaluatedAt.minusMinutes(1).minusNanos(1_000),
                evaluatedAt,
                RunnerHealth.STALE);
        assertHealth(runner, evaluatedAt.minusMinutes(5), evaluatedAt, RunnerHealth.STALE);
        assertHealth(
                runner,
                evaluatedAt.minusMinutes(5).minusNanos(1_000),
                evaluatedAt,
                RunnerHealth.OFFLINE);
    }

    @Test
    void healthRetainsDisabledAndDeregisteredLifecycleSeparately() {
        Runner disabled = register("health-disabled");
        setStatus(disabled.getId(), RunnerStatus.DISABLED);
        OffsetDateTime evaluatedAt = currentRuntimeTime(disabled.getId());
        var disabledHealth = heartbeatService.evaluateHealth(disabled.getId(), evaluatedAt);
        assertThat(disabledHealth.health()).isEqualTo(RunnerHealth.ONLINE);
        assertThat(disabledHealth.lifecycleStatus()).isEqualTo(RunnerStatus.DISABLED);

        Runner deregistered = register("health-deregistered");
        setStatus(deregistered.getId(), RunnerStatus.DEREGISTERED);
        var deregisteredHealth = heartbeatService.evaluateHealth(
                deregistered.getId(), currentRuntimeTime(deregistered.getId()));
        assertThat(deregisteredHealth.health()).isEqualTo(RunnerHealth.ONLINE);
        assertThat(deregisteredHealth.lifecycleStatus())
                .isEqualTo(RunnerStatus.DEREGISTERED);
    }

    private void assertHealth(
            Runner runner,
            OffsetDateTime lastSeenAt,
            OffsetDateTime evaluatedAt,
            RunnerHealth expected) {
        jdbcTemplate.update(
                "UPDATE runner_runtime SET last_seen_at = ? WHERE runner_id = ?",
                lastSeenAt,
                runner.getId());
        var before = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();

        var result = heartbeatService.evaluateHealth(runner.getId(), evaluatedAt);

        var after = runtimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        assertThat(result.health()).isEqualTo(expected);
        assertThat(result.lifecycleStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(result.lastSeenAt()).isEqualTo(lastSeenAt);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getHeartbeatCount()).isEqualTo(before.getHeartbeatCount());
    }

    private Runner register(String suffix) {
        return registrationService.register(new RegisterRunnerCommand(
                key(suffix),
                "Runner " + suffix,
                "AS-020E integration runner",
                "1.0.0",
                suffix + ".internal",
                "linux",
                "amd64",
                4,
                Map.of(),
                Map.of()));
    }

    private RecordRunnerHeartbeatCommand command(String suffix) {
        return new RecordRunnerHeartbeatCommand(key(suffix));
    }

    private String key(String suffix) {
        return KEY_PREFIX + suffix;
    }

    private void setStatus(UUID runnerId, RunnerStatus status) {
        transactionTemplate.executeWithoutResult(transaction -> {
            Runner locked = runnerRepository.findByIdForUpdate(runnerId).orElseThrow();
            locked.updateStatus(status);
            runnerRepository.saveAndFlush(locked);
        });
    }

    private OffsetDateTime currentRuntimeTime(UUID runnerId) {
        return runtimeRepository.findByRunnerId(runnerId).orElseThrow().getLastSeenAt();
    }
}
