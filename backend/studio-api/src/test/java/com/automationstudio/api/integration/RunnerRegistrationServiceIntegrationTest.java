package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.RunnerAlreadyDeregisteredException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerRegistrationService;
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

class RunnerRegistrationServiceIntegrationTest extends IntegrationTestBase {

    private static final String KEY_PREFIX = "as020d-";

    @Autowired
    private RunnerRegistrationService service;

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
    void newRegistrationCreatesRunnerAndRuntimeAtomically() {
        Runner registered = service.register(command("new", "New runner"));

        Runner persisted = runnerRepository.findById(registered.getId()).orElseThrow();
        var runtime = runtimeRepository.findByRunnerId(registered.getId()).orElseThrow();
        assertThat(persisted.getRunnerKey()).isEqualTo(key("new"));
        assertThat(persisted.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(persisted.getRegisteredAt()).isEqualTo(persisted.getLastRegisteredAt());
        assertThat(runtime.getLastSeenAt()).isEqualTo(persisted.getRegisteredAt());
        assertThat(runtime.getHeartbeatCount()).isZero();
    }

    @Test
    void activeReregistrationUpdatesMetadataAndPreservesIdentityAndRuntime() {
        Runner original = service.register(command("active", "Original"));
        UUID id = original.getId();
        OffsetDateTime registeredAt = original.getRegisteredAt();
        long originalRunnerVersion = original.getVersion();
        jdbcTemplate.update("""
                UPDATE runner_runtime
                SET heartbeat_count = 7, version = 4
                WHERE runner_id = ?
                """, id);

        Runner updated = service.register(command("active", "Updated"));

        var runtime = runtimeRepository.findByRunnerId(id).orElseThrow();
        assertThat(updated.getId()).isEqualTo(id);
        assertThat(updated.getRunnerKey()).isEqualTo(key("active"));
        assertThat(updated.getRegisteredAt()).isEqualTo(registeredAt);
        assertThat(updated.getName()).isEqualTo("Updated");
        assertThat(updated.getVersion()).isGreaterThan(originalRunnerVersion);
        assertThat(runtime.getRunnerId()).isEqualTo(id);
        assertThat(runtime.getHeartbeatCount()).isEqualTo(7);
        assertThat(runtime.getVersion()).isEqualTo(4);
        assertThat(runtimeRepository.count()).isEqualTo(1);
    }

    @Test
    void disabledReregistrationUpdatesMetadataWithoutReenablingRunner() {
        Runner original = service.register(command("disabled", "Original"));
        transactionTemplate.executeWithoutResult(status -> {
            Runner locked = runnerRepository.findByIdForUpdate(original.getId()).orElseThrow();
            locked.updateStatus(RunnerStatus.DISABLED);
            runnerRepository.saveAndFlush(locked);
        });

        Runner updated = service.register(command("disabled", "Updated disabled"));

        assertThat(updated.getStatus()).isEqualTo(RunnerStatus.DISABLED);
        assertThat(updated.getName()).isEqualTo("Updated disabled");
        assertThat(runtimeRepository.count()).isEqualTo(1);
    }

    @Test
    void deregisteredReregistrationIsRejectedWithoutMutation() {
        Runner original = service.register(command("deregistered", "Original"));
        transactionTemplate.executeWithoutResult(status -> {
            Runner locked = runnerRepository.findByIdForUpdate(original.getId()).orElseThrow();
            locked.updateStatus(RunnerStatus.DEREGISTERED);
            runnerRepository.saveAndFlush(locked);
        });
        Runner before = runnerRepository.findById(original.getId()).orElseThrow();

        assertThatExceptionOfType(RunnerAlreadyDeregisteredException.class)
                .isThrownBy(() -> service.register(
                        command("deregistered", "Must not persist")));

        Runner after = runnerRepository.findById(original.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo(before.getName());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(runtimeRepository.count()).isEqualTo(1);
    }

    @Test
    void repeatedRegistrationDoesNotCreateDuplicateRows() {
        Runner first = service.register(command("repeat", "First"));
        Runner second = service.register(command("repeat", "Second"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(runnerRepository.count()).isEqualTo(1);
        assertThat(runtimeRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentFirstRegistrationsCreateExactlyOneRunnerAndRuntime() throws Exception {
        int participants = 6;
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Runner>> tasks = new ArrayList<>();
        for (int index = 0; index < participants; index++) {
            String name = "Concurrent " + index;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return service.register(command("concurrent", name));
            });
        }

        try (var executor = Executors.newFixedThreadPool(participants)) {
            List<Future<Runner>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();
            ready.await();
            start.countDown();

            List<UUID> ids = new ArrayList<>();
            for (Future<Runner> future : futures) {
                ids.add(future.get().getId());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        }

        assertThat(runnerRepository.count()).isEqualTo(1);
        assertThat(runtimeRepository.count()).isEqualTo(1);
    }

    @Test
    void runtimePersistenceFailureRollsBackRunnerCreation() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION reject_as020d_runtime()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF EXISTS (
                        SELECT 1 FROM runner
                        WHERE id = NEW.runner_id
                          AND runner_key = 'as020d-rollback'
                    ) THEN
                        RAISE EXCEPTION 'synthetic runtime failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_as020d_runtime_trigger
                BEFORE INSERT ON runner_runtime
                FOR EACH ROW EXECUTE FUNCTION reject_as020d_runtime()
                """);
        try {
            assertThatExceptionOfType(ResourceConflictException.class)
                    .isThrownBy(() -> service.register(command("rollback", "Rollback")));
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER reject_as020d_runtime_trigger ON runner_runtime");
            jdbcTemplate.execute("DROP FUNCTION reject_as020d_runtime()");
        }

        assertThat(runnerRepository.findByRunnerKey(key("rollback"))).isEmpty();
        assertThat(runtimeRepository.count()).isZero();
    }

    private RegisterRunnerCommand command(String suffix, String name) {
        return new RegisterRunnerCommand(
                key(suffix),
                name,
                "AS-020D integration runner",
                "1.0.0",
                suffix + ".internal",
                "linux",
                "amd64",
                4,
                Map.of("features", List.of("docker")),
                Map.of("pool", "build"));
    }

    private String key(String suffix) {
        return KEY_PREFIX + suffix;
    }
}
