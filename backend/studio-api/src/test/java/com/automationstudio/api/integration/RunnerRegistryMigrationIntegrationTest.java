package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RunnerRegistryMigrationIntegrationTest extends IntegrationTestBase {

    private static final OffsetDateTime REGISTERED_AT =
            OffsetDateTime.parse("2026-07-26T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cleanMigrationCreatesRunnerTablesColumnsConstraintsAndIndexes() {
        assertThat(POSTGRESQL_CONTAINER.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '12' AND success
                """, Integer.class)).isEqualTo(1);

        List<Map<String, Object>> runnerColumns = columnsFor("runner");
        assertThat(runnerColumns).hasSize(17);
        assertColumn(runnerColumns, "id", "uuid", null, false);
        assertColumn(runnerColumns, "runner_key", "character varying", 150, false);
        assertColumn(runnerColumns, "name", "character varying", 100, false);
        assertColumn(runnerColumns, "description", "character varying", 1000, true);
        assertColumn(runnerColumns, "agent_version", "character varying", 100, false);
        assertColumn(runnerColumns, "hostname", "character varying", 255, false);
        assertColumn(runnerColumns, "operating_system", "character varying", 100, false);
        assertColumn(runnerColumns, "architecture", "character varying", 50, false);
        assertColumn(runnerColumns, "max_concurrency", "integer", null, false);
        assertColumn(runnerColumns, "capabilities", "jsonb", null, false);
        assertColumn(runnerColumns, "labels", "jsonb", null, false);
        assertColumn(runnerColumns, "status", "character varying", 30, false);
        assertColumn(runnerColumns, "registered_at", "timestamp with time zone", null, false);
        assertColumn(
                runnerColumns, "last_registered_at", "timestamp with time zone", null, false);
        assertColumn(runnerColumns, "version", "bigint", null, false);
        assertColumn(runnerColumns, "created_at", "timestamp with time zone", null, false);
        assertColumn(runnerColumns, "updated_at", "timestamp with time zone", null, false);
        assertThat(runnerColumns).allSatisfy(
                column -> assertThat(column.get("column_default")).isNull());

        List<Map<String, Object>> runtimeColumns = columnsFor("runner_runtime");
        assertThat(runtimeColumns).hasSize(6);
        assertColumn(runtimeColumns, "runner_id", "uuid", null, false);
        assertColumn(runtimeColumns, "last_seen_at", "timestamp with time zone", null, false);
        assertColumn(runtimeColumns, "heartbeat_count", "bigint", null, false);
        assertColumn(runtimeColumns, "version", "bigint", null, false);
        assertColumn(runtimeColumns, "created_at", "timestamp with time zone", null, false);
        assertColumn(runtimeColumns, "updated_at", "timestamp with time zone", null, false);
        assertThat(runtimeColumns).allSatisfy(
                column -> assertThat(column.get("column_default")).isNull());

        assertThat(constraintsFor("runner")).contains(
                "runner_pkey",
                "uk_runner_runner_key",
                "chk_runner_runner_key",
                "chk_runner_name",
                "chk_runner_agent_version",
                "chk_runner_hostname",
                "chk_runner_operating_system",
                "chk_runner_architecture",
                "chk_runner_max_concurrency",
                "chk_runner_capabilities_object",
                "chk_runner_capabilities_size",
                "chk_runner_labels_object",
                "chk_runner_labels_size",
                "chk_runner_status",
                "chk_runner_version",
                "chk_runner_registration_order",
                "chk_runner_audit_order");
        assertThat(constraintsFor("runner_runtime")).contains(
                "runner_runtime_pkey",
                "fk_runner_runtime_runner",
                "chk_runner_runtime_heartbeat_count",
                "chk_runner_runtime_version",
                "chk_runner_runtime_audit_order");

        assertThat(indexesFor("runner"))
                .anyMatch(index -> index.contains("uk_runner_runner_key")
                        && index.contains("(runner_key)"))
                .anyMatch(index -> index.contains("idx_runner_status_name")
                        && index.contains("(status, name, id)"))
                .anyMatch(index -> index.contains("idx_runner_capabilities_gin")
                        && index.contains("USING gin (capabilities)"))
                .anyMatch(index -> index.contains("idx_runner_labels_gin")
                        && index.contains("USING gin (labels)"));
        assertThat(indexesFor("runner_runtime"))
                .anyMatch(index -> index.contains("idx_runner_runtime_last_seen")
                        && index.contains("(last_seen_at, runner_id)"));

        assertRuntimeForeignKey();
    }

    @Test
    void runnerConstraintsRejectInvalidIdentityLifecycleJsonConcurrencyAndTime() {
        UUID validId = insertRunner(
                jdbcTemplate, "runner-valid", "ACTIVE", 4, "{}", "{}", 0,
                REGISTERED_AT, REGISTERED_AT, REGISTERED_AT, REGISTERED_AT);
        insertRunner(
                jdbcTemplate, "runner-retired", "DEREGISTERED", 1, "{}", "{}", 0,
                REGISTERED_AT, REGISTERED_AT, REGISTERED_AT, REGISTERED_AT);

        assertInsertRunnerFails("runner-valid", "ACTIVE", 1, "{}", "{}");
        assertInsertRunnerFails("runner-retired", "ACTIVE", 1, "{}", "{}");
        assertInsertRunnerFails("Runner-Uppercase", "ACTIVE", 1, "{}", "{}");
        assertInsertRunnerFails("-invalid-prefix", "ACTIVE", 1, "{}", "{}");
        assertInsertRunnerFails("runner invalid", "ACTIVE", 1, "{}", "{}");
        assertInsertRunnerFails("runner-status", "ONLINE", 1, "{}", "{}");
        assertInsertRunnerFails("runner-zero-capacity", "ACTIVE", 0, "{}", "{}");
        assertInsertRunnerFails("runner-high-capacity", "ACTIVE", 1001, "{}", "{}");
        assertInsertRunnerFails("runner-array-capabilities", "ACTIVE", 1, "[]", "{}");
        assertInsertRunnerFails("runner-array-labels", "ACTIVE", 1, "{}", "[]");

        String oversizedJson = "{\"value\":\"" + "x".repeat(65_536) + "\"}";
        assertInsertRunnerFails("runner-large-capabilities", "ACTIVE", 1, oversizedJson, "{}");
        assertInsertRunnerFails("runner-large-labels", "ACTIVE", 1, "{}", oversizedJson);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE runner
                SET version = -1
                WHERE id = ?
                """, validId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE runner
                SET last_registered_at = registered_at - INTERVAL '1 second'
                WHERE id = ?
                """, validId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE runner
                SET updated_at = created_at - INTERVAL '1 second'
                WHERE id = ?
                """, validId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runnerRuntimeIsOneToOneRestrictiveAndVersioned() {
        UUID runnerId = insertRunner(
                jdbcTemplate, "runner-runtime", "DISABLED", 1, "{}", "{}", 0,
                REGISTERED_AT, REGISTERED_AT, REGISTERED_AT, REGISTERED_AT);
        insertRuntime(jdbcTemplate, runnerId, 0, 0);

        assertThatThrownBy(() -> insertRuntime(jdbcTemplate, runnerId, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRuntime(jdbcTemplate, UUID.randomUUID(), 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM runner WHERE id = ?", runnerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE runner_runtime SET heartbeat_count = -1 WHERE runner_id = ?
                """, runnerId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE runner_runtime SET version = -1 WHERE runner_id = ?
                """, runnerId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE runner_runtime
                SET updated_at = created_at - INTERVAL '1 second'
                WHERE runner_id = ?
                """, runnerId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradeFromV11PreservesHistoricalExecutionLeaseWithoutRunnerBackfill() {
        String schema = "as020b_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);

        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("11"));
            UUID executionId = insertHistoricalExecutionLease(isolated);

            migrate(dataSource, schema, null);

            assertThat(isolated.queryForObject(
                    "SELECT COUNT(*) FROM runner", Integer.class)).isZero();
            assertThat(isolated.queryForObject(
                    "SELECT COUNT(*) FROM runner_runtime", Integer.class)).isZero();
            assertThat(isolated.queryForMap("""
                    SELECT runner_id, lease_generation, version
                    FROM execution_lease
                    WHERE execution_id = ?
                    """, executionId))
                    .containsEntry("runner_id", "historical-runner-without-registry")
                    .containsEntry("lease_generation", 1L)
                    .containsEntry("version", 0L);
            assertThat(foreignKeyTargetsFor("execution_lease", schema))
                    .doesNotContain("runner");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private List<Map<String, Object>> columnsFor(String table) {
        return jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable,
                       column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                ORDER BY ordinal_position
                """, table);
    }

    private void assertColumn(
            List<Map<String, Object>> columns,
            String name,
            String type,
            Integer maximumLength,
            boolean nullable) {
        assertThat(columns)
                .filteredOn(column -> name.equals(column.get("column_name")))
                .singleElement()
                .satisfies(column -> {
                    assertThat(column.get("data_type")).isEqualTo(type);
                    assertThat(column.get("character_maximum_length")).isEqualTo(maximumLength);
                    assertThat(column.get("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
                });
    }

    private Set<String> constraintsFor(String table) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                """, String.class, table));
    }

    private List<String> indexesFor(String table) {
        return jdbcTemplate.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = ?
                """, String.class, table);
    }

    private void assertRuntimeForeignKey() {
        assertThat(jdbcTemplate.queryForMap("""
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
                  AND tc.table_name = 'runner_runtime'
                  AND tc.constraint_name = 'fk_runner_runtime_runner'
                """))
                .containsEntry("target_table", "runner")
                .containsEntry("target_column", "id")
                .containsEntry("delete_rule", "RESTRICT");
    }

    private void assertInsertRunnerFails(
            String runnerKey,
            String status,
            int maxConcurrency,
            String capabilities,
            String labels) {
        assertThatThrownBy(() -> insertRunner(
                jdbcTemplate, runnerKey, status, maxConcurrency, capabilities, labels, 0,
                REGISTERED_AT, REGISTERED_AT, REGISTERED_AT, REGISTERED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertRunner(
            JdbcTemplate target,
            String runnerKey,
            String status,
            int maxConcurrency,
            String capabilities,
            String labels,
            long version,
            OffsetDateTime registeredAt,
            OffsetDateTime lastRegisteredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        target.update("""
                INSERT INTO runner (
                    id, runner_key, name, description, agent_version, hostname,
                    operating_system, architecture, max_concurrency, capabilities,
                    labels, status, registered_at, last_registered_at, version,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, 'Runner', NULL, '1.0.0', 'runner.example.test',
                    'linux', 'amd64', ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?
                )
                """, id, runnerKey, maxConcurrency, capabilities, labels, status,
                registeredAt, lastRegisteredAt, version, createdAt, updatedAt);
        return id;
    }

    private void insertRuntime(
            JdbcTemplate target, UUID runnerId, long heartbeatCount, long version) {
        target.update("""
                INSERT INTO runner_runtime (
                    runner_id, last_seen_at, heartbeat_count, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, runnerId, REGISTERED_AT, heartbeatCount, version,
                REGISTERED_AT, REGISTERED_AT);
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

    private UUID insertHistoricalExecutionLease(JdbcTemplate target) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        target.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'AS-020 Historical Workspace', ?, 'ACTIVE')
                """, workspaceId, "as020-historical-" + workspaceId);
        target.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'AS-020 Historical Project', 'ACTIVE')
                """, projectId, workspaceId);
        target.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, 'AS-020 Historical Environment',
                          'https://historical.example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        target.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, 'AS-020 Historical Suite', 'PLAYWRIGHT',
                          'tests/historical', 'ACTIVE')
                """, suiteId, projectId);
        target.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at
                ) VALUES (?, ?, ?, ?, 'SUITE', 'CLAIMED', 'historical-actor', ?)
                """, executionId, projectId, environmentId, suiteId, REGISTERED_AT);
        target.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at
                ) VALUES (
                    ?, 'historical-runner-without-registry', ?, 1,
                    ?, ?, ?, 0, ?, ?
                )
                """, executionId, UUID.randomUUID(), REGISTERED_AT, REGISTERED_AT,
                REGISTERED_AT.plusMinutes(5), REGISTERED_AT, REGISTERED_AT);
        return executionId;
    }

    private List<String> foreignKeyTargetsFor(String table, String schema) {
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        return new JdbcTemplate(dataSource).queryForList("""
                SELECT ccu.table_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.referential_constraints rc
                  ON rc.constraint_schema = tc.constraint_schema
                 AND rc.constraint_name = tc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = tc.constraint_schema
                 AND ccu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = ?
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                """, String.class, schema, table);
    }
}
