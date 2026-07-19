package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AutomationTestCaseIntegrationTest extends IntegrationTestBase {

    private static final String WORKSPACE_SLUG_PREFIX = "as-016c-repository-test-";

    @Autowired
    private AutomationTestCaseRepository testCaseRepository;

    @Autowired
    private AutomationSuiteRepository automationSuiteRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void persistsReferenceOnlyCaseWithJsonConfigurationAndAuditFields() {
        AutomationSuite suite = createSuite("Persistence suite");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("browser", "chromium");
        input.put("retries", 2);
        AutomationTestCase testCase = newCase(suite, "Checkout", "checkout succeeds", 0);
        testCase.setDescription("Engine-native checkout case");
        testCase.setConfiguration(input);
        input.put("browser", "mutated");

        UUID id = testCaseRepository.saveAndFlush(testCase).getId();
        AutomationTestCase loaded = testCaseRepository.findById(id).orElseThrow();

        assertThat(loaded.getAutomationSuite().getId()).isEqualTo(suite.getId());
        assertThat(loaded.getName()).isEqualTo("Checkout");
        assertThat(loaded.getDescription()).isEqualTo("Engine-native checkout case");
        assertThat(loaded.getCaseReference()).isEqualTo("checkout succeeds");
        assertThat(loaded.getConfiguration()).containsEntry("browser", "chromium")
                .containsEntry("retries", 2);
        assertThat(loaded.getStatus()).isEqualTo(AutomationTestCaseStatus.ACTIVE);
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT test_suite_id FROM automation_test_case WHERE id = ?
                """, UUID.class, id)).isEqualTo(suite.getId());
    }

    @Test
    void configurationGetterIsDefensiveAndNullConfigurationRoundTrips() {
        AutomationSuite suite = createSuite("Configuration suite");
        AutomationTestCase configured = newCase(suite, "Configured", "configured", 0);
        configured.setConfiguration(Map.of("region", "eu"));
        UUID configuredId = testCaseRepository.saveAndFlush(configured).getId();
        AutomationTestCase nullable = newCase(suite, "Nullable", "nullable", 1);
        UUID nullableId = testCaseRepository.saveAndFlush(nullable).getId();

        AutomationTestCase loaded = testCaseRepository.findById(configuredId).orElseThrow();
        Map<String, Object> returned = loaded.getConfiguration();
        returned.put("region", "mutated");

        assertThat(loaded.getConfiguration()).containsEntry("region", "eu");
        testCaseRepository.flush();
        EntityManager freshEntityManager = entityManagerFactory.createEntityManager();
        try {
            AutomationTestCase reloaded = freshEntityManager.find(
                    AutomationTestCase.class, configuredId);
            assertThat(reloaded.getConfiguration())
                    .containsEntry("region", "eu")
                    .doesNotContainEntry("region", "mutated");
        } finally {
            freshEntityManager.close();
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT configuration ->> 'region'
                FROM automation_test_case
                WHERE id = ?
                """, String.class, configuredId)).isEqualTo("eu");
        assertThat(testCaseRepository.findById(nullableId).orElseThrow().getConfiguration())
                .isNull();
        assertThat(testCaseRepository.findById(nullableId).orElseThrow().getDescription())
                .isNull();
    }

    @Test
    void updatesIncrementVersionAndStaleUpdatesAreRejected() {
        AutomationSuite suite = createSuite("Version suite");
        UUID caseId = testCaseRepository.saveAndFlush(
                newCase(suite, "Versioned", "versioned", 0)).getId();
        AutomationTestCase original = testCaseRepository.findById(caseId).orElseThrow();
        OffsetDateTime originalCreatedAt = original.getCreatedAt();
        OffsetDateTime originalUpdatedAt = original.getUpdatedAt();
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            AutomationTestCase firstCopy = first.find(AutomationTestCase.class, caseId);
            AutomationTestCase staleCopy = second.find(AutomationTestCase.class, caseId);
            first.getTransaction().begin();
            firstCopy.setDescription("accepted update");
            first.getTransaction().commit();
            second.getTransaction().begin();
            staleCopy.setDescription("stale update");

            assertThatThrownBy(() -> second.getTransaction().commit())
                    .isInstanceOfAny(OptimisticLockException.class, RollbackException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT version FROM automation_test_case WHERE id = ?",
                    Long.class, caseId)).isEqualTo(1L);
            AutomationTestCase updated = testCaseRepository.findById(caseId).orElseThrow();
            assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
            assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        } finally {
            if (second.getTransaction().isActive()) {
                second.getTransaction().rollback();
            }
            first.close();
            second.close();
        }
    }

    @Test
    void suiteScopedLookupExistenceAndUpdateUniquenessChecksRemainIsolated() {
        AutomationSuite firstSuite = createSuite("First ownership suite");
        AutomationSuite secondSuite = createSuite("Second ownership suite");
        AutomationTestCase first = testCaseRepository.saveAndFlush(
                newCase(firstSuite, "Shared", "shared-ref", 0));
        AutomationTestCase second = testCaseRepository.saveAndFlush(
                newCase(firstSuite, "Second", "second-ref", 1));
        testCaseRepository.saveAndFlush(newCase(secondSuite, "Shared", "shared-ref", 0));

        assertThat(testCaseRepository.findByAutomationSuiteIdAndId(
                firstSuite.getId(), first.getId())).isPresent();
        assertThat(testCaseRepository.findByAutomationSuiteIdAndId(
                secondSuite.getId(), first.getId())).isEmpty();
        assertThat(testCaseRepository.existsByAutomationSuiteId(firstSuite.getId())).isTrue();
        assertThat(testCaseRepository.existsByAutomationSuiteId(UUID.randomUUID())).isFalse();
        assertThat(testCaseRepository.existsByAutomationSuiteIdAndName(
                firstSuite.getId(), "Shared")).isTrue();
        assertThat(testCaseRepository.existsByAutomationSuiteIdAndCaseReference(
                firstSuite.getId(), "shared-ref")).isTrue();
        assertThat(testCaseRepository.existsByAutomationSuiteIdAndNameAndIdNot(
                firstSuite.getId(), "Shared", first.getId())).isFalse();
        assertThat(testCaseRepository.existsByAutomationSuiteIdAndNameAndIdNot(
                firstSuite.getId(), "Shared", second.getId())).isTrue();
        assertThat(testCaseRepository.existsByAutomationSuiteIdAndCaseReferenceAndIdNot(
                firstSuite.getId(), "shared-ref", first.getId())).isFalse();
        assertThat(testCaseRepository.existsByAutomationSuiteIdAndCaseReferenceAndIdNot(
                firstSuite.getId(), "shared-ref", second.getId())).isTrue();
    }

    @Test
    void paginationStatusFilteringMaximumPositionAndCommittedOrderingAreSuiteScoped() {
        AutomationSuite suite = createSuite("Query suite");
        AutomationSuite otherSuite = createSuite("Other query suite");
        AutomationTestCase third = newCase(suite, "Third", "third", 3);
        AutomationTestCase first = newCase(suite, "First", "first", 0);
        AutomationTestCase archived = newCase(suite, "Archived", "archived", 2);
        archived.setStatus(AutomationTestCaseStatus.ARCHIVED);
        testCaseRepository.saveAllAndFlush(java.util.List.of(third, first, archived));
        testCaseRepository.saveAndFlush(newCase(otherSuite, "Other", "other", 20));

        Page<AutomationTestCase> page = testCaseRepository.findByAutomationSuiteId(
                suite.getId(), PageRequest.of(0, 2, Sort.by("position")));
        Page<AutomationTestCase> archivedPage =
                testCaseRepository.findByAutomationSuiteIdAndStatus(
                        suite.getId(), AutomationTestCaseStatus.ARCHIVED,
                        PageRequest.of(0, 10, Sort.by("position")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(AutomationTestCase::getPosition)
                .containsExactly(0, 2);
        assertThat(archivedPage.getContent()).extracting(AutomationTestCase::getId)
                .containsExactly(archived.getId());
        assertThat(testCaseRepository.findMaximumPositionByAutomationSuiteId(suite.getId()))
                .contains(3);
        assertThat(testCaseRepository.findMaximumPositionByAutomationSuiteId(UUID.randomUUID()))
                .isEmpty();
        assertThat(testCaseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(suite.getId()))
                .extracting(AutomationTestCase::getPosition).containsExactly(0, 2, 3);
    }

    @Test
    void databaseUniquenessRemainsAuthoritative() {
        AutomationSuite suite = createSuite("Unique suite");
        testCaseRepository.saveAndFlush(newCase(suite, "Original", "original", 0));

        assertThatThrownBy(() -> testCaseRepository.saveAndFlush(
                newCase(suite, "Original", "other-reference", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void entityUsesReferenceOnlyDatabaseColumnsAndStringStatus() {
        AutomationSuite suite = createSuite("Alignment suite");
        AutomationTestCase testCase = newCase(suite, "Aligned", "aligned", 0);
        testCase.setStatus(AutomationTestCaseStatus.INACTIVE);
        testCase.setConfiguration(Map.of("key", "value"));
        UUID caseId = testCaseRepository.saveAndFlush(testCase).getId();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM automation_test_case WHERE id = ?
                """, String.class, caseId)).isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT jsonb_typeof(configuration)
                FROM automation_test_case WHERE id = ?
                """, String.class, caseId)).isEqualTo("object");
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'automation_test_case'
                """, String.class)).doesNotContain(
                        "engine_id", "engine_type", "definition", "definition_type");
    }

    @Test
    void projectScopedSuiteLockAndSuiteScopedCaseLocksExecuteInsideTransactions() {
        AutomationSuite suite = createSuite("Lock suite");
        AutomationTestCase first = testCaseRepository.saveAndFlush(
                newCase(suite, "First lock", "first-lock", 0));
        AutomationTestCase second = testCaseRepository.saveAndFlush(
                newCase(suite, "Second lock", "second-lock", 1));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(automationSuiteRepository.findByProjectIdAndIdForUpdate(
                    suite.getProject().getId(), suite.getId())).isPresent();
            assertThat(automationSuiteRepository.findByProjectIdAndIdForUpdate(
                    UUID.randomUUID(), suite.getId())).isEmpty();
            assertThat(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(
                    suite.getId(), first.getId())).isPresent();
            assertThat(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(
                    UUID.randomUUID(), first.getId())).isEmpty();
            assertThat(testCaseRepository.findAllByAutomationSuiteIdForUpdate(suite.getId()))
                    .extracting(AutomationTestCase::getId)
                    .containsExactly(first.getId(), second.getId());
        });
    }

    @Test
    void physicalDeletionRemovesOnlyTheSelectedCaseAndRetainsItsSuite() {
        AutomationSuite suite = createSuite("Deletion suite");
        AutomationTestCase removed = testCaseRepository.saveAndFlush(
                newCase(suite, "Removed", "removed", 0));
        AutomationTestCase retained = testCaseRepository.saveAndFlush(
                newCase(suite, "Retained", "retained", 1));

        testCaseRepository.delete(removed);
        testCaseRepository.flush();

        assertThat(testCaseRepository.findById(removed.getId())).isEmpty();
        assertThat(testCaseRepository.findById(retained.getId())).isPresent();
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

    private AutomationTestCase newCase(
            AutomationSuite suite, String name, String caseReference, int position) {
        AutomationTestCase testCase = new AutomationTestCase();
        testCase.setAutomationSuite(suite);
        testCase.setName(name);
        testCase.setCaseReference(caseReference);
        testCase.setPosition(position);
        return testCase;
    }

    private UUID insertProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "Test Case Repository Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "Test Case Repository Project " + suffix);
        return projectId;
    }
}
