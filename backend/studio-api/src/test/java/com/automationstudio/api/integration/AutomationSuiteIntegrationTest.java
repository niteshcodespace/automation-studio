package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.SuiteType;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ProjectRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import jakarta.persistence.Table;
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

class AutomationSuiteIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-015b-domain-test";
    private static final String WORKSPACE_SLUG_PREFIX = "as-015b-domain-test-";

    @Autowired
    private AutomationSuiteRepository automationSuiteRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
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
    void mapsTheSingleAutomationSuiteEntityToTheExistingTableAndLoadsLegacyRows() {
        UUID projectId = insertProject();
        UUID suiteId = insertLegacySuite(projectId);

        AutomationSuite suite = automationSuiteRepository.findById(suiteId).orElseThrow();

        assertThat(suite.getName()).isEqualTo("Legacy suite");
        assertThat(suite.getEngineType()).isEqualTo("PLAYWRIGHT");
        assertThat(suite.getSuiteReference()).isEqualTo("tests/legacy");
        assertThat(suite.getEngineId()).isNull();
        assertThat(suite.getSuiteType()).isNull();
        assertThat(suite.getConfiguration()).isNull();
        assertThat(suite.getVersion()).isZero();

        var tableEntities = entityManagerFactory.getMetamodel().getEntities().stream()
                .filter(type -> type.getJavaType().isAnnotationPresent(Entity.class))
                .filter(type -> type.getJavaType().isAnnotationPresent(Table.class))
                .filter(type -> type.getJavaType().getAnnotation(Table.class)
                        .name().equals("test_suite"))
                .toList();
        assertThat(tableEntities).hasSize(1);
        assertThat(tableEntities.getFirst().getJavaType()).isEqualTo(AutomationSuite.class);
    }

    @Test
    void transitionalFieldsAndJsonObjectRoundTripWithDefensiveCopies() {
        Project project = projectRepository.findById(insertProject()).orElseThrow();
        AutomationSuite suite = newSuite(project, "Configured suite");
        suite.setEngineId("playwright-java");
        suite.setSuiteType(SuiteType.UI);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("browser", "chromium");
        input.put("headless", true);
        suite.setConfiguration(input);
        input.put("browser", "mutated-after-set");

        UUID suiteId = automationSuiteRepository.saveAndFlush(suite).getId();
        AutomationSuite loaded = automationSuiteRepository.findById(suiteId).orElseThrow();

        assertThat(loaded.getEngineId()).isEqualTo("playwright-java");
        assertThat(loaded.getSuiteType()).isEqualTo(SuiteType.UI);
        assertThat(loaded.getConfiguration()).containsEntry("browser", "chromium")
                .containsEntry("headless", true);
        Map<String, Object> returned = loaded.getConfiguration();
        returned.put("browser", "mutated-after-get");
        assertThat(loaded.getConfiguration()).containsEntry("browser", "chromium");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT engine_id FROM test_suite WHERE id = ?", String.class, suiteId))
                .isEqualTo("playwright-java");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT suite_type FROM test_suite WHERE id = ?", String.class, suiteId))
                .isEqualTo("UI");
    }

    @Test
    void nullConfigurationPersistsAndExecutionReferencesAutomationSuiteThroughLegacyJoinColumn() {
        Project project = projectRepository.findById(insertProject()).orElseThrow();
        Environment environment = environmentRepository.findById(insertEnvironment(project.getId()))
                .orElseThrow();
        AutomationSuite suite = automationSuiteRepository.saveAndFlush(
                newSuite(project, "Execution suite"));

        Execution execution = new Execution();
        execution.setProject(project);
        execution.setEnvironment(environment);
        execution.setAutomationSuite(suite);
        execution.setRequestedBy(TEST_ACTOR);
        UUID executionId = executionRepository.saveAndFlush(execution).getId();

        assertThat(automationSuiteRepository.findById(suite.getId()).orElseThrow()
                .getConfiguration()).isNull();
        assertThat(executionRepository.findById(executionId).orElseThrow()
                .getAutomationSuite().getId()).isEqualTo(suite.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT test_suite_id FROM execution WHERE id = ?", UUID.class, executionId))
                .isEqualTo(suite.getId());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM test_suite WHERE id = ?", suite.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleUpdateIsRejectedByVersionColumn() {
        Project project = projectRepository.findById(insertProject()).orElseThrow();
        UUID suiteId = automationSuiteRepository.saveAndFlush(
                newSuite(project, "Versioned suite")).getId();
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            AutomationSuite firstCopy = first.find(AutomationSuite.class, suiteId);
            AutomationSuite staleCopy = second.find(AutomationSuite.class, suiteId);

            first.getTransaction().begin();
            firstCopy.setDescription("first update");
            first.getTransaction().commit();

            second.getTransaction().begin();
            staleCopy.setDescription("stale update");
            assertThatThrownBy(() -> second.getTransaction().commit())
                    .isInstanceOfAny(OptimisticLockException.class, RollbackException.class);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT version FROM test_suite WHERE id = ?", Long.class, suiteId))
                    .isEqualTo(1L);
        } finally {
            if (second.getTransaction().isActive()) {
                second.getTransaction().rollback();
            }
            first.close();
            second.close();
        }
    }

    @Test
    void projectScopedQueriesReturnOnlySuitesOwnedByTheRequestedProject() {
        Project firstProject = projectRepository.findById(insertProject()).orElseThrow();
        Project secondProject = projectRepository.findById(insertProject()).orElseThrow();
        AutomationSuite first = automationSuiteRepository.saveAndFlush(
                newSuite(firstProject, "First project suite"));
        AutomationSuite second = automationSuiteRepository.saveAndFlush(
                newSuite(firstProject, "Second project suite"));
        automationSuiteRepository.saveAndFlush(newSuite(secondProject, "Other project suite"));

        assertThat(automationSuiteRepository.findByProjectIdAndId(
                firstProject.getId(), first.getId()))
                .map(AutomationSuite::getId).contains(first.getId());
        assertThat(automationSuiteRepository.findByProjectIdAndId(
                secondProject.getId(), first.getId())).isEmpty();
        assertThat(automationSuiteRepository.findByProjectId(firstProject.getId()))
                .extracting(AutomationSuite::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(automationSuiteRepository.countByProjectId(firstProject.getId()))
                .isEqualTo(2);
        assertThat(automationSuiteRepository.countByProjectId(secondProject.getId()))
                .isEqualTo(1);
    }

    @Test
    void projectScopedPaginationReturnsRequestedSliceAndMetadata() {
        Project project = projectRepository.findById(insertProject()).orElseThrow();
        automationSuiteRepository.saveAndFlush(newSuite(project, "Alpha suite"));
        automationSuiteRepository.saveAndFlush(newSuite(project, "Beta suite"));
        automationSuiteRepository.saveAndFlush(newSuite(project, "Gamma suite"));

        Page<AutomationSuite> page = automationSuiteRepository.findByProjectId(
                project.getId(), PageRequest.of(1, 2, Sort.by("name")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getContent()).extracting(AutomationSuite::getName)
                .containsExactly("Gamma suite");
    }

    @Test
    void nameQueriesAndExistenceChecksAreProjectScoped() {
        Project firstProject = projectRepository.findById(insertProject()).orElseThrow();
        Project secondProject = projectRepository.findById(insertProject()).orElseThrow();
        AutomationSuite suite = automationSuiteRepository.saveAndFlush(
                newSuite(firstProject, "Named suite"));

        assertThat(automationSuiteRepository.findByProjectIdAndName(
                firstProject.getId(), "Named suite"))
                .map(AutomationSuite::getId).contains(suite.getId());
        assertThat(automationSuiteRepository.findByProjectIdAndName(
                secondProject.getId(), "Named suite")).isEmpty();
        assertThat(automationSuiteRepository.existsByProjectIdAndName(
                firstProject.getId(), "Named suite")).isTrue();
        assertThat(automationSuiteRepository.existsByProjectIdAndName(
                firstProject.getId(), "Missing suite")).isFalse();
    }

    @Test
    void statusQueriesSupportProjectScopingAndPagination() {
        Project firstProject = projectRepository.findById(insertProject()).orElseThrow();
        Project secondProject = projectRepository.findById(insertProject()).orElseThrow();
        AutomationSuite active = automationSuiteRepository.saveAndFlush(
                newSuite(firstProject, "Active suite"));
        AutomationSuite archivedOne = newSuite(firstProject, "Archived alpha");
        archivedOne.setStatus(AutomationSuiteStatus.ARCHIVED);
        archivedOne = automationSuiteRepository.saveAndFlush(archivedOne);
        AutomationSuite archivedTwo = newSuite(firstProject, "Archived beta");
        archivedTwo.setStatus(AutomationSuiteStatus.ARCHIVED);
        archivedTwo = automationSuiteRepository.saveAndFlush(archivedTwo);
        AutomationSuite otherProjectArchived = newSuite(secondProject, "Other archived");
        otherProjectArchived.setStatus(AutomationSuiteStatus.ARCHIVED);
        automationSuiteRepository.saveAndFlush(otherProjectArchived);

        assertThat(automationSuiteRepository.findByProjectIdAndStatus(
                firstProject.getId(), AutomationSuiteStatus.ACTIVE))
                .extracting(AutomationSuite::getId).containsExactly(active.getId());
        assertThat(automationSuiteRepository.findByProjectIdAndStatus(
                firstProject.getId(), AutomationSuiteStatus.ARCHIVED))
                .extracting(AutomationSuite::getId)
                .containsExactlyInAnyOrder(archivedOne.getId(), archivedTwo.getId());

        Page<AutomationSuite> page = automationSuiteRepository.findByProjectIdAndStatus(
                firstProject.getId(), AutomationSuiteStatus.ARCHIVED,
                PageRequest.of(0, 1, Sort.by("name")));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AutomationSuite::getName)
                .containsExactly("Archived alpha");
    }

    private AutomationSuite newSuite(Project project, String name) {
        AutomationSuite suite = new AutomationSuite();
        suite.setProject(project);
        suite.setName(name);
        suite.setEngineType("PLAYWRIGHT");
        suite.setSuiteReference("tests/legacy");
        return suite;
    }

    private UUID insertProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "Domain Test Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "Domain Test Project " + suffix);
        return projectId;
    }

    private UUID insertEnvironment(UUID projectId) {
        UUID environmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, status)
                VALUES (?, ?, 'Domain Test Environment', 'https://example.test', 'ACTIVE')
                """, environmentId, projectId);
        return environmentId;
    }

    private UUID insertLegacySuite(UUID projectId) {
        UUID suiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                )
                VALUES (?, ?, 'Legacy suite', 'PLAYWRIGHT', 'tests/legacy', 'ACTIVE')
                """, suiteId, projectId);
        return suiteId;
    }
}
