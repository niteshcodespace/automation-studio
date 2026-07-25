package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ExecutionLeaseMigrationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cleanMigrationCreatesLeaseTableConstraintsAndIndexes() {
        assertThat(POSTGRESQL_CONTAINER.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '11' AND success
                """, Integer.class)).isEqualTo(1);

        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable,
                       column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'execution_lease'
                ORDER BY ordinal_position
                """);
        assertThat(columns).hasSize(10);
        assertColumn(columns, "execution_id", "uuid", null);
        assertColumn(columns, "runner_id", "character varying", 150);
        assertColumn(columns, "claim_token", "uuid", null);
        assertColumn(columns, "lease_generation", "bigint", null);
        assertColumn(columns, "claimed_at", "timestamp with time zone", null);
        assertColumn(columns, "last_heartbeat_at", "timestamp with time zone", null);
        assertColumn(columns, "lease_expires_at", "timestamp with time zone", null);
        assertColumn(columns, "version", "bigint", null);
        assertColumn(columns, "created_at", "timestamp with time zone", null);
        assertColumn(columns, "updated_at", "timestamp with time zone", null);
        assertThat(columns).allSatisfy(column -> {
            assertThat(column.get("is_nullable")).isEqualTo("NO");
            assertThat(column.get("column_default")).isNull();
        });

        assertThat(constraintsFor("execution_lease")).contains(
                "execution_lease_pkey",
                "fk_execution_lease_execution",
                "uk_execution_lease_claim_token",
                "chk_execution_lease_runner_id",
                "chk_execution_lease_generation",
                "chk_execution_lease_version",
                "chk_execution_lease_heartbeat_order",
                "chk_execution_lease_expiry_order",
                "chk_execution_lease_audit_order");
        assertForeignKey();

        List<String> leaseIndexes = indexesFor("execution_lease");
        assertThat(leaseIndexes)
                .anyMatch(index -> index.contains("uk_execution_lease_claim_token")
                        && index.contains("(claim_token)"))
                .anyMatch(index -> index.contains("idx_execution_lease_expiry")
                        && index.contains("(lease_expires_at, execution_id)"))
                .anyMatch(index -> index.contains("idx_execution_lease_runner_expiry")
                        && index.contains("(runner_id, lease_expires_at)"));

        assertThat(indexesFor("execution"))
                .anyMatch(index -> index.contains("idx_execution_pending_queue")
                        && index.contains("(requested_at, id)")
                        && index.contains("WHERE")
                        && index.contains("status")
                        && index.contains("'PENDING'"));
    }

    @Test
    void upgradeFromV10PreservesExecutionsWithoutBackfillingLeases() {
        String schema = "as019b_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        JdbcTemplate isolated = new JdbcTemplate(dataSource);

        try {
            migrate(dataSource, schema, MigrationVersion.fromVersion("10"));
            List<UUID> executionIds = insertHistoricalExecutions(isolated);

            migrate(dataSource, schema, null);

            assertThat(isolated.queryForObject(
                    "SELECT COUNT(*) FROM execution", Integer.class))
                    .isEqualTo(executionIds.size());
            assertThat(isolated.queryForObject(
                    "SELECT COUNT(*) FROM execution_lease", Integer.class))
                    .isZero();
            assertThat(isolated.queryForList("""
                    SELECT status
                    FROM execution
                    ORDER BY requested_at, id
                    """, String.class))
                    .containsExactlyInAnyOrder(
                            "PENDING", "CLAIMED", "RUNNING", "CANCEL_REQUESTED", "PASSED");
            assertThat(isolated.queryForObject("""
                    SELECT COUNT(*)
                    FROM execution
                    WHERE status = 'CLAIMED'
                      AND NOT EXISTS (
                          SELECT 1 FROM execution_lease
                          WHERE execution_lease.execution_id = execution.id
                      )
                    """, Integer.class)).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void assertColumn(
            List<Map<String, Object>> columns,
            String name,
            String type,
            Integer maximumLength) {
        assertThat(columns)
                .filteredOn(column -> name.equals(column.get("column_name")))
                .singleElement()
                .satisfies(column -> {
                    assertThat(column.get("data_type")).isEqualTo(type);
                    assertThat(column.get("character_maximum_length")).isEqualTo(maximumLength);
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

    private void assertForeignKey() {
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
                  AND tc.table_name = 'execution_lease'
                  AND tc.constraint_name = 'fk_execution_lease_execution'
                """))
                .containsEntry("target_table", "execution")
                .containsEntry("target_column", "id")
                .containsEntry("delete_rule", "RESTRICT");
    }

    private List<String> indexesFor(String table) {
        return jdbcTemplate.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = ?
                """, String.class, table);
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

    private List<UUID> insertHistoricalExecutions(JdbcTemplate target) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        target.update("""
                INSERT INTO workspace (id, name, slug, status)
                VALUES (?, 'AS-019 Historical Workspace', ?, 'ACTIVE')
                """, workspaceId, "as019-historical-" + workspaceId);
        target.update("""
                INSERT INTO project (id, workspace_id, name, status)
                VALUES (?, ?, 'AS-019 Historical Project', 'ACTIVE')
                """, projectId, workspaceId);
        target.update("""
                INSERT INTO environment (
                    id, project_id, name, base_url, type, status
                ) VALUES (?, ?, 'AS-019 Historical Environment',
                          'https://historical.example.test', 'TEST', 'ACTIVE')
                """, environmentId, projectId);
        target.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, suite_reference, status
                ) VALUES (?, ?, 'AS-019 Historical Suite', 'PLAYWRIGHT',
                          'tests/historical', 'ACTIVE')
                """, suiteId, projectId);

        List<String> statuses =
                List.of("PENDING", "CLAIMED", "RUNNING", "CANCEL_REQUESTED", "PASSED");
        return statuses.stream().map(status -> {
            UUID executionId = UUID.randomUUID();
            target.update("""
                    INSERT INTO execution (
                        id, project_id, environment_id, test_suite_id, selection_mode,
                        status, requested_by, requested_at
                    ) VALUES (?, ?, ?, ?, 'SUITE', ?, 'historical-actor',
                              TIMESTAMPTZ '2026-07-25 10:00:00Z')
                    """, executionId, projectId, environmentId, suiteId, status);
            return executionId;
        }).toList();
    }
}
