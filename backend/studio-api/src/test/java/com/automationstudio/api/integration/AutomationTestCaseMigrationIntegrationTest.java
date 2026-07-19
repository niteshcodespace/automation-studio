package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AutomationTestCaseMigrationIntegrationTest extends IntegrationTestBase {

    private static final String TEST_ACTOR = "as-016b-migration-test";
    private static final String WORKSPACE_SLUG_PREFIX = "as-016b-migration-test-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
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
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", TEST_ACTOR);
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
    void migrationCreatesExpectedTableColumnsConstraintsAndIndex() {
        assertThat(POSTGRESQL_CONTAINER.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '8' AND success
                """, Integer.class)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name = 'automation_test_case'
                """, Integer.class)).isEqualTo(1);

        assertColumn("id", "uuid", null, "NO", null);
        assertColumn("test_suite_id", "uuid", null, "NO", null);
        assertColumn("name", "character varying", 150, "NO", null);
        assertColumn("description", "text", null, "YES", null);
        assertColumn("case_reference", "character varying", 300, "NO", null);
        assertColumn("configuration", "jsonb", null, "YES", null);
        assertColumn("status", "character varying", 30, "NO", "'ACTIVE'");
        assertColumn("position", "integer", null, "NO", null);
        assertColumn("version", "bigint", null, "NO", "0");
        assertColumn("created_at", "timestamp with time zone", null, "NO", "CURRENT_TIMESTAMP");
        assertColumn("updated_at", "timestamp with time zone", null, "NO", "CURRENT_TIMESTAMP");

        Set<String> constraints = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                    AND table_name = 'automation_test_case'
                """, String.class));
        assertThat(constraints).contains(
                "automation_test_case_pkey",
                "fk_automation_test_case_test_suite",
                "uk_automation_test_case_suite_name",
                "uk_automation_test_case_suite_reference",
                "uk_automation_test_case_suite_position",
                "chk_automation_test_case_configuration",
                "chk_automation_test_case_status",
                "chk_automation_test_case_position",
                "chk_automation_test_case_version");

        Map<String, Object> positionConstraint = jdbcTemplate.queryForMap("""
                SELECT is_deferrable, initially_deferred
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                    AND table_name = 'automation_test_case'
                    AND constraint_name = 'uk_automation_test_case_suite_position'
                """);
        assertThat(positionConstraint)
                .containsEntry("is_deferrable", "YES")
                .containsEntry("initially_deferred", "YES");

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
                    AND tc.table_name = 'automation_test_case'
                    AND tc.constraint_name = 'fk_automation_test_case_test_suite'
                """);
        assertThat(foreignKey)
                .containsEntry("target_table", "test_suite")
                .containsEntry("target_column", "id")
                .containsEntry("delete_rule", "RESTRICT");

        List<String> indexDefinitions = jdbcTemplate.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                    AND tablename = 'automation_test_case'
                """, String.class);
        assertThat(indexDefinitions)
                .anyMatch(definition -> definition.contains(
                        "idx_automation_test_case_suite_status")
                        && definition.contains("(test_suite_id, status)"));
        assertThat(indexDefinitions).hasSize(5);
    }

    @Test
    void migrationUpgradesPopulatedAs015SchemaWithoutChangingSuiteOrExecution() {
        String schema = "as016b_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);

        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("7"));
            ExistingIds existing = insertExistingAs015Data(isolated);

            String suiteBefore = isolated.queryForObject(
                    "SELECT name FROM test_suite WHERE id = ?", String.class, existing.suiteId());
            UUID executionSuiteBefore = isolated.queryForObject(
                    "SELECT test_suite_id FROM execution WHERE id = ?",
                    UUID.class, existing.executionId());

            migrate(dataSource, schema, null);

            assertThat(isolated.queryForObject(
                    "SELECT name FROM test_suite WHERE id = ?", String.class, existing.suiteId()))
                    .isEqualTo(suiteBefore);
            assertThat(isolated.queryForObject(
                    "SELECT test_suite_id FROM execution WHERE id = ?",
                    UUID.class, existing.executionId()))
                    .isEqualTo(executionSuiteBefore);
            UUID caseId = UUID.randomUUID();
            insertCase(isolated, existing.suiteId(), caseId, "Existing suite case", "case-existing", 0);
            assertThat(isolated.queryForObject(
                    "SELECT test_suite_id FROM automation_test_case WHERE id = ?",
                    UUID.class, caseId)).isEqualTo(existing.suiteId());
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void ownershipForeignKeyRestrictsMissingAndOwnedSuiteDeletion() {
        UUID projectId = insertProject();
        UUID suiteId = insertSuite(projectId, "Ownership suite");

        assertThatThrownBy(() -> insertCase(
                UUID.randomUUID(), UUID.randomUUID(), "Missing suite case", "missing-suite", 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID caseId = UUID.randomUUID();
        insertCase(suiteId, caseId, "Owned case", "owned-case", 0);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM test_suite WHERE id = ?", suiteId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_test_case WHERE id = ?",
                Integer.class, caseId)).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM automation_test_case WHERE id = ?", caseId);
        assertThat(jdbcTemplate.update("DELETE FROM test_suite WHERE id = ?", suiteId)).isOne();
    }

    @Test
    void existingExecutionRelationshipRemainsReadableAndRestrictive() {
        UUID projectId = insertProject();
        UUID suiteId = insertSuite(projectId, "Execution compatibility suite");
        UUID environmentId = insertEnvironment(projectId);
        UUID executionId = insertExecution(projectId, environmentId, suiteId);
        UUID caseId = UUID.randomUUID();
        insertCase(suiteId, caseId, "Execution compatibility case", "execution-case", 0);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT test_suite_id FROM execution WHERE id = ?", UUID.class, executionId))
                .isEqualTo(suiteId);
        jdbcTemplate.update("DELETE FROM automation_test_case WHERE id = ?", caseId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM test_suite WHERE id = ?", suiteId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT test_suite_id FROM execution WHERE id = ?", UUID.class, executionId))
                .isEqualTo(suiteId);
    }

    @Test
    void uniquenessIsSuiteScopedForNameReferenceAndPosition() {
        UUID projectId = insertProject();
        UUID firstSuite = insertSuite(projectId, "First uniqueness suite");
        UUID secondSuite = insertSuite(projectId, "Second uniqueness suite");
        insertCase(firstSuite, UUID.randomUUID(), "Shared name", "shared-reference", 0);

        assertThatThrownBy(() -> insertCase(
                firstSuite, UUID.randomUUID(), "Shared name", "different-reference", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCase(
                firstSuite, UUID.randomUUID(), "Different name", "shared-reference", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCase(
                firstSuite, UUID.randomUUID(), "Another name", "another-reference", 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertCase(secondSuite, UUID.randomUUID(), "Shared name", "shared-reference", 0);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_test_case
                WHERE name = 'Shared name'
                    AND case_reference = 'shared-reference'
                    AND position = 0
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void defaultsAndSupportedStatusesArePersisted() {
        UUID projectId = insertProject();
        UUID suiteId = insertSuite(projectId, "Defaults suite");
        UUID defaultCaseId = UUID.randomUUID();
        insertCase(suiteId, defaultCaseId, "Default case", "default-case", 0);

        Map<String, Object> defaults = jdbcTemplate.queryForMap("""
                SELECT status, version, created_at, updated_at, configuration
                FROM automation_test_case WHERE id = ?
                """, defaultCaseId);
        assertThat(defaults)
                .containsEntry("status", "ACTIVE")
                .containsEntry("version", 0L)
                .containsEntry("configuration", null);
        assertThat(defaults.get("created_at")).isNotNull();
        assertThat(defaults.get("updated_at")).isNotNull();

        insertCaseWithStatus(suiteId, UUID.randomUUID(), "Inactive case", "inactive-case", 1,
                "INACTIVE");
        insertCaseWithStatus(suiteId, UUID.randomUUID(), "Archived case", "archived-case", 2,
                "ARCHIVED");
    }

    @Test
    void checksRejectInvalidStatusNegativePositionAndNegativeVersion() {
        UUID projectId = insertProject();
        UUID suiteId = insertSuite(projectId, "Check suite");

        assertThatThrownBy(() -> insertCaseWithStatus(
                suiteId, UUID.randomUUID(), "Invalid status", "invalid-status", 0, "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCase(
                suiteId, UUID.randomUUID(), "Negative position", "negative-position", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO automation_test_case (
                    id, test_suite_id, name, case_reference, position, version
                ) VALUES (?, ?, ?, ?, 0, -1)
                """, UUID.randomUUID(), suiteId, "Negative version", "negative-version"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void configurationAcceptsNullAndObjectsButRejectsOtherJsonTypes() {
        UUID projectId = insertProject();
        UUID suiteId = insertSuite(projectId, "Configuration suite");
        insertCase(suiteId, UUID.randomUUID(), "Null configuration", "null-configuration", 0);

        List<String> objects = List.of("{}", "{\"retries\":2,\"enabled\":true}");
        int position = 1;
        for (String configuration : objects) {
            UUID caseId = UUID.randomUUID();
            insertCaseWithConfiguration(
                    suiteId, caseId, "Object configuration " + position,
                    "object-configuration-" + position, position, configuration);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT configuration = CAST(? AS jsonb)
                    FROM automation_test_case WHERE id = ?
                    """, Boolean.class, configuration, caseId)).isTrue();
            position++;
        }

        List<String> invalid = List.of("[]", "\"value\"", "42", "true", "false");
        for (String configuration : invalid) {
            int invalidPosition = position++;
            assertThatThrownBy(() -> insertCaseWithConfiguration(
                    suiteId, UUID.randomUUID(), "Invalid configuration " + invalidPosition,
                    "invalid-configuration-" + invalidPosition,
                    invalidPosition, configuration))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void deferredPositionConstraintAllowsSwapAndCommitsFinalOrder() {
        UUID projectId = insertProject();
        UUID suiteId = insertSuite(projectId, "Swap suite");
        UUID firstCase = UUID.randomUUID();
        UUID secondCase = UUID.randomUUID();
        insertCase(suiteId, firstCase, "First swap case", "first-swap", 0);
        insertCase(suiteId, secondCase, "Second swap case", "second-swap", 1);

        inTransaction(connection -> {
            updatePosition(connection, firstCase, 1);
            updatePosition(connection, secondCase, 0);
        });

        assertThat(readPositions(suiteId)).containsExactly(
                Map.entry(secondCase, 0),
                Map.entry(firstCase, 1));
    }

    @Test
    void deferredPositionConstraintRejectsDuplicateFinalPositionAtCommit() {
        UUID projectId = insertProject();
        UUID suiteId = insertSuite(projectId, "Duplicate final position suite");
        UUID firstCase = UUID.randomUUID();
        UUID secondCase = UUID.randomUUID();
        insertCase(suiteId, firstCase, "First duplicate case", "first-duplicate", 0);
        insertCase(suiteId, secondCase, "Second duplicate case", "second-duplicate", 1);

        assertThatThrownBy(() -> inTransaction(connection ->
                updatePosition(connection, secondCase, 0)))
                .isInstanceOf(DataAccessException.class);

        assertThat(readPositions(suiteId)).containsExactly(
                Map.entry(firstCase, 0),
                Map.entry(secondCase, 1));
    }

    private void assertColumn(
            String columnName,
            String dataType,
            Integer maximumLength,
            String nullable,
            String defaultFragment) {
        ColumnMetadata metadata = jdbcTemplate.queryForObject("""
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                    AND table_name = 'automation_test_case'
                    AND column_name = ?
                """, (resultSet, rowNumber) -> new ColumnMetadata(
                resultSet.getString("data_type"),
                (Integer) resultSet.getObject("character_maximum_length"),
                resultSet.getString("is_nullable"),
                resultSet.getString("column_default")), columnName);
        assertThat(metadata.dataType()).isEqualTo(dataType);
        assertThat(metadata.maximumLength()).isEqualTo(maximumLength);
        assertThat(metadata.nullable()).isEqualTo(nullable);
        if (defaultFragment == null) {
            assertThat(metadata.defaultValue()).isNull();
        } else {
            assertThat(metadata.defaultValue()).contains(defaultFragment);
        }
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
            DriverManagerDataSource dataSource,
            String schema,
            MigrationVersion target) {
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

    private ExistingIds insertExistingAs015Data(JdbcTemplate target) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        target.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'Existing Workspace', ?, 'ACTIVE')
                """, workspaceId, "existing-" + workspaceId);
        target.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'Existing Project', 'ACTIVE')
                """, projectId, workspaceId);
        target.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, 'Existing Suite', 'PLAYWRIGHT', 'tests/existing', 'ACTIVE')
                """, suiteId, projectId);
        target.update("""
                INSERT INTO environment (id, project_id, name, base_url, status)
                VALUES (?, ?, 'Existing Environment', 'https://example.test', 'ACTIVE')
                """, environmentId, projectId);
        target.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING', 'existing-actor')
                """, executionId, projectId, environmentId, suiteId);
        return new ExistingIds(suiteId, executionId);
    }

    private UUID insertProject() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String suffix = workspaceId.toString();
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, workspaceId, "AS-016B Workspace " + suffix,
                WORKSPACE_SLUG_PREFIX + suffix);
        jdbcTemplate.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, projectId, workspaceId, "AS-016B Project " + suffix);
        return projectId;
    }

    private UUID insertSuite(UUID projectId, String name) {
        UUID suiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, ?, 'PLAYWRIGHT', ?, 'ACTIVE')
                """, suiteId, projectId, name, "tests/" + suiteId);
        return suiteId;
    }

    private UUID insertEnvironment(UUID projectId) {
        UUID environmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, status)
                VALUES (?, ?, ?, 'https://example.test', 'ACTIVE')
                """, environmentId, projectId, "AS-016B Environment " + environmentId);
        return environmentId;
    }

    private UUID insertExecution(UUID projectId, UUID environmentId, UUID suiteId) {
        UUID executionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, status, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING', ?)
                """, executionId, projectId, environmentId, suiteId, TEST_ACTOR);
        return executionId;
    }

    private void insertCase(
            UUID suiteId,
            UUID caseId,
            String name,
            String caseReference,
            int position) {
        insertCase(jdbcTemplate, suiteId, caseId, name, caseReference, position);
    }

    private void insertCase(
            JdbcTemplate target,
            UUID suiteId,
            UUID caseId,
            String name,
            String caseReference,
            int position) {
        target.update("""
                INSERT INTO automation_test_case (
                    id, test_suite_id, name, case_reference, position
                ) VALUES (?, ?, ?, ?, ?)
                """, caseId, suiteId, name, caseReference, position);
    }

    private void insertCaseWithStatus(
            UUID suiteId,
            UUID caseId,
            String name,
            String caseReference,
            int position,
            String status) {
        jdbcTemplate.update("""
                INSERT INTO automation_test_case (
                    id, test_suite_id, name, case_reference, position, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, caseId, suiteId, name, caseReference, position, status);
    }

    private void insertCaseWithConfiguration(
            UUID suiteId,
            UUID caseId,
            String name,
            String caseReference,
            int position,
            String configuration) {
        jdbcTemplate.update("""
                INSERT INTO automation_test_case (
                    id, test_suite_id, name, case_reference, position, configuration
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, caseId, suiteId, name, caseReference, position, configuration);
    }

    private void inTransaction(SqlWork work) {
        jdbcTemplate.execute((Connection connection) -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.execute(connection);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
            return null;
        });
    }

    private void updatePosition(Connection connection, UUID caseId, int position)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE automation_test_case SET position = ? WHERE id = ?")) {
            statement.setInt(1, position);
            statement.setObject(2, caseId);
            statement.executeUpdate();
        }
    }

    private List<Map.Entry<UUID, Integer>> readPositions(UUID suiteId) {
        return jdbcTemplate.query("""
                SELECT id, position
                FROM automation_test_case
                WHERE test_suite_id = ?
                ORDER BY position
                """, (resultSet, rowNumber) -> Map.entry(
                resultSet.getObject("id", UUID.class),
                resultSet.getInt("position")), suiteId);
    }

    @FunctionalInterface
    private interface SqlWork {
        void execute(Connection connection) throws SQLException;
    }

    private record ColumnMetadata(
            String dataType,
            Integer maximumLength,
            String nullable,
            String defaultValue) {
    }

    private record ExistingIds(UUID suiteId, UUID executionId) {
    }
}
