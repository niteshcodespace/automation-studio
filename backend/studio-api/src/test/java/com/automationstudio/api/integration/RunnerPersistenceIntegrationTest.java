package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.RollbackException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RunnerPersistenceIntegrationTest extends IntegrationTestBase {

    private static final String KEY_PREFIX = "as020c-";
    private static final OffsetDateTime REGISTERED_AT =
            OffsetDateTime.parse("2026-07-26T12:00:00Z");

    @Autowired
    private RunnerRepository runnerRepository;

    @Autowired
    private RunnerRuntimeRepository runnerRuntimeRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
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
    void runnerRoundTripsScalarsEnumTimestampsAndNestedJsonWithDefensiveCopies() {
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("playwright-java", "1.52.0");
        List<Object> features = new ArrayList<>(List.of("docker", "headless"));
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("engines", engines);
        capabilities.put("features", features);
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("region", "test");

        Runner runner = newRunner("mapping", RunnerStatus.ACTIVE, capabilities, labels);
        UUID id = runnerRepository.saveAndFlush(runner).getId();
        engines.put("playwright-java", "changed-after-set");
        features.add("changed-after-set");
        labels.put("region", "changed-after-set");
        clearPersistenceContext();

        Runner loaded = runnerRepository.findById(id).orElseThrow();

        assertThat(id).isNotNull();
        assertThat(loaded.getRunnerKey()).isEqualTo(KEY_PREFIX + "mapping");
        assertThat(loaded.getName()).isEqualTo("Runner mapping");
        assertThat(loaded.getDescription()).isEqualTo("Persistence runner");
        assertThat(loaded.getAgentVersion()).isEqualTo("1.0.0");
        assertThat(loaded.getHostname()).isEqualTo("mapping.example.test");
        assertThat(loaded.getOperatingSystem()).isEqualTo("linux");
        assertThat(loaded.getArchitecture()).isEqualTo("amd64");
        assertThat(loaded.getMaxConcurrency()).isEqualTo(4);
        assertThat(loaded.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(loaded.getRegisteredAt()).isEqualTo(REGISTERED_AT);
        assertThat(loaded.getLastRegisteredAt()).isEqualTo(REGISTERED_AT);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCapabilities()).containsKey("engines");
        assertThat(nestedMap(loaded.getCapabilities(), "engines"))
                .containsEntry("playwright-java", "1.52.0");
        assertThat(nestedList(loaded.getCapabilities(), "features"))
                .containsExactly("docker", "headless");
        assertThat(loaded.getLabels()).containsEntry("region", "test");

        Map<String, Object> returned = loaded.getCapabilities();
        nestedMap(returned, "engines").put("playwright-java", "changed-after-get");
        nestedList(returned, "features").add("changed-after-get");
        assertThat(nestedMap(loaded.getCapabilities(), "engines"))
                .containsEntry("playwright-java", "1.52.0");
        assertThat(nestedList(loaded.getCapabilities(), "features"))
                .containsExactly("docker", "headless");
    }

    @Test
    void emptyJsonObjectsRoundTrip() {
        Runner runner = runnerRepository.saveAndFlush(
                newRunner("empty-json", RunnerStatus.ACTIVE, Map.of(), Map.of()));
        clearPersistenceContext();

        Runner loaded = runnerRepository.findById(runner.getId()).orElseThrow();

        assertThat(loaded.getCapabilities()).isEmpty();
        assertThat(loaded.getLabels()).isEmpty();
    }

    @Test
    void repositoryQueriesSupportKeyExistenceStatusAndPessimisticLocks() {
        Runner active = runnerRepository.saveAndFlush(
                newRunner("active", RunnerStatus.ACTIVE, Map.of(), Map.of()));
        Runner disabled = runnerRepository.saveAndFlush(
                newRunner("disabled", RunnerStatus.DISABLED, Map.of(), Map.of()));
        RunnerRuntime runtime = runnerRuntimeRepository.saveAndFlush(
                new RunnerRuntime(active.getId(), REGISTERED_AT));

        assertThat(runnerRepository.findByRunnerKey(active.getRunnerKey()))
                .map(Runner::getId).contains(active.getId());
        assertThat(runnerRepository.existsByRunnerKey(active.getRunnerKey())).isTrue();
        assertThat(runnerRepository.existsByRunnerKey(KEY_PREFIX + "missing")).isFalse();
        assertThat(runnerRepository.findByStatus(
                RunnerStatus.DISABLED,
                PageRequest.of(0, 10, Sort.by("name"))).getContent())
                .extracting(Runner::getId).containsExactly(disabled.getId());
        assertThat(runnerRuntimeRepository.findByRunnerId(active.getId()))
                .map(RunnerRuntime::getRunnerId).contains(runtime.getRunnerId());
        assertThat(runnerRuntimeRepository.existsByRunnerId(active.getId())).isTrue();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Runner lockedByKey = runnerRepository
                    .findByRunnerKeyForUpdate(active.getRunnerKey()).orElseThrow();
            Runner lockedById =
                    runnerRepository.findByIdForUpdate(active.getId()).orElseThrow();
            RunnerRuntime lockedRuntime = runnerRuntimeRepository
                    .findByRunnerIdForUpdate(active.getId()).orElseThrow();

            assertThat(lockedByKey.getId()).isEqualTo(active.getId());
            assertThat(lockedById.getRunnerKey()).isEqualTo(active.getRunnerKey());
            assertThat(lockedRuntime.getRunnerId()).isEqualTo(active.getId());
            assertThat(entityManager.getLockMode(lockedByKey))
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
            assertThat(entityManager.getLockMode(lockedById))
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
            assertThat(entityManager.getLockMode(lockedRuntime))
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        });
    }

    @Test
    void metadataUpdatePreservesIdentityAndRegistrationTimeAndIncrementsManagementVersion() {
        Map<String, Object> originalCapabilities = Map.of("features", List.of("docker"));
        Runner runner = runnerRepository.saveAndFlush(
                newRunner("metadata", RunnerStatus.ACTIVE, originalCapabilities, Map.of()));
        UUID id = runner.getId();
        OffsetDateTime registeredAt = runner.getRegisteredAt();
        OffsetDateTime reRegisteredAt = REGISTERED_AT.plusMinutes(10);

        runner.updateMetadata(
                "Updated runner",
                "Updated description",
                "1.1.0",
                "updated.example.test",
                "windows",
                "arm64",
                8,
                Map.of("engines", Map.of("selenium-java", "4.28.0")),
                Map.of("region", "secondary"),
                reRegisteredAt);
        runnerRepository.saveAndFlush(runner);
        clearPersistenceContext();

        Runner loaded = runnerRepository.findById(id).orElseThrow();
        assertThat(loaded.getRunnerKey()).isEqualTo(KEY_PREFIX + "metadata");
        assertThat(loaded.getRegisteredAt()).isEqualTo(registeredAt);
        assertThat(loaded.getLastRegisteredAt()).isEqualTo(reRegisteredAt);
        assertThat(loaded.getName()).isEqualTo("Updated runner");
        assertThat(loaded.getAgentVersion()).isEqualTo("1.1.0");
        assertThat(loaded.getMaxConcurrency()).isEqualTo(8);
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void duplicateAndDeregisteredRunnerKeysRemainRejected() {
        Runner retired = newRunner(
                "reserved", RunnerStatus.DEREGISTERED, Map.of(), Map.of());
        runnerRepository.saveAndFlush(retired);

        Runner duplicate = newRunner(
                "reserved", RunnerStatus.ACTIVE, Map.of(), Map.of());

        assertThatThrownBy(() -> runnerRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runtimePersistsIndependentlyAndDoesNotIncrementRunnerVersion() {
        Runner runner = runnerRepository.saveAndFlush(
                newRunner("runtime", RunnerStatus.ACTIVE, Map.of(), Map.of()));
        long runnerVersion = runner.getVersion();
        RunnerRuntime runtime = runnerRuntimeRepository.saveAndFlush(
                new RunnerRuntime(runner.getId(), REGISTERED_AT));
        assertThat(runtime.getVersion()).isZero();

        runtime.updateRuntimeState(REGISTERED_AT.plusMinutes(1), 1);
        runnerRuntimeRepository.saveAndFlush(runtime);
        clearPersistenceContext();

        RunnerRuntime loadedRuntime =
                runnerRuntimeRepository.findByRunnerId(runner.getId()).orElseThrow();
        Runner loadedRunner = runnerRepository.findById(runner.getId()).orElseThrow();
        assertThat(loadedRuntime.getLastSeenAt()).isEqualTo(REGISTERED_AT.plusMinutes(1));
        assertThat(loadedRuntime.getHeartbeatCount()).isEqualTo(1);
        assertThat(loadedRuntime.getVersion()).isEqualTo(1);
        assertThat(loadedRunner.getVersion()).isEqualTo(runnerVersion);
    }

    @Test
    void runtimePrimaryKeyForeignKeyAndRestrictiveDeleteAreEnforced() {
        Runner runner = runnerRepository.saveAndFlush(
                newRunner("constraints", RunnerStatus.ACTIVE, Map.of(), Map.of()));
        runnerRuntimeRepository.saveAndFlush(new RunnerRuntime(runner.getId(), REGISTERED_AT));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO runner_runtime (
                    runner_id, last_seen_at, heartbeat_count, version, created_at, updated_at
                ) VALUES (?, ?, 0, 0, ?, ?)
                """, runner.getId(), REGISTERED_AT.plusMinutes(1),
                REGISTERED_AT, REGISTERED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> runnerRuntimeRepository.saveAndFlush(
                new RunnerRuntime(UUID.randomUUID(), REGISTERED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM runner WHERE id = ?", runner.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleRunnerAndRuntimeUpdatesAreRejectedIndependently() {
        Runner runner = runnerRepository.saveAndFlush(
                newRunner("optimistic", RunnerStatus.ACTIVE, Map.of(), Map.of()));
        runnerRuntimeRepository.saveAndFlush(new RunnerRuntime(runner.getId(), REGISTERED_AT));

        assertStaleRunnerUpdateRejected(runner.getId());
        assertStaleRuntimeUpdateRejected(runner.getId());
    }

    private void assertStaleRunnerUpdateRejected(UUID runnerId) {
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            Runner firstCopy = first.find(Runner.class, runnerId);
            Runner staleCopy = second.find(Runner.class, runnerId);

            first.getTransaction().begin();
            firstCopy.updateStatus(RunnerStatus.DISABLED);
            first.getTransaction().commit();

            second.getTransaction().begin();
            staleCopy.updateStatus(RunnerStatus.DEREGISTERED);
            assertThatThrownBy(() -> second.getTransaction().commit())
                    .isInstanceOfAny(OptimisticLockException.class, RollbackException.class);
        } finally {
            rollbackIfActive(second);
            first.close();
            second.close();
        }
    }

    private void assertStaleRuntimeUpdateRejected(UUID runnerId) {
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            RunnerRuntime firstCopy = first.find(RunnerRuntime.class, runnerId);
            RunnerRuntime staleCopy = second.find(RunnerRuntime.class, runnerId);

            first.getTransaction().begin();
            firstCopy.updateRuntimeState(REGISTERED_AT.plusMinutes(1), 1);
            first.getTransaction().commit();

            second.getTransaction().begin();
            staleCopy.updateRuntimeState(REGISTERED_AT.plusMinutes(2), 1);
            assertThatThrownBy(() -> second.getTransaction().commit())
                    .isInstanceOfAny(OptimisticLockException.class, RollbackException.class);
        } finally {
            rollbackIfActive(second);
            first.close();
            second.close();
        }
    }

    private Runner newRunner(
            String suffix,
            RunnerStatus status,
            Map<String, Object> capabilities,
            Map<String, Object> labels) {
        return new Runner(
                KEY_PREFIX + suffix,
                "Runner " + suffix,
                "Persistence runner",
                "1.0.0",
                suffix + ".example.test",
                "linux",
                "amd64",
                4,
                capabilities,
                labels,
                status,
                REGISTERED_AT);
    }

    private void clearPersistenceContext() {
        entityManagerFactory.getCache().evictAll();
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.clear();
        entityManager.close();
    }

    private void rollbackIfActive(EntityManager entityManager) {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private List<Object> nestedList(Map<String, Object> source, String key) {
        return (List<Object>) source.get(key);
    }
}
