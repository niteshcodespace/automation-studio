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

class ArtifactMetadataMigrationIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesCanonicalColumnsConstraintsIndexesAndRestrictForeignKey() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'execution_artifact'
                """, String.class)).contains(
                "category", "logical_name", "media_type", "size_bytes", "storage_reference",
                "checksum_algorithm", "checksum", "retention_reference", "metadata", "created_at",
                "legacy_storage_location");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'execution_artifact'
                """, String.class)).contains(
                "idx_execution_artifact_category", "idx_execution_artifact_execution_created",
                "uk_execution_artifact_storage_reference");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT rc.delete_rule
                FROM information_schema.referential_constraints rc
                WHERE rc.constraint_schema = 'public'
                  AND rc.constraint_name = 'fk_execution_artifact_execution'
                """, String.class)).isEqualTo("RESTRICT");
    }

    @Test
    void migrationPreservesLegacyRowsAndMapsClosedCategoriesWithoutFabricatingIntegrity() {
        String schema = "as028d_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);
        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("15"));
            Fixture fixture = insertFixture(isolated);
            UUID artifactId = UUID.randomUUID();
            isolated.update("""
                    INSERT INTO execution_artifact (
                        id, execution_id, artifact_type, file_name, storage_location,
                        content_type, size_bytes
                    ) VALUES (?, ?, 'HTML_REPORT', 'legacy.html', 'file:/legacy/report', NULL, NULL)
                    """, artifactId, fixture.executionId());
            migrate(dataSource, schema, null);
            Map<String, Object> row = isolated.queryForMap("""
                    SELECT category, logical_name, media_type, size_bytes, legacy_storage_location,
                           storage_reference, checksum, retention_reference, metadata::text metadata
                    FROM execution_artifact WHERE id = ?
                    """, artifactId);
            assertThat(row).containsEntry("category", "REPORT")
                    .containsEntry("logical_name", "legacy.html")
                    .containsEntry("media_type", "application/octet-stream")
                    .containsEntry("size_bytes", 0L)
                    .containsEntry("legacy_storage_location", "file:/legacy/report")
                    .containsEntry("storage_reference", null)
                    .containsEntry("checksum", null)
                    .containsEntry("retention_reference", null)
                    .containsEntry("metadata", "{}");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void canonicalConstraintsAcceptExtensionsAndRejectInvalidIntegrityOrDuplicateReference() {
        Fixture fixture = insertFixture(jdbcTemplate);
        UUID first = UUID.randomUUID();
        String reference = "artifact:v1:" + first;
        insertCanonical(jdbcTemplate, fixture.executionId(), first, "vendor.custom", reference);
        assertThatThrownBy(() -> insertCanonical(jdbcTemplate, fixture.executionId(),
                UUID.randomUUID(), "invalid category", "artifact:v1:" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCanonical(jdbcTemplate, fixture.executionId(),
                UUID.randomUUID(), "LOG", reference))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM execution WHERE id = ?", fixture.executionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("DELETE FROM execution_artifact WHERE execution_id = ?", fixture.executionId());
        cleanup(jdbcTemplate, fixture);
    }

    private void insertCanonical(
            JdbcTemplate target, UUID executionId, UUID artifactId, String category, String reference) {
        target.update("""
                INSERT INTO execution_artifact (
                    id, execution_id, category, logical_name, media_type, size_bytes,
                    storage_reference, checksum_algorithm, checksum, retention_reference, metadata
                ) VALUES (?, ?, ?, 'artifact.log', 'text/plain', 3, ?, 'SHA-256', ?,
                          'retain:default', '{}'::jsonb)
                """, artifactId, executionId, category, reference, "0".repeat(64));
    }

    private Fixture insertFixture(JdbcTemplate target) {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID suite = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        target.update("INSERT INTO workspace (id,name,slug,status) VALUES (?,? ,?,'ACTIVE')",
                workspace, "Artifact Workspace", "artifact-" + workspace);
        target.update("INSERT INTO project (id,workspace_id,name,status) VALUES (?,? ,?,'ACTIVE')",
                project, workspace, "Artifact Project");
        target.update("""
                INSERT INTO environment (id,project_id,name,base_url,type,status)
                VALUES (?,?,'Artifact Environment','https://example.test','TEST','ACTIVE')
                """, environment, project);
        target.update("""
                INSERT INTO test_suite (id,project_id,name,engine_type,suite_reference,status)
                VALUES (?,?,'Artifact Suite','PLAYWRIGHT','tests/artifact','ACTIVE')
                """, suite, project);
        target.update("""
                INSERT INTO execution (
                    id,project_id,environment_id,test_suite_id,selection_mode,status,requested_by)
                VALUES (?,?,?,?,'SUITE','PENDING','as-028d-test')
                """, execution, project, environment, suite);
        return new Fixture(workspace, project, environment, suite, execution);
    }

    private void cleanup(JdbcTemplate target, Fixture fixture) {
        target.update("DELETE FROM execution WHERE id = ?", fixture.executionId());
        target.update("DELETE FROM test_suite WHERE id = ?", fixture.suiteId());
        target.update("DELETE FROM environment WHERE id = ?", fixture.environmentId());
        target.update("DELETE FROM project WHERE id = ?", fixture.projectId());
        target.update("DELETE FROM workspace WHERE id = ?", fixture.workspaceId());
    }

    private DriverManagerDataSource schemaDataSource(String schema) {
        String separator = POSTGRESQL_CONTAINER.getJdbcUrl().contains("?") ? "&" : "?";
        var source = new DriverManagerDataSource();
        source.setUrl(POSTGRESQL_CONTAINER.getJdbcUrl() + separator + "currentSchema=" + schema);
        source.setUsername(POSTGRESQL_CONTAINER.getUsername());
        source.setPassword(POSTGRESQL_CONTAINER.getPassword());
        return source;
    }

    private void migrate(DriverManagerDataSource source, String schema, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema);
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private record Fixture(
            UUID workspaceId, UUID projectId, UUID environmentId, UUID suiteId, UUID executionId) { }
}
