package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EnvironmentMigrationIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-017b-migration-test";
    private static final String WORKSPACE_SLUG_PREFIX = "as-017b-migration-test-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
        deleteOwned("environment");
        deleteOwned("test_suite");
        jdbcTemplate.update("""
                DELETE FROM project WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_SLUG_PREFIX + "%");
    }

    @Test
    void migrationCreatesExpectedColumnsConstraintsIndexesAndForeignKey() {
        assertThat(POSTGRESQL_CONTAINER.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '9' AND success
                """, Integer.class)).isEqualTo(1);

        assertColumn("description", "character varying", 1000, "YES", null);
        assertColumn("type", "character varying", 30, "NO", null);
        assertColumn("configuration", "jsonb", null, "NO", "'{}'::jsonb");
        assertColumn("secret_references", "jsonb", null, "NO", "'{}'::jsonb");
        assertColumn("is_default", "boolean", null, "NO", "false");
        assertColumn("version", "bigint", null, "NO", "0");

        Set<String> constraints = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'environment'
                """, String.class));
        assertThat(constraints).contains(
                "environment_pkey", "fk_environment_project", "uk_environment_project_name",
                "chk_environment_status", "chk_environment_type",
                "chk_environment_configuration", "chk_environment_secret_references",
                "chk_environment_version", "chk_environment_default_active");

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'environment'
                """, String.class);
        assertThat(indexes)
                .anyMatch(value -> value.contains("idx_environment_project_id"))
                .anyMatch(value -> value.contains("uk_environment_project_name"))
                .anyMatch(value -> value.contains("uk_environment_project_default")
                        && value.contains("WHERE (is_default = true)"));

        Map<String, Object> foreignKey = jdbcTemplate.queryForMap("""
                SELECT ccu.table_name AS target_table, ccu.column_name AS target_column,
                       rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.referential_constraints rc
                  ON rc.constraint_schema = tc.constraint_schema
                 AND rc.constraint_name = tc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = tc.constraint_schema
                 AND ccu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'public' AND tc.table_name = 'execution'
                  AND tc.constraint_name = 'fk_execution_environment'
                """);
        assertThat(foreignKey)
                .containsEntry("target_table", "environment")
                .containsEntry("target_column", "id")
                .containsEntry("delete_rule", "RESTRICT");
    }

    @Test
    void migrationUpgradesPopulatedV8SchemaAndPreservesLegacyData() {
        String schema = "as017b_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);
        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("8"));
            ExistingIds ids = insertLegacyData(isolated);
            Map<String, Object> before = isolated.queryForMap("""
                    SELECT id, project_id, name, base_url, status, created_at, updated_at
                    FROM environment WHERE id = ?
                    """, ids.environmentId());

            migrate(dataSource, schema, null);

            Map<String, Object> after = isolated.queryForMap("""
                    SELECT id, project_id, name, base_url, status, created_at, updated_at,
                           description, type, configuration::text AS configuration,
                           secret_references::text AS secret_references,
                           is_default, version
                    FROM environment WHERE id = ?
                    """, ids.environmentId());
            assertThat(after).containsAllEntriesOf(before);
            assertThat(after)
                    .containsEntry("description", null)
                    .containsEntry("type", "TEST")
                    .containsEntry("configuration", "{}")
                    .containsEntry("secret_references", "{}")
                    .containsEntry("is_default", false)
                    .containsEntry("version", 0L);
            assertThat(isolated.queryForObject(
                    "SELECT environment_id FROM execution WHERE id = ?",
                    UUID.class, ids.executionId())).isEqualTo(ids.environmentId());
            assertThatThrownBy(() -> isolated.update(
                    "DELETE FROM environment WHERE id = ?", ids.environmentId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void supportedTypesAreAcceptedAndUnsupportedTypeIsRejected() {
        UUID projectId = insertProject();
        for (String type : List.of(
                "LOCAL", "DEV", "TEST", "QA", "STAGING", "UAT", "PRODUCTION")) {
            UUID id = insertEnvironment(projectId, "Type " + type, type, "ACTIVE", false);
            assertThat(readString(id, "type")).isEqualTo(type);
        }
        assertThatThrownBy(() -> insertEnvironment(
                projectId, "Unsupported type", "UNKNOWN", "ACTIVE", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void supportedStatusesAreAcceptedAndUnsupportedStatusIsRejected() {
        UUID projectId = insertProject();
        for (String status : List.of("ACTIVE", "INACTIVE", "ARCHIVED")) {
            UUID id = insertEnvironment(projectId, "Status " + status, "TEST", status, false);
            assertThat(readString(id, "status")).isEqualTo(status);
        }
        assertThatThrownBy(() -> insertEnvironment(
                projectId, "Unsupported status", "TEST", "UNKNOWN", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void configurationDefaultsToObjectAndRejectsEveryNonObjectRoot() {
        UUID projectId = insertProject();
        UUID defaultId = insertEnvironment(
                projectId, "Default configuration", "TEST", "ACTIVE", false);
        assertThat(jsonEquals(defaultId, "configuration", "{}")).isTrue();
        String object = "{\"browser\":\"chromium\",\"headless\":true}";
        UUID objectId = insertWithJson(projectId, "Object configuration", "configuration", object);
        assertThat(jsonEquals(objectId, "configuration", object)).isTrue();
        assertNonObjectRootsRejected(projectId, "configuration");
    }

    @Test
    void secretReferencesDefaultsToObjectAndRejectsEveryNonObjectRoot() {
        UUID projectId = insertProject();
        UUID defaultId = insertEnvironment(
                projectId, "Default references", "TEST", "ACTIVE", false);
        assertThat(jsonEquals(defaultId, "secret_references", "{}")).isTrue();
        String object = "{\"username\":\"vault://synthetic/username\"}";
        UUID objectId = insertWithJson(projectId, "Object references", "secret_references", object);
        assertThat(jsonEquals(objectId, "secret_references", object)).isTrue();
        assertNonObjectRootsRejected(projectId, "secret_references");
    }

    @Test
    void negativeVersionIsRejected() {
        UUID projectId = insertProject();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status, version
                ) VALUES (?, ?, 'Negative version', 'https://example.test',
                          'TEST', 'ACTIVE', -1)
                """, UUID.randomUUID(), projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyActiveEnvironmentMayBeDefault() {
        UUID activeProject = insertProject();
        UUID activeId = insertEnvironment(
                activeProject, "Active default", "TEST", "ACTIVE", true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_default FROM environment WHERE id = ?",
                Boolean.class, activeId)).isTrue();
        for (String status : List.of("INACTIVE", "ARCHIVED")) {
            assertThatThrownBy(() -> insertEnvironment(
                    insertProject(), status + " default", "TEST", status, true))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void defaultIsUniquePerProjectButDifferentProjectsMayEachHaveOne() {
        UUID firstProject = insertProject();
        UUID secondProject = insertProject();
        insertEnvironment(firstProject, "First default", "TEST", "ACTIVE", true);
        assertThatThrownBy(() -> insertEnvironment(
                firstProject, "Duplicate default", "QA", "ACTIVE", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertEnvironment(secondProject, "Second default", "QA", "ACTIVE", true);
    }

    @Test
    void nameUniquenessRemainsProjectScoped() {
        UUID firstProject = insertProject();
        UUID secondProject = insertProject();
        insertEnvironment(firstProject, "Shared name", "TEST", "ACTIVE", false);
        assertThatThrownBy(() -> insertEnvironment(
                firstProject, "Shared name", "QA", "ACTIVE", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertEnvironment(secondProject, "Shared name", "PRODUCTION", "ACTIVE", false);
    }

    @Test
    void referencedEnvironmentCannotBeDeletedButUnreferencedEnvironmentCan() {
        UUID projectId = insertProject();
        UUID referenced = insertEnvironment(projectId, "Referenced", "TEST", "ACTIVE", false);
        UUID unreferenced = insertEnvironment(projectId, "Unreferenced", "QA", "ACTIVE", false);
        UUID suiteId = insertSuite(projectId);
        insertExecution(projectId, referenced, suiteId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM environment WHERE id = ?", referenced))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.update(
                "DELETE FROM environment WHERE id = ?", unreferenced)).isOne();
    }

    private void deleteOwned(String table) {
        jdbcTemplate.update("""
                DELETE FROM %s WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?
                )
                """.formatted(table), WORKSPACE_SLUG_PREFIX + "%");
    }

    private void assertColumn(
            String name, String type, Integer length, String nullable, String defaultFragment) {
        ColumnMetadata metadata = jdbcTemplate.queryForObject("""
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'environment'
                  AND column_name = ?
                """, (rs, row) -> new ColumnMetadata(
                rs.getString("data_type"),
                (Integer) rs.getObject("character_maximum_length"),
                rs.getString("is_nullable"), rs.getString("column_default")), name);
        assertThat(metadata.dataType()).isEqualTo(type);
        assertThat(metadata.maximumLength()).isEqualTo(length);
        assertThat(metadata.nullable()).isEqualTo(nullable);
        if (defaultFragment == null) {
            assertThat(metadata.defaultValue()).isNull();
        } else {
            assertThat(metadata.defaultValue()).contains(defaultFragment);
        }
    }

    private UUID insertProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-017B Workspace " + workspaceId,
                WORKSPACE_SLUG_PREFIX + workspaceId);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-017B Project " + workspaceId);
        return projectId;
    }

    private UUID insertEnvironment(
            UUID projectId, String name, String type, String status, boolean isDefault) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status, is_default
                ) VALUES (?, ?, ?, 'https://example.test', ?, ?, ?)
                """, id, projectId, name, type, status, isDefault);
        return id;
    }

    private UUID insertWithJson(UUID projectId, String name, String column, String json) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status, %s
                ) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE', CAST(? AS jsonb))
                """.formatted(column), id, projectId, name, json);
        return id;
    }

    private void assertNonObjectRootsRejected(UUID projectId, String column) {
        for (String json : List.of("[]", "\"value\"", "42", "true", "false", "null")) {
            assertThatThrownBy(() -> insertWithJson(
                    projectId, "Invalid " + column + " " + UUID.randomUUID(), column, json))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private String readString(UUID id, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM environment WHERE id = ?", String.class, id);
    }

    private boolean jsonEquals(UUID id, String column, String json) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT " + column + " = CAST(? AS jsonb) FROM environment WHERE id = ?",
                Boolean.class, json, id));
    }

    private UUID insertSuite(UUID projectId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, id, projectId, "AS-017B Suite " + id, "tests/" + id);
        return id;
    }

    private void insertExecution(UUID projectId, UUID environmentId, UUID suiteId) {
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING', ?)
                """, UUID.randomUUID(), projectId, environmentId, suiteId, TEST_ACTOR);
    }

    private DriverManagerDataSource schemaDataSource(String schema) {
        String separator = POSTGRESQL_CONTAINER.getJdbcUrl().contains("?") ? "&" : "?";
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRESQL_CONTAINER.getJdbcUrl()
                + separator + "currentSchema=" + schema);
        dataSource.setUsername(POSTGRESQL_CONTAINER.getUsername());
        dataSource.setPassword(POSTGRESQL_CONTAINER.getPassword());
        return dataSource;
    }

    private void migrate(
            DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private ExistingIds insertLegacyData(JdbcTemplate target) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        target.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'Legacy Workspace', ?, 'ACTIVE')
                """, workspaceId, "legacy-" + workspaceId);
        target.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'Legacy Project', 'ACTIVE')
                """, projectId, workspaceId);
        target.update("""
                INSERT INTO environment (id, project_id, name, base_url, status)
                VALUES (?, ?, 'Legacy Environment', 'https://legacy.example.test', 'INACTIVE')
                """, environmentId, projectId);
        target.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, 'Legacy Suite', 'PLAYWRIGHT', 'tests/legacy', 'ACTIVE')
                """, suiteId, projectId);
        target.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING', 'legacy-actor')
                """, executionId, projectId, environmentId, suiteId);
        return new ExistingIds(environmentId, executionId);
    }

    private record ColumnMetadata(
            String dataType, Integer maximumLength, String nullable, String defaultValue) {
    }

    private record ExistingIds(UUID environmentId, UUID executionId) {
    }
}
