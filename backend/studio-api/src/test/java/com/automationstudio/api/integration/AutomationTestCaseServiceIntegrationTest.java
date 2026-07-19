package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.AutomationSuiteService;
import com.automationstudio.api.service.AutomationTestCaseService;
import java.util.List;
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

class AutomationTestCaseServiceIntegrationTest extends IntegrationTestBase {

    private static final String WORKSPACE_SLUG_PREFIX = "as-016d-service-test-";

    @Autowired
    private AutomationTestCaseService testCaseService;

    @Autowired
    private AutomationSuiteService automationSuiteService;

    @Autowired
    private AutomationTestCaseRepository testCaseRepository;

    @Autowired
    private AutomationSuiteRepository automationSuiteRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanDatabase() {
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
                DELETE FROM execution
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
                DELETE FROM test_suite
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
    void concurrentCreatesSerializeAndReceiveDistinctAppendPositions() throws Exception {
        AutomationSuite suite = createSuite("Concurrent create suite");
        RaceOutcomes outcomes = runWithProvenSuiteLockContention(
                suite,
                () -> testCaseService.create(suite.getProject().getId(), suite.getId(),
                        newCase("First", "first")),
                () -> testCaseService.create(suite.getProject().getId(), suite.getId(),
                        newCase("Second", "second")));

        assertThat(outcomes.firstFailure()).isNull();
        assertThat(outcomes.secondFailure()).isNull();
        assertThat(testCaseRepository
                .findByAutomationSuiteIdOrderByPositionAscIdAsc(suite.getId()))
                .extracting(AutomationTestCase::getPosition).containsExactly(0, 1);
    }

    @Test
    void reverseReorderCommitsAndInvalidMembershipRollsBackCompletely() {
        AutomationSuite suite = createSuite("Reorder suite");
        UUID firstId = testCaseService.create(
                suite.getProject().getId(), suite.getId(), newCase("First", "first")).getId();
        UUID secondId = testCaseService.create(
                suite.getProject().getId(), suite.getId(), newCase("Second", "second")).getId();
        UUID thirdId = testCaseService.create(
                suite.getProject().getId(), suite.getId(), newCase("Third", "third")).getId();

        List<AutomationTestCase> reordered = testCaseService.reorder(
                suite.getProject().getId(), suite.getId(), List.of(thirdId, secondId, firstId));

        assertThat(reordered).extracting(AutomationTestCase::getId)
                .containsExactly(thirdId, secondId, firstId);
        assertThat(reordered).extracting(AutomationTestCase::getPosition)
                .containsExactly(0, 1, 2);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> testCaseService.reorder(
                        suite.getProject().getId(), suite.getId(), List.of(firstId, secondId)));
        assertThat(testCaseRepository
                .findByAutomationSuiteIdOrderByPositionAscIdAsc(suite.getId()))
                .extracting(AutomationTestCase::getId)
                .containsExactly(thirdId, secondId, firstId);
    }

    @Test
    void caseCreationAndProtectedSuiteUpdateSerializeToASafeInvariant() throws Exception {
        AutomationSuite suite = createSuite("Update race suite");
        AutomationSuite updates = suiteUpdate(suite, "SELENIUM", suite.getSuiteReference());
        RaceOutcomes outcomes = runWithProvenSuiteLockContention(
                suite,
                () -> testCaseService.create(suite.getProject().getId(), suite.getId(),
                        newCase("Racing case", "racing-case")),
                () -> automationSuiteService.update(
                        suite.getProject().getId(), suite.getId(), updates));

        assertThat(outcomes.firstFailure()).isNull();
        assertThat(outcomes.secondFailure()).isInstanceOf(ResourceConflictException.class);
        assertThat(testCaseRepository.existsByAutomationSuiteId(suite.getId())).isTrue();
        assertThat(automationSuiteRepository.findById(suite.getId()).orElseThrow()
                .getEngineType()).isEqualTo("PLAYWRIGHT");
    }

    @Test
    void caseCreationAndSuiteDeletionSerializeWithoutOrphaning() throws Exception {
        AutomationSuite suite = createSuite("Delete race suite");
        UUID projectId = suite.getProject().getId();
        UUID suiteId = suite.getId();
        RaceOutcomes outcomes = runWithProvenSuiteLockContention(
                suite,
                () -> testCaseService.create(
                        projectId, suiteId, newCase("Racing case", "racing-case")),
                () -> automationSuiteService.delete(projectId, suiteId));

        assertThat(outcomes.firstFailure()).isNull();
        assertThat(outcomes.secondFailure()).isInstanceOf(ResourceConflictException.class);
        assertThat(automationSuiteRepository.existsById(suiteId)).isTrue();
        assertThat(testCaseRepository.existsByAutomationSuiteId(suiteId)).isTrue();
    }

