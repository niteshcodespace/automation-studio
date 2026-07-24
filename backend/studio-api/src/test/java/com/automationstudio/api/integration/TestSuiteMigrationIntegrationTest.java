package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class TestSuiteMigrationIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-015a-migration-test";
    private static final String WORKSPACE_SLUG_PREFIX = "as-015a-migration-test-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
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
                DELETE FROM environment
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
                    SELECT id
                    FROM workspace
                    WHERE slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_SLUG_PREFIX + "%");
    }

    @Test
    void applicationSchemaIncludesAutomationSuiteExpansion() {
        assertThat(POSTGRESQL_CONTAINER.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '7' AND success
                """, Integer.class)).isEqualTo(1);

        ColumnMetadata engineId = columnMetadata("engine_id");
        assertThat(engineId.dataType()).isEqualTo("character varying");
        assertThat(engineId.maximumLength()).isEqualTo(100);
        assertThat(engineId.nullable()).isEqualTo("YES");
        assertThat(engineId.defaultValue()).isNull();

        ColumnMetadata suiteType = columnMetadata("suite_type");
        assertThat(suiteType.dataType()).isEqualTo("character varying");
        assertThat(suiteType.maximumLength()).isEqualTo(30);
        assertThat(suiteType.nullable()).isEqualTo("YES");
        assertThat(suiteType.defaultValue()).isNull();

        ColumnMetadata configuration = columnMetadata("configuration");
        assertThat(configuration.dataType()).isEqualTo("jsonb");
        assertThat(configuration.nullable()).isEqualTo("YES");
        assertThat(configuration.defaultValue()).isNull();

        ColumnMetadata version = columnMetadata("version");
        assertThat(version.dataType()).isEqualTo("bigint");
        assertThat(version.nullable()).isEqualTo("NO");
        assertThat(version.defaultValue()).contains("0");
    }

    @Test
    void suiteTypeAcceptsNullAndSupportedValues() {
        UUID projectId = insertProject();
        UUID nullSuiteId = UUID.randomUUID();
        insertLegacySuite(projectId, nullSuiteId, "Null suite type");
        assertThat(readSuiteType(nullSuiteId)).isNull();

        List<String> supportedValues = List.of(
                "API", "UI", "MOBILE", "PERFORMANCE", "SECURITY", "DATABASE");

        for (String suiteType : supportedValues) {
            UUID suiteId = UUID.randomUUID();
            insertSuiteWithSuiteType(projectId, suiteId, suiteType);
            assertThat(readSuiteType(suiteId)).isEqualTo(suiteType);
        }
    }

    @Test
    void suiteTypeRejectsUnsupportedValue() {
        UUID projectId = insertProject();

        assertThatThrownBy(() -> insertSuiteWithSuiteType(
                projectId, UUID.randomUUID(), "UNSUPPORTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void configurationAcceptsNullAndJsonObjects() {
        UUID projectId = insertProject();
        UUID nullConfigurationSuiteId = UUID.randomUUID();
        insertLegacySuite(projectId, nullConfigurationSuiteId, "Null configuration");
        assertThat(columnIsNull(nullConfigurationSuiteId, "configuration")).isTrue();

        List<String> validConfigurations = List.of(
                "{}",
                "{\"browser\":\"chromium\",\"headless\":true}");

        for (String configuration : validConfigurations) {
            UUID suiteId = UUID.randomUUID();
            insertSuiteWithConfiguration(projectId, suiteId, configuration);
            assertThat(configurationMatches(suiteId, configuration)).isTrue();
        }
    }

    @Test
    void configurationRejectsNonObjectJsonValues() {
        UUID projectId = insertProject();
        List<String> invalidConfigurations = List.of("[]", "\"value\"", "42");

        for (String configuration : invalidConfigurations) {
            assertThatThrownBy(() -> insertSuiteWithConfiguration(
                    projectId, UUID.randomUUID(), configuration))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void versionRejectsNegativeValue() {
        UUID projectId = insertProject();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status, version
                )
                VALUES (?, ?, ?, 'PLAYWRIGHT', 'tests/negative-version', 'ACTIVE', -1)
                """, UUID.randomUUID(), projectId, "Negative version"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacySuiteRemainsCompatibleWithExecutionRelationship() {
        UUID projectId = insertProject();
        UUID suiteId = UUID.randomUUID();
        insertLegacySuite(projectId, suiteId, "Legacy suite");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM test_suite WHERE id = ?", Long.class, suiteId))
                .isZero();
        assertThat(columnIsNull(suiteId, "engine_id")).isTrue();
        assertThat(columnIsNull(suiteId, "suite_type")).isTrue();
        assertThat(columnIsNull(suiteId, "configuration")).isTrue();

        UUID environmentId = insertEnvironment(projectId);
        UUID executionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                )
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                """, executionId, projectId, environmentId, suiteId, TEST_ACTOR);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT test_suite_id FROM execution WHERE id = ?", UUID.class, executionId))
                .isEqualTo(suiteId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM test_suite WHERE id = ?", suiteId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_suite WHERE id = ?", Integer.class, suiteId))
                .isEqualTo(1);
    }

    private ColumnMetadata columnMetadata(String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                    AND table_name = 'test_suite'
                    AND column_name = ?
                """, (resultSet, rowNumber) -> new ColumnMetadata(
                resultSet.getString("data_type"),
                (Integer) resultSet.getObject("character_maximum_length"),
                resultSet.getString("is_nullable"),
                resultSet.getString("column_default")), columnName);
    }

    private UUID insertProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "Migration Test Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "Migration Test Project " + suffix);
        return projectId;
    }

    private UUID insertEnvironment(UUID projectId) {
        UUID environmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'Migration Test Environment', 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        return environmentId;
    }

    private void insertLegacySuite(UUID projectId, UUID suiteId, String name) {
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, description,
                    engine_type, suite_reference, status
                )
                VALUES (?, ?, ?, 'Legacy suite row', 'PLAYWRIGHT', 'tests/legacy', 'ACTIVE')
                """, suiteId, projectId, name);
    }

    private void insertSuiteWithSuiteType(UUID projectId, UUID suiteId, String suiteType) {
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status, suite_type
                )
                VALUES (?, ?, ?, 'PLAYWRIGHT', 'tests/suite-type', 'ACTIVE', ?)
                """, suiteId, projectId, "Suite type " + suiteId, suiteType);
    }

    private void insertSuiteWithConfiguration(
            UUID projectId,
            UUID suiteId,
            String configuration) {
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status, configuration
                )
                VALUES (
                    ?, ?, ?, 'PLAYWRIGHT', 'tests/configuration', 'ACTIVE', CAST(? AS jsonb)
                )
                """, suiteId, projectId, "Configuration " + suiteId, configuration);
    }

    private String readSuiteType(UUID suiteId) {
        return jdbcTemplate.queryForObject(
                "SELECT suite_type FROM test_suite WHERE id = ?", String.class, suiteId);
    }

    private boolean configurationMatches(UUID suiteId, String configuration) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT configuration = CAST(? AS jsonb) FROM test_suite WHERE id = ?",
                Boolean.class,
                configuration,
                suiteId));
    }

    private boolean columnIsNull(UUID suiteId, String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT " + columnName + " IS NULL FROM test_suite WHERE id = ?",
                Boolean.class,
                suiteId));
    }

    private record ColumnMetadata(
            String dataType,
            Integer maximumLength,
            String nullable,
            String defaultValue) {
    }
}
