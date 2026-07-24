package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.EnvironmentService;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EnvironmentServiceIntegrationTest extends IntegrationTestBase {

    private static final String WORKSPACE_SLUG_PREFIX = "as-017d-environment-service-";

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM automation_test_case
                WHERE test_suite_id IN (
                    SELECT test_suite.id FROM test_suite
                    JOIN project ON project.id = test_suite.project_id
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_SLUG_PREFIX + "%");
    }

    @Test
    void createAndSetDefaultReplaceThePreviousDefaultAtomically() {
        UUID projectId = insertProject();
        Environment first = environmentService.create(
                projectId, createCommand("First", true));
        Environment second = environmentService.create(
                projectId, createCommand("Second", true));

        assertThat(environmentService.getDefault(projectId))
                .map(Environment::getId).contains(second.getId());
        assertThat(reload(first).isDefault()).isFalse();
        assertThat(reload(first).getVersion()).isEqualTo(1);

        Environment third = environmentService.create(
                projectId, createCommand("Third", false));
        Environment selected = environmentService.changeDefault(
                projectId, third.getId(), third.getVersion(), true);

        assertThat(selected.isDefault()).isTrue();
        assertThat(environmentService.getDefault(projectId))
                .map(Environment::getId).contains(third.getId());
        assertThat(reload(second).isDefault()).isFalse();
        assertThat(reload(second).getVersion()).isEqualTo(1);
    }

    @Test
    void successfulUpdateIncrementsVersionAndStaleExpectedVersionConflicts() {
        UUID projectId = insertProject();
        Environment environment = environmentService.create(
                projectId, createCommand("Versioned", false));

        Environment updated = environmentService.update(
                projectId, environment.getId(), 0, new UpdateEnvironmentCommand(
                        "Updated",
                        "updated",
                        "https://updated.example.test",
                        EnvironmentType.STAGING,
                        Map.of("browser", "chromium"),
                        Map.of("token", "vault://synthetic/token")));

        assertThat(updated.getVersion()).isEqualTo(1);
        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> environmentService.update(
                        projectId, environment.getId(), 0, new UpdateEnvironmentCommand(
                                "Stale", null, "https://example.test", EnvironmentType.TEST,
                                Map.of(), Map.of())));
    }

    @Test
    void statusTransitionClearsDefaultAndReactivationDoesNotRestoreIt() {
        UUID projectId = insertProject();
        Environment environment = environmentService.create(
                projectId, createCommand("Lifecycle", true));

        Environment archived = environmentService.changeStatus(
                projectId, environment.getId(), 0, EnvironmentStatus.ARCHIVED);
        assertThat(archived.isDefault()).isFalse();
        assertThat(archived.getVersion()).isEqualTo(1);
        assertThat(environmentService.getDefault(projectId)).isEmpty();

        Environment active = environmentService.changeStatus(
                projectId, environment.getId(), 1, EnvironmentStatus.ACTIVE);
        assertThat(active.isDefault()).isFalse();
        assertThat(active.getVersion()).isEqualTo(2);
        assertThat(environmentService.getDefault(projectId)).isEmpty();
    }

    @Test
    void deleteDefaultLeavesNoDefaultAndReferencedDeleteIsRejected() {
        UUID projectId = insertProject();
        Environment defaultEnvironment = environmentService.create(
                projectId, createCommand("Deleted default", true));
        environmentService.delete(projectId, defaultEnvironment.getId(), 0);
        assertThat(environmentService.getDefault(projectId)).isEmpty();
        assertThat(environmentRepository.findById(defaultEnvironment.getId())).isEmpty();

        Environment referenced = environmentService.create(
                projectId, createCommand("Referenced", false));
        insertExecution(projectId, referenced.getId());
        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> environmentService.delete(
                        projectId, referenced.getId(), referenced.getVersion()));
        assertThat(environmentRepository.findById(referenced.getId())).isPresent();
    }

    @Test
    void crossProjectOperationsRemainNotFound() {
        UUID ownerProjectId = insertProject();
        UUID otherProjectId = insertProject();
        Environment environment = environmentService.create(
                ownerProjectId, createCommand("Owned", false));

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> environmentService.get(
                        otherProjectId, environment.getId()));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> environmentService.changeDefault(
                        otherProjectId, environment.getId(), 0, true));
    }

    @Test
    void concurrentDefaultRequestsSerializeOnProjectAndLeaveExactlyOneDefault()
            throws Exception {
        UUID projectId = insertProject();
        Environment first = environmentService.create(
                projectId, createCommand("Concurrent first", false));
        Environment second = environmentService.create(
                projectId, createCommand("Concurrent second", false));
        RaceOutcomes outcomes = runWithProvenProjectLockContention(
                projectId,
                () -> environmentService.changeDefault(projectId, first.getId(), 0, true),
                () -> environmentService.changeDefault(projectId, second.getId(), 0, true));

        assertThat(outcomes.firstFailure()).isNull();
        assertThat(outcomes.secondFailure()).isNull();
        assertThat(environmentRepository.findByProjectIdAndIsDefaultTrue(projectId)).isPresent();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM environment
                WHERE project_id = ? AND is_default
                """, Integer.class, projectId)).isEqualTo(1);
    }

    private Environment reload(Environment environment) {
        return environmentRepository.findById(environment.getId()).orElseThrow();
    }

    private CreateEnvironmentCommand createCommand(String name, boolean isDefault) {
        return new CreateEnvironmentCommand(
                name,
                null,
                "https://example.test",
                EnvironmentType.TEST,
                Map.of(),
                Map.of("token", "vault://synthetic/token"),
                EnvironmentStatus.ACTIVE,
                isDefault);
    }

    private UUID insertProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "Environment Service Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "Environment Service Project " + suffix);
        return projectId;
    }

    private void insertExecution(UUID projectId, UUID environmentId) {
        UUID suiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "Environment Service Suite " + suiteId,
                "tests/" + suiteId);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING', 'as-017d-service-test')
                """, UUID.randomUUID(), projectId, environmentId, suiteId);
    }

    private RaceOutcomes runWithProvenProjectLockContention(
            UUID projectId,
            ThrowingRunnable firstOperation,
            ThrowingRunnable competingOperation) throws Exception {
        CountDownLatch projectLockHeld = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CompletableFuture<Integer> holderBackendPid = new CompletableFuture<>();
        CompletableFuture<Integer> competitorBackendPid = new CompletableFuture<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> capture(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        holderBackendPid.complete(currentBackendPid());
                        projectRepository.findByIdForUpdate(projectId).orElseThrow();
                        projectLockHeld.countDown();
                        awaitUnchecked(releaseFirstTransaction);
                        firstOperation.run();
                    })));
            assertThat(projectLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> second = executor.submit(() -> capture(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        competitorBackendPid.complete(currentBackendPid());
                        competingOperation.run();
                    })));
            int holderPid = holderBackendPid.get(10, TimeUnit.SECONDS);
            int competitorPid = competitorBackendPid.get(10, TimeUnit.SECONDS);
            assertThat(holderPid).isPositive();
            assertThat(competitorPid).isPositive().isNotEqualTo(holderPid);
            try {
                assertThat(waitForProjectLock(competitorPid)).isTrue();
            } finally {
                releaseFirstTransaction.countDown();
            }
            return new RaceOutcomes(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS));
        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable capture(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private int currentBackendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private boolean waitForProjectLock(int competitorPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE pid = ?
                      AND datname = current_database()
                      AND state = 'active'
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%project%'
                    """, Integer.class, competitorPid);
            if (waiting != null && waiting > 0) {
                return true;
            }
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    private void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while holding Project lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding Project lock", exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private record RaceOutcomes(Throwable firstFailure, Throwable secondFailure) {
    }
}
