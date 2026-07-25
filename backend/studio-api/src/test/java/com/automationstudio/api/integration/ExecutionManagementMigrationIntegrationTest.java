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

class ExecutionManagementMigrationIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-018b-migration-test";
    private static final String WORKSPACE_SLUG_PREFIX = "as-018b-migration-test-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution_test_case
                WHERE execution_id IN (
                    SELECT id FROM execution WHERE requested_by = ?
                )
                """, TEST_ACTOR);
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
        jdbcTemplate.update("""
                DELETE FROM automation_test_case
                WHERE test_suite_id IN (
                    SELECT test_suite.id
                    FROM test_suite
                    JOIN project ON project.id = test_suite.project_id
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
                DELETE FROM test_suite
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
                    SELECT id FROM workspace WHERE slug LIKE ?
                )
                """, WORKSPACE_SLUG_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE slug LIKE ?", WORKSPACE_SLUG_PREFIX + "%");
    }

    @Test
    void migrationCreatesExecutionColumnsConstraintsAndSelectionTable() {
        assertThat(POSTGRESQL_CONTAINER.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '10' AND success
                """, Integer.class)).isEqualTo(1);

        assertExecutionColumn("selection_mode", "character varying", 30, "NO", null);
        assertExecutionColumn("environment_snapshot", "jsonb", null, "YES", null);
        assertExecutionColumn("suite_snapshot", "jsonb", null, "YES", null);
        assertExecutionColumn("request_snapshot", "jsonb", null, "YES", null);
        assertExecutionColumn(
                "cancel_requested_at", "timestamp with time zone", null, "YES", null);
        assertExecutionColumn("cancelled_at", "timestamp with time zone", null, "YES", null);
        assertExecutionColumn("cancelled_by", "character varying", 150, "YES", null);
        assertExecutionColumn("cancellation_reason", "character varying", 1000, "YES", null);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'execution_test_case'
                """, Integer.class)).isEqualTo(1);
        assertChildColumn("id", "uuid", null, "NO", null);
        assertChildColumn("execution_id", "uuid", null, "NO", null);
        assertChildColumn("automation_test_case_id", "uuid", null, "NO", null);
        assertChildColumn("sequence_number", "integer", null, "NO", null);
        assertChildColumn("test_case_snapshot", "jsonb", null, "YES", null);
        assertChildColumn(
                "created_at", "timestamp with time zone", null, "NO", "CURRENT_TIMESTAMP");

        Set<String> executionConstraints = constraintsFor("execution");
        assertThat(executionConstraints).contains(
                "chk_execution_status",
                "chk_execution_selection_mode",
                "chk_execution_environment_snapshot",
                "chk_execution_suite_snapshot",
                "chk_execution_request_snapshot",
                "chk_execution_cancellation_time_order");

        Set<String> childConstraints = constraintsFor("execution_test_case");
        assertThat(childConstraints).contains(
                "execution_test_case_pkey",
                "fk_execution_test_case_execution",
                "fk_execution_test_case_automation_test_case",
                "uk_execution_test_case_execution_case",
                "uk_execution_test_case_execution_sequence",
                "chk_execution_test_case_sequence_number",
                "chk_execution_test_case_snapshot");

        assertForeignKey(
                "fk_execution_test_case_execution", "execution", "id", "RESTRICT");
        assertForeignKey(
                "fk_execution_test_case_automation_test_case",
                "automation_test_case", "id", "RESTRICT");

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'execution_test_case'
                """, String.class);
        assertThat(indexes)
                .anyMatch(index -> index.contains("uk_execution_test_case_execution_case")
                        && index.contains("(execution_id, automation_test_case_id)"))
                .anyMatch(index -> index.contains("uk_execution_test_case_execution_sequence")
                        && index.contains("(execution_id, sequence_number)"))
                .anyMatch(index -> index.contains(
                        "idx_execution_test_case_automation_test_case_id")
                        && index.contains("(automation_test_case_id)"));
    }

    @Test
    void migrationBackfillsPopulatedV9SchemaWithoutFabricatingSnapshots() {
        String schema = "as018b_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);

        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("9"));
            UUID executionId = insertHistoricalExecution(isolated);

            migrate(dataSource, schema, null);

            Map<String, Object> migrated = isolated.queryForMap("""
                    SELECT selection_mode, environment_snapshot, suite_snapshot, request_snapshot,
                           status, requested_by
                    FROM execution
                    WHERE id = ?
                    """, executionId);
            assertThat(migrated)
                    .containsEntry("selection_mode", "SUITE")
                    .containsEntry("environment_snapshot", null)
                    .containsEntry("suite_snapshot", null)
                    .containsEntry("request_snapshot", null)
                    .containsEntry("status", "PENDING")
                    .containsEntry("requested_by", "historical-actor");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void executionChecksAcceptNewValuesAndRejectInvalidValuesOrJsonShapes() {
        Fixture fixture = insertFixture();
        UUID executionId = insertExecution(fixture, "TEST_CASES", "CANCEL_REQUESTED");

        assertThat(jdbcTemplate.queryForMap("""
                SELECT selection_mode, status
                FROM execution WHERE id = ?
                """, executionId))
                .containsEntry("selection_mode", "TEST_CASES")
                .containsEntry("status", "CANCEL_REQUESTED");

        assertThatThrownBy(() -> insertExecution(fixture, "UNKNOWN", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecution(fixture, "SUITE", "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING', ?)
                """, UUID.randomUUID(), fixture.projectId(), fixture.environmentId(),
                fixture.suiteId(), TEST_ACTOR))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE execution
                SET request_snapshot = CAST('[]' AS jsonb)
                WHERE id = ?
                """, executionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancellationAndSelectionConstraintsRejectInvalidRowsAndProtectHistory() {
        Fixture fixture = insertFixture();
        UUID executionId = insertExecution(fixture, "TEST_CASES", "PENDING");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE execution
                SET cancel_requested_at = TIMESTAMPTZ '2026-07-25 10:00:00Z',
                    cancelled_at = TIMESTAMPTZ '2026-07-25 09:59:59Z'
                WHERE id = ?
                """, executionId)).isInstanceOf(DataIntegrityViolationException.class);

        UUID selectionId = insertSelection(executionId, fixture.testCaseId(), 0);
        assertThatThrownBy(() -> insertSelection(
                executionId, fixture.testCaseId(), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID otherCase = insertAutomationTestCase(fixture.suiteId(), 1);
        assertThatThrownBy(() -> insertSelection(executionId, otherCase, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSelection(executionId, otherCase, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE execution_test_case
                SET test_case_snapshot = CAST('[]' AS jsonb)
                WHERE id = ?
                """, selectionId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSelection(
                UUID.randomUUID(), otherCase, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSelection(
                executionId, UUID.randomUUID(), 2))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM execution WHERE id = ?", executionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM automation_test_case WHERE id = ?", fixture.testCaseId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM execution_test_case WHERE id = ?
                """, Integer.class, selectionId)).isOne();
    }

    private void assertExecutionColumn(
            String name, String type, Integer length, String nullable, String defaultFragment) {
        assertColumn("execution", name, type, length, nullable, defaultFragment);
    }

    private void assertChildColumn(
            String name, String type, Integer length, String nullable, String defaultFragment) {
        assertColumn("execution_test_case", name, type, length, nullable, defaultFragment);
    }

    private void assertColumn(
            String table,
            String name,
            String type,
            Integer length,
            String nullable,
            String defaultFragment) {
        ColumnMetadata metadata = jdbcTemplate.queryForObject("""
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, (resultSet, rowNumber) -> new ColumnMetadata(
                resultSet.getString("data_type"),
                (Integer) resultSet.getObject("character_maximum_length"),
                resultSet.getString("is_nullable"),
                resultSet.getString("column_default")), table, name);
        assertThat(metadata.dataType()).isEqualTo(type);
        assertThat(metadata.maximumLength()).isEqualTo(length);
        assertThat(metadata.nullable()).isEqualTo(nullable);
        if (defaultFragment == null) {
            assertThat(metadata.defaultValue()).isNull();
        } else {
            assertThat(metadata.defaultValue()).contains(defaultFragment);
        }
    }

    private Set<String> constraintsFor(String table) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                """, String.class, table));
    }

    private void assertForeignKey(
            String constraint, String targetTable, String targetColumn, String deleteRule) {
        Map<String, Object> foreignKey = jdbcTemplate.queryForMap("""
                SELECT ccu.table_name AS target_table,
                       ccu.column_name AS target_column,
                       rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.referential_constraints rc
                  ON rc.constraint_schema = tc.constraint_schema
                 AND rc.constraint_name = tc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = tc.constraint_schema
                 AND ccu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = 'execution_test_case'
                  AND tc.constraint_name = ?
                """, constraint);
        assertThat(foreignKey)
                .containsEntry("target_table", targetTable)
                .containsEntry("target_column", targetColumn)
                .containsEntry("delete_rule", deleteRule);
    }

    private Fixture insertFixture() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-018B Migration Workspace " + workspaceId,
                WORKSPACE_SLUG_PREFIX + workspaceId);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-018B Migration Project " + workspaceId);
        jdbcTemplate.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, ?, 'https://example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId, "AS-018B Environment " + workspaceId);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, "AS-018B Suite " + workspaceId, "tests/" + suiteId);
        UUID testCaseId = insertAutomationTestCase(suiteId, 0);
        return new Fixture(projectId, environmentId, suiteId, testCaseId);
    }

    private UUID insertAutomationTestCase(UUID suiteId, int position) {
        UUID testCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO automation_test_case (
                    id, test_suite_id, name, case_reference, position
                ) VALUES (?, ?, ?, ?, ?)
                """, testCaseId, suiteId, "AS-018B Case " + testCaseId,
                "case-" + testCaseId, position);
        return testCaseId;
    }

    private UUID insertExecution(Fixture fixture, String selectionMode, String status) {
        UUID executionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status,
                    selection_mode, requested_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, executionId, fixture.projectId(), fixture.environmentId(),
                fixture.suiteId(), status, selectionMode, TEST_ACTOR);
        return executionId;
    }

    private UUID insertSelection(UUID executionId, UUID testCaseId, int sequence) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO execution_test_case (
                    id, execution_id, automation_test_case_id, sequence_number,
                    test_case_snapshot
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                """, id, executionId, testCaseId, sequence,
                "{\"name\":\"Selected case\"}");
        return id;
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

    private UUID insertHistoricalExecution(JdbcTemplate target) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        target.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'Historical Workspace', ?, 'ACTIVE')
                """, workspaceId, "historical-" + workspaceId);
        target.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'Historical Project', 'ACTIVE')
                """, projectId, workspaceId);
        target.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, 'Historical Environment', 'https://historical.example.test',
                          'TEST', 'ACTIVE')
                """, environmentId, projectId);
        target.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, 'Historical Suite', 'PLAYWRIGHT', 'tests/historical', 'ACTIVE')
                """, suiteId, projectId);
        target.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING', 'historical-actor')
                """, executionId, projectId, environmentId, suiteId);
        return executionId;
    }

    private record ColumnMetadata(
            String dataType, Integer maximumLength, String nullable, String defaultValue) {
    }

    private record Fixture(
            UUID projectId, UUID environmentId, UUID suiteId, UUID testCaseId) {
    }
}
