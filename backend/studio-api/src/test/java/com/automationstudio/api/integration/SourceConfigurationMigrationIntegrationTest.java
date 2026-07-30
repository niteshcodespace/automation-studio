package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SourceConfigurationMigrationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationAddsOnlyDurableSourceIdentityColumnsAndConstraints() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '15' AND success
                """, Integer.class)).isOne();
        assertColumn("project", "source_type", "character varying", 30);
        assertColumn("project", "source_repository", "character varying", 1000);
        assertColumn("project", "source_revision", "character varying", 40);
        assertColumn("test_suite", "source_location", "character varying", 500);
        assertColumn("execution", "source_snapshot", "jsonb", null);

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name ILIKE '%workspace%path%'
                """, String.class)).isEmpty();
        assertThat(constraintExists("project", "chk_project_source_configuration")).isTrue();
        assertThat(constraintExists("test_suite", "chk_test_suite_source_location")).isTrue();
        assertThat(constraintExists("execution", "chk_execution_source_snapshot")).isTrue();
    }

    @Test
    void migrationPreservesHistoricalRowsWithoutInventingSource() {
        String schema = "as023b_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);
        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("14"));
            UUID executionId = insertHistoricalFixture(isolated);
            migrate(dataSource, schema, null);

            Map<String, Object> source = isolated.queryForMap("""
                    SELECT p.source_type, p.source_repository, p.source_revision,
                           ts.source_location, e.source_snapshot
                    FROM execution e
                    JOIN project p ON p.id = e.project_id
                    JOIN test_suite ts ON ts.id = e.test_suite_id
                    WHERE e.id = ?
                    """, executionId);
            assertThat(source.values()).containsOnlyNulls();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void databaseRejectsPartialSourceInvalidRevisionLocationAndSnapshotShape() {
        Fixture fixture = insertFixture();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE project SET source_type = 'GIT_HTTPS' WHERE id = ?
                """, fixture.projectId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE project
                SET source_type = 'GIT_HTTPS',
                    source_repository = 'https://example.test/repo.git',
                    source_revision = 'main'
                WHERE id = ?
                """, fixture.projectId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE test_suite SET source_location = '../escape' WHERE id = ?
                """, fixture.suiteId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE execution SET source_snapshot = CAST('[]' AS jsonb) WHERE id = ?
                """, fixture.executionId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE execution
                SET source_snapshot = CAST(
                    '{"sourceType":"GIT_HTTPS","revision":"1111111111111111111111111111111111111111"}'
                    AS jsonb)
                WHERE id = ?
                """, fixture.executionId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertColumn(String table, String column, String type, Integer length) {
        Map<String, Object> metadata = jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, table, column);
        assertThat(metadata)
                .containsEntry("data_type", type)
                .containsEntry("character_maximum_length", length)
                .containsEntry("is_nullable", "YES");
    }

    private boolean constraintExists(String table, String constraint) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                """, Integer.class, table, constraint) == 1;
    }

    private Fixture insertFixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-023B " + workspaceId, "as-023b-" + workspaceId);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-023B " + projectId);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId, "AS-023B " + environmentId);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status)
                VALUES (?, ?, ?, 'BUILTIN', 'builtin', 'ACTIVE')
                """, suiteId, projectId, "AS-023B " + suiteId);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status,
                    selection_mode, requested_by)
                VALUES (?, ?, ?, ?, 'PENDING', 'SUITE', 'as-023b')
                """, executionId, projectId, environmentId, suiteId);
        return new Fixture(projectId, suiteId, executionId);
    }

    private UUID insertHistoricalFixture(JdbcTemplate isolated) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        isolated.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "Historical", "historical-" + workspaceId);
        isolated.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "Historical");
        isolated.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId, "Historical");
        isolated.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status)
                VALUES (?, ?, ?, 'BUILTIN', 'builtin', 'ACTIVE')
                """, suiteId, projectId, "Historical");
        isolated.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status,
                    selection_mode, requested_by)
                VALUES (?, ?, ?, ?, 'PENDING', 'SUITE', 'historical')
                """, executionId, projectId, environmentId, suiteId);
        return executionId;
    }

    private void migrate(
            DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private DriverManagerDataSource schemaDataSource(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        String separator = POSTGRESQL_CONTAINER.getJdbcUrl().contains("?") ? "&" : "?";
        dataSource.setUrl(POSTGRESQL_CONTAINER.getJdbcUrl()
                + separator + "currentSchema=" + schema);
        dataSource.setUsername(POSTGRESQL_CONTAINER.getUsername());
        dataSource.setPassword(POSTGRESQL_CONTAINER.getPassword());
        return dataSource;
    }

    private record Fixture(UUID projectId, UUID suiteId, UUID executionId) {
    }
}