    @Test
    void physicalCaseDeletionLeavesOwningSuiteIntact() {
        AutomationSuite suite = createSuite("Physical deletion suite");
        UUID caseId = testCaseService.create(
                suite.getProject().getId(), suite.getId(), newCase("Deleted", "deleted")).getId();

        testCaseService.delete(suite.getProject().getId(), suite.getId(), caseId);

        assertThat(testCaseRepository.findById(caseId)).isEmpty();
        assertThat(automationSuiteRepository.findById(suite.getId())).isPresent();
    }

    private AutomationSuite createSuite(String name) {
        Project project = projectRepository.findById(insertProject()).orElseThrow();
        AutomationSuite suite = new AutomationSuite();
        suite.setProject(project);
        suite.setName(name);
        suite.setEngineType("PLAYWRIGHT");
        suite.setSuiteReference("tests/" + UUID.randomUUID());
        return automationSuiteRepository.saveAndFlush(suite);
    }

    private AutomationSuite suiteUpdate(
            AutomationSuite existing, String engineType, String suiteReference) {
        AutomationSuite updates = new AutomationSuite();
        updates.setName(existing.getName());
        updates.setDescription(existing.getDescription());
        updates.setEngineId(existing.getEngineId());
        updates.setEngineType(engineType);
        updates.setSuiteReference(suiteReference);
        updates.setSuiteType(existing.getSuiteType());
        updates.setConfiguration(existing.getConfiguration());
        return updates;
    }

    private AutomationTestCase newCase(String name, String reference) {
        AutomationTestCase testCase = new AutomationTestCase();
        testCase.setName(name);
        testCase.setCaseReference(reference);
        return testCase;
    }

    private UUID insertProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "Service Test Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "Service Test Project " + suffix);
        return projectId;
    }

    private Throwable capture(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private RaceOutcomes runWithProvenSuiteLockContention(
            AutomationSuite suite,
            ThrowingRunnable firstOperation,
            ThrowingRunnable competingOperation) throws Exception {
        CountDownLatch suiteLockHeld = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CompletableFuture<Integer> holderBackendPid = new CompletableFuture<>();
        CompletableFuture<Integer> competitorBackendPid = new CompletableFuture<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> capture(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        holderBackendPid.complete(currentBackendPid());
                        automationSuiteRepository.findByProjectIdAndIdForUpdate(
                                suite.getProject().getId(), suite.getId()).orElseThrow();
                        suiteLockHeld.countDown();
                        awaitUnchecked(releaseFirstTransaction);
                        try {
                            firstOperation.run();
                        } catch (Exception exception) {
                            throw new TestOperationException(exception);
                        }
                    })));
            assertThat(suiteLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> second = executor.submit(() -> capture(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        competitorBackendPid.complete(currentBackendPid());
                        try {
                            competingOperation.run();
                        } catch (Exception exception) {
                            throw new TestOperationException(exception);
                        }
                    })));
            int holderPid = holderBackendPid.get(10, TimeUnit.SECONDS);
            int competitorPid = competitorBackendPid.get(10, TimeUnit.SECONDS);
            assertThat(holderPid).isPositive();
            assertThat(competitorPid).isPositive().isNotEqualTo(holderPid);
            try {
                assertThat(waitForPostgreSqlLockWait(competitorPid)).isTrue();
            } finally {
                releaseFirstTransaction.countDown();
            }
            RaceOutcomes outcomes = new RaceOutcomes(
                    unwrap(first.get(15, TimeUnit.SECONDS)),
                    unwrap(second.get(15, TimeUnit.SECONDS)));
            assertThat(waitForPostgreSqlLockWaitToEnd(competitorPid)).isTrue();
            return outcomes;
        } finally {
            releaseFirstTransaction.countDown();
            shutdown(executor);
        }
    }

    private int currentBackendPid() {
        Integer pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
        if (pid == null) {
            throw new IllegalStateException("PostgreSQL did not return a backend PID");
        }
        return pid;
    }

    private boolean waitForPostgreSqlLockWait(int competitorPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE pid = ?
                      AND datname = current_database()
                      AND state = 'active'
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%test_suite%'
                    """, Integer.class, competitorPid);
            if (waiting != null && waiting > 0) {
                return true;
            }
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    private boolean waitForPostgreSqlLockWaitToEnd(int competitorPid)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE pid = ?
                      AND wait_event_type = 'Lock'
                    """, Integer.class, competitorPid);
            if (waiting != null && waiting == 0) {
                return true;
            }
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    private void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release suite lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding suite lock", exception);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof TestOperationException wrapper && wrapper.getCause() != null) {
            return wrapper.getCause();
        }
        return throwable;
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record RaceOutcomes(Throwable firstFailure, Throwable secondFailure) {
    }

    private static final class TestOperationException extends RuntimeException {
        private TestOperationException(Throwable cause) {
            super(cause);
        }
    }
}
