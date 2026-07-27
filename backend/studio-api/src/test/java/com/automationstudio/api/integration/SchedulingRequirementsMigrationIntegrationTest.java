package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchedulingRequirementsMigrationIntegrationTest extends IntegrationTestBase {

    private static final OffsetDateTime REQUESTED_AT =
            OffsetDateTime.parse("2026-07-27T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cleanMigrationAddsImmutableSnapshotTriggerAndCompatibleQueueIndex() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '13' AND success
                """, Integer.class)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT event_manipulation, action_timing, action_orientation
                FROM information_schema.triggers
                WHERE trigger_schema = 'public'
                  AND event_object_table = 'execution'
                  AND trigger_name = 'trg_execution_snapshots_immutable'
                """))
                .containsEntry("event_manipulation", "UPDATE")
                .containsEntry("action_timing", "BEFORE")
                .containsEntry("action_orientation", "ROW");

        assertThat(indexesFor(jdbcTemplate, "public", "execution"))
                .filteredOn(index -> index.contains("idx_execution_pending_engine_queue"))
                .singleElement()
                .satisfies(index -> assertThat(index)
                        .contains("suite_snapshot ->> 'engineId'::text")
                        .contains("requested_at, id")
                        .contains("status")
                        .contains("'PENDING'::text")
                        .contains("NULLIF(btrim"));
    }

    @Test
    void snapshotsAreImmutableWhileLifecycleUpdatesRemainAllowed() {
        UUID executionId = insertExecution(
                jdbcTemplate,
                "immutable",
                """
                {"engineId":"playwright-java","name":"Suite at admission"}
                """,
                """
                {"selectionMode":"SUITE"}
                """);

        assertSnapshotUpdateFails(
                executionId, "suite_snapshot", "{\"engineId\":\"selenium-java\"}");
        assertSnapshotUpdateFails(
                executionId, "request_snapshot", "{\"selectionMode\":\"TEST_CASES\"}");
        assertSnapshotUpdateFails(
                executionId, "environment_snapshot", "{\"name\":\"Changed\"}");

        assertThat(jdbcTemplate.update("""
                UPDATE execution
                SET status = 'CLAIMED'
                WHERE id = ?
                """, executionId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM execution WHERE id = ?", String.class, executionId))
                .isEqualTo("CLAIMED");
    }

    @Test
    void upgradeFromV12PreservesHistoricalSnapshotsAndAddsProtection() {
        String schema = "as021b_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);

        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("12"));
            UUID executionId = insertExecution(
                    isolated,
                    "historical",
                    """
                    {"engineId":"playwright-java","name":"Historical suite"}
                    """,
                    """
                    {"selectionMode":"SUITE","requestedBy":"historical"}
                    """);
            Map<String, Object> before = snapshotValues(isolated, executionId);

            migrate(dataSource, schema, null);

            assertThat(snapshotValues(isolated, executionId)).isEqualTo(before);
            assertThat(indexesFor(isolated, schema, "execution"))
                    .anyMatch(index -> index.contains("idx_execution_pending_engine_queue"));
            assertThatThrownBy(() -> isolated.update("""
                    UPDATE execution
                    SET suite_snapshot = '{"engineId":"changed"}'::jsonb
                    WHERE id = ?
                    """, executionId)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void assertSnapshotUpdateFails(UUID executionId, String column, String json) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE execution SET " + column + " = ?::jsonb WHERE id = ?",
                json,
                executionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertExecution(
            JdbcTemplate target, String suffix, String suiteSnapshot, String requestSnapshot) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        target.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'AS-021 Workspace', ?, 'ACTIVE')
                """, workspaceId, "as021-" + suffix + "-" + workspaceId);
        target.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'AS-021 Project', 'ACTIVE')
                """, projectId, workspaceId);
        target.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'AS-021 Environment',
                        'https://as021.example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        target.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, engine_id, suite_reference, status
                ) VALUES (
                    ?, ?, 'AS-021 Suite', 'PLAYWRIGHT', 'playwright-java',
                    'tests/as021', 'ACTIVE'
                )
                """, suiteId, projectId);
        target.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at, environment_snapshot,
                    suite_snapshot, request_snapshot
                ) VALUES (
                    ?, ?, ?, ?, 'SUITE', 'PENDING', 'as-021b-test', ?,
                    '{"name":"Environment at admission"}'::jsonb, ?::jsonb, ?::jsonb
                )
                """, executionId, projectId, environmentId, suiteId, REQUESTED_AT,
                suiteSnapshot, requestSnapshot);
        return executionId;
    }

    private Map<String, Object> snapshotValues(JdbcTemplate target, UUID executionId) {
        return target.queryForMap("""
                SELECT environment_snapshot::text AS environment_snapshot,
                       suite_snapshot::text AS suite_snapshot,
                       request_snapshot::text AS request_snapshot
                FROM execution
                WHERE id = ?
                """, executionId);
    }

    private List<String> indexesFor(JdbcTemplate target, String schema, String table) {
        return target.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = ?
                  AND tablename = ?
                """, String.class, schema, table);
    }

    private DriverManagerDataSource schemaDataSource(String schema) {
        String separator = POSTGRESQL_CONTAINER.getJdbcUrl().contains("?") ? "&" : "?";
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(
                POSTGRESQL_CONTAINER.getJdbcUrl() + separator + "currentSchema=" + schema);
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
}
