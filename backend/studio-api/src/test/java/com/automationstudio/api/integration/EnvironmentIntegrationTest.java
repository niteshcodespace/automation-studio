package com.automationstudio.api.integration;

import static com.automationstudio.api.repository.EnvironmentSpecifications.withFilters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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

class EnvironmentIntegrationTest extends IntegrationTestBase {

    private static final String WORKSPACE_SLUG_PREFIX = "as-017c-environment-test-";

    @Autowired
    private EnvironmentRepository environmentRepository;

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
    void completeEnvironmentRoundTripsWithDefaultsJsonAuditAndGeneratedIdentity() {
        Project project = insertAndLoadProject();
        Environment environment = newEnvironment(project, "Complete", EnvironmentType.QA);
        environment.setDescription("QA target");
        environment.setConfiguration(Map.of("browser", "chromium", "retries", 2));
        environment.setSecretReferences(Map.of(
                "username", "vault://synthetic/qa/username",
                "password", "vault://synthetic/qa/password"));

        UUID id = environmentRepository.saveAndFlush(environment).getId();
        EntityManager fresh = entityManagerFactory.createEntityManager();
        try {
            Environment loaded = fresh.find(Environment.class, id);
            assertThat(id).isNotNull();
            assertThat(loaded.getProject().getId()).isEqualTo(project.getId());
            assertThat(loaded.getName()).isEqualTo("Complete");
            assertThat(loaded.getDescription()).isEqualTo("QA target");
            assertThat(loaded.getBaseUrl()).isEqualTo("https://example.test");
            assertThat(loaded.getType()).isEqualTo(EnvironmentType.QA);
            assertThat(loaded.getConfiguration())
                    .containsEntry("browser", "chromium")
                    .containsEntry("retries", 2);
            assertThat(loaded.getSecretReferences())
                    .containsEntry("username", "vault://synthetic/qa/username")
                    .containsEntry("password", "vault://synthetic/qa/password");
            assertThat(loaded.getStatus()).isEqualTo(EnvironmentStatus.ACTIVE);
            assertThat(loaded.isDefault()).isFalse();
            assertThat(loaded.getVersion()).isZero();
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getUpdatedAt()).isNotNull();
        } finally {
            fresh.close();
        }
    }

    @Test
    void jsonMapsNormalizeNullAndDefensivelyCopyTopLevelValues() {
        Project project = insertAndLoadProject();
        Environment environment = newEnvironment(project, "Defensive", EnvironmentType.TEST);
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("browser", "chromium");
        Map<String, Object> references = new LinkedHashMap<>();
        references.put("token", "vault://synthetic/token");
        environment.setConfiguration(configuration);
        environment.setSecretReferences(references);
        configuration.put("browser", "mutated-after-set");
        references.put("token", "mutated-after-set");

        assertThat(environment.getConfiguration()).containsEntry("browser", "chromium");
        assertThat(environment.getSecretReferences())
                .containsEntry("token", "vault://synthetic/token");
        environment.getConfiguration().put("browser", "mutated-after-get");
        environment.getSecretReferences().put("token", "mutated-after-get");
        assertThat(environment.getConfiguration()).containsEntry("browser", "chromium");
        assertThat(environment.getSecretReferences())
                .containsEntry("token", "vault://synthetic/token");

        environment.setConfiguration(null);
        environment.setSecretReferences(null);
        assertThat(environment.getConfiguration()).isEmpty();
        assertThat(environment.getSecretReferences()).isEmpty();
        UUID id = environmentRepository.saveAndFlush(environment).getId();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT configuration = '{}'::jsonb FROM environment WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT secret_references = '{}'::jsonb FROM environment WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void updateIncrementsVersionAndRejectsAStaleEntity() {
        Project project = insertAndLoadProject();
        UUID id = environmentRepository.saveAndFlush(
                newEnvironment(project, "Versioned", EnvironmentType.STAGING)).getId();
        Environment original = environmentRepository.findById(id).orElseThrow();
        OffsetDateTime createdAt = original.getCreatedAt();
        OffsetDateTime updatedAt = original.getUpdatedAt();
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            Environment firstCopy = first.find(Environment.class, id);
            Environment staleCopy = second.find(Environment.class, id);
            assertThat(firstCopy.getVersion()).isEqualTo(staleCopy.getVersion()).isZero();

            first.getTransaction().begin();
            firstCopy.setDescription("accepted update");
            first.getTransaction().commit();
            second.getTransaction().begin();
            staleCopy.setDescription("stale update");

            assertThatThrownBy(() -> second.getTransaction().commit())
                    .isInstanceOfAny(OptimisticLockException.class, RollbackException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT version FROM environment WHERE id = ?", Long.class, id))
                    .isEqualTo(1L);
            Environment updated = environmentRepository.findById(id).orElseThrow();
            assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
            assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updatedAt);
        } finally {
            if (second.getTransaction().isActive()) {
                second.getTransaction().rollback();
            }
            first.close();
            second.close();
        }
    }

    @Test
    void projectScopedIdentityNameListCountAndUniquenessRemainIsolated() {
        Project firstProject = insertAndLoadProject();
        Project secondProject = insertAndLoadProject();
        Environment first = environmentRepository.saveAndFlush(
                newEnvironment(firstProject, "Shared", EnvironmentType.DEV));
        Environment second = environmentRepository.saveAndFlush(
                newEnvironment(firstProject, "Second", EnvironmentType.TEST));
        Environment otherProject = environmentRepository.saveAndFlush(
                newEnvironment(secondProject, "Shared", EnvironmentType.PRODUCTION));

        assertThat(environmentRepository.findByProjectIdAndId(
                firstProject.getId(), first.getId())).isPresent();
        assertThat(environmentRepository.findByProjectIdAndId(
                secondProject.getId(), first.getId())).isEmpty();
        assertThat(environmentRepository.findByProjectIdAndName(
                firstProject.getId(), "Shared"))
                .map(Environment::getId).contains(first.getId());
        assertThat(environmentRepository.findByProjectIdAndName(
                secondProject.getId(), "Shared"))
                .map(Environment::getId).contains(otherProject.getId());
        assertThat(environmentRepository.existsByProjectIdAndName(
                firstProject.getId(), "Shared")).isTrue();
        assertThat(environmentRepository.findByProjectId(firstProject.getId()))
                .extracting(Environment::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(environmentRepository.countByProjectId(firstProject.getId())).isEqualTo(2);
        assertThat(environmentRepository.countByProjectId(secondProject.getId())).isEqualTo(1);

        assertThatThrownBy(() -> environmentRepository.saveAndFlush(
                newEnvironment(firstProject, "Shared", EnvironmentType.QA)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pagingSortingAndIndividualFiltersAreProjectScoped() {
        Project project = insertAndLoadProject();
        Project otherProject = insertAndLoadProject();
        Environment alpha = newEnvironment(project, "Alpha", EnvironmentType.TEST);
        Environment beta = newEnvironment(project, "Beta", EnvironmentType.QA);
        beta.setStatus(EnvironmentStatus.INACTIVE);
        Environment gamma = newEnvironment(project, "Gamma", EnvironmentType.TEST);
        gamma.setDefault(true);
        environmentRepository.saveAllAndFlush(List.of(alpha, beta, gamma));
        environmentRepository.saveAndFlush(
                newEnvironment(otherProject, "Other", EnvironmentType.TEST));

        Page<Environment> secondPage = environmentRepository.findByProjectId(
                project.getId(), PageRequest.of(1, 2, Sort.by("name")));
        assertThat(secondPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.getContent()).extracting(Environment::getName)
                .containsExactly("Gamma");
        assertThat(environmentRepository.findByProjectIdAndStatus(
                project.getId(), EnvironmentStatus.INACTIVE, PageRequest.of(0, 10)))
                .extracting(Environment::getId).containsExactly(beta.getId());
        assertThat(environmentRepository.findByProjectIdAndType(
                project.getId(), EnvironmentType.TEST, PageRequest.of(0, 10)))
                .extracting(Environment::getId)
                .containsExactlyInAnyOrder(alpha.getId(), gamma.getId());
        assertThat(environmentRepository.findByProjectIdAndIsDefault(
                project.getId(), true, PageRequest.of(0, 10)))
                .extracting(Environment::getId).containsExactly(gamma.getId());
    }

    @Test
    void combinedFiltersUseLogicalAndAndDefaultLookupIsProjectScoped() {
        Project project = insertAndLoadProject();
        Project otherProject = insertAndLoadProject();
        Environment selected = newEnvironment(project, "Selected", EnvironmentType.QA);
        selected.setDefault(true);
        Environment wrongStatus = newEnvironment(project, "Inactive", EnvironmentType.QA);
        wrongStatus.setStatus(EnvironmentStatus.INACTIVE);
        Environment wrongType = newEnvironment(project, "Test", EnvironmentType.TEST);
        environmentRepository.saveAllAndFlush(List.of(selected, wrongStatus, wrongType));
        Environment otherDefault = newEnvironment(
                otherProject, "Other default", EnvironmentType.QA);
        otherDefault.setDefault(true);
        environmentRepository.saveAndFlush(otherDefault);

        Page<Environment> result = environmentRepository.findAll(
                withFilters(project.getId(), EnvironmentStatus.ACTIVE, EnvironmentType.QA, true),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Environment::getId)
                .containsExactly(selected.getId());
        assertThat(environmentRepository.findByProjectIdAndIsDefaultTrue(project.getId()))
                .map(Environment::getId).contains(selected.getId());
        assertThat(environmentRepository.findByProjectIdAndIsDefaultTrue(otherProject.getId()))
                .map(Environment::getId).contains(otherDefault.getId());
        assertThat(environmentRepository.findByProjectIdAndIsDefaultTrue(UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void everySupportedTypeAndStatusPersistsAsAStringEnum() {
        Project project = insertAndLoadProject();
        for (EnvironmentType type : EnvironmentType.values()) {
            Environment saved = environmentRepository.saveAndFlush(
                    newEnvironment(project, "Type " + type, type));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT type FROM environment WHERE id = ?", String.class, saved.getId()))
                    .isEqualTo(type.name());
        }
        for (EnvironmentStatus status : EnvironmentStatus.values()) {
            Environment environment = newEnvironment(
                    project, "Status " + status, EnvironmentType.TEST);
            environment.setStatus(status);
            Environment saved = environmentRepository.saveAndFlush(environment);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM environment WHERE id = ?", String.class, saved.getId()))
                    .isEqualTo(status.name());
        }
    }

    @Test
    void databaseRejectsInvalidEnumsJsonRootsAndNegativeVersion() {
        UUID projectId = insertAndLoadProject().getId();
        assertInvalidInsert(projectId, "Invalid type", """
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, ?, 'https://example.test', 'UNKNOWN', 'ACTIVE')
                """);
        assertInvalidInsert(projectId, "Invalid status", """
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'UNKNOWN')
                """);
        for (String column : List.of("configuration", "secret_references")) {
            for (String json : List.of("[]", "\"value\"", "42", "true", "null")) {
                assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO environment (
                            id, project_id, name, base_url, type, status, %s
                        ) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE',
                                  CAST(? AS jsonb))
                        """.formatted(column), UUID.randomUUID(), projectId,
                        "Invalid " + column + " " + UUID.randomUUID(), json))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        }
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status, version
                ) VALUES (?, ?, 'Negative version', 'https://example.test',
                          'TEST', 'ACTIVE', -1)
                """, UUID.randomUUID(), projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesActiveAndUniqueDefaultsPerProject() {
        Project firstProject = insertAndLoadProject();
        Project secondProject = insertAndLoadProject();
        for (EnvironmentStatus status :
                List.of(EnvironmentStatus.INACTIVE, EnvironmentStatus.ARCHIVED)) {
            Environment invalid = newEnvironment(
                    firstProject, status + " default", EnvironmentType.TEST);
            invalid.setStatus(status);
            invalid.setDefault(true);
            assertThatThrownBy(() -> environmentRepository.saveAndFlush(invalid))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        Environment firstDefault = newEnvironment(
                firstProject, "First default", EnvironmentType.TEST);
        firstDefault.setDefault(true);
        environmentRepository.saveAndFlush(firstDefault);
        Environment duplicate = newEnvironment(
                firstProject, "Duplicate default", EnvironmentType.QA);
        duplicate.setDefault(true);
        assertThatThrownBy(() -> environmentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        Environment secondDefault = newEnvironment(
                secondProject, "Second default", EnvironmentType.PRODUCTION);
        secondDefault.setDefault(true);
        environmentRepository.saveAndFlush(secondDefault);
    }

    @Test
    void projectPessimisticLockQueryIsUsableInsideATransaction() {
        Project project = insertAndLoadProject();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(projectRepository.findByIdForUpdate(project.getId())).isPresent();
            assertThat(projectRepository.findByIdForUpdate(UUID.randomUUID())).isEmpty();
        });
    }

    private Environment newEnvironment(
            Project project, String name, EnvironmentType type) {
        Environment environment = new Environment();
        environment.setProject(project);
        environment.setName(name);
        environment.setBaseUrl("https://example.test");
        environment.setType(type);
        return environment;
    }

    private Project insertAndLoadProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "Environment Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "Environment Project " + suffix);
        return projectRepository.findById(projectId).orElseThrow();
    }

    private void assertInvalidInsert(UUID projectId, String name, String sql) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                sql, UUID.randomUUID(), projectId, name))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
