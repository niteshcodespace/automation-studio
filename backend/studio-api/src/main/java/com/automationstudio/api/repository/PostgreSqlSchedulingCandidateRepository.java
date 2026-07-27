package com.automationstudio.api.repository;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.RunnerCapabilities;
import com.automationstudio.api.domain.SchedulingCandidate;
import com.automationstudio.api.domain.SchedulingRequirements;
import com.automationstudio.api.domain.SchedulingRequirementsParser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgreSqlSchedulingCandidateRepository
        implements SchedulingCandidateRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {};

    private static final String COMPATIBLE_CANDIDATE = """
            SELECT execution.id,
                   execution.environment_id,
                   execution.test_suite_id,
                   execution.selection_mode,
                   execution.requested_by,
                   execution.requested_at,
                   execution.environment_snapshot::text AS environment_snapshot,
                   execution.suite_snapshot::text AS suite_snapshot,
                   execution.request_snapshot::text AS request_snapshot
            FROM execution
            WHERE execution.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1
                  FROM execution_lease
                  WHERE execution_lease.execution_id = execution.id
              )
              AND execution.environment_snapshot IS NOT NULL
              AND execution.suite_snapshot IS NOT NULL
              AND execution.request_snapshot IS NOT NULL
              AND execution.environment_snapshot ->> 'id' = execution.environment_id::text
              AND jsonb_typeof(execution.environment_snapshot -> 'name') = 'string'
              AND NULLIF(BTRIM(execution.environment_snapshot ->> 'name'), '') IS NOT NULL
              AND jsonb_typeof(execution.environment_snapshot -> 'type') = 'string'
              AND NULLIF(BTRIM(execution.environment_snapshot ->> 'type'), '') IS NOT NULL
              AND jsonb_typeof(execution.environment_snapshot -> 'baseUrl') = 'string'
              AND NULLIF(BTRIM(execution.environment_snapshot ->> 'baseUrl'), '') IS NOT NULL
              AND jsonb_typeof(execution.environment_snapshot -> 'configuration') = 'object'
              AND jsonb_typeof(execution.environment_snapshot -> 'secretReferences') = 'object'
              AND execution.suite_snapshot ->> 'id' = execution.test_suite_id::text
              AND jsonb_typeof(execution.suite_snapshot -> 'name') = 'string'
              AND NULLIF(BTRIM(execution.suite_snapshot ->> 'name'), '') IS NOT NULL
              AND jsonb_typeof(execution.suite_snapshot -> 'engineType') = 'string'
              AND NULLIF(BTRIM(execution.suite_snapshot ->> 'engineType'), '') IS NOT NULL
              AND jsonb_typeof(execution.suite_snapshot -> 'engineId') = 'string'
              AND NULLIF(BTRIM(execution.suite_snapshot ->> 'engineId'), '') IS NOT NULL
              AND jsonb_typeof(execution.suite_snapshot -> 'suiteReference') = 'string'
              AND NULLIF(BTRIM(execution.suite_snapshot ->> 'suiteReference'), '') IS NOT NULL
              AND jsonb_typeof(execution.suite_snapshot -> 'configuration') = 'object'
              AND jsonb_exists(execution.suite_snapshot, 'suiteType')
              AND (
                  execution.suite_snapshot -> 'suiteType' = 'null'::jsonb
                  OR (
                      jsonb_typeof(execution.suite_snapshot -> 'suiteType') = 'string'
                      AND NULLIF(BTRIM(execution.suite_snapshot ->> 'suiteType'), '') IS NOT NULL
                  )
              )
              AND execution.request_snapshot ->> 'selectionMode' = execution.selection_mode
              AND execution.request_snapshot ->> 'requestedBy' = execution.requested_by
              AND jsonb_typeof(execution.request_snapshot -> 'testCaseIds') = 'array'
              AND NOT EXISTS (
                  SELECT 1
                  FROM jsonb_array_elements(
                      CASE
                          WHEN jsonb_typeof(
                              execution.request_snapshot -> 'testCaseIds'
                          ) = 'array'
                          THEN execution.request_snapshot -> 'testCaseIds'
                          ELSE '[]'::jsonb
                      END
                  ) AS test_case_id
                  WHERE jsonb_typeof(test_case_id) <> 'string'
                     OR BTRIM(test_case_id #>> '{}') !~
                        '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
              )
              AND CASE
                  WHEN jsonb_typeof(execution.request_snapshot -> 'requestedAt') = 'string'
                   AND execution.request_snapshot ->> 'requestedAt' ~
                       '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}(\\.[0-9]+)?)?(Z|[+-][0-9]{2}:[0-9]{2})$'
                  THEN (execution.request_snapshot ->> 'requestedAt')::timestamptz
                       = execution.requested_at
                  ELSE FALSE
              END
              AND (
                  NOT jsonb_exists(
                      execution.request_snapshot,
                      'requiredCapabilities'
                  )
                  OR jsonb_typeof(
                      execution.request_snapshot -> 'requiredCapabilities'
                  ) = 'object'
              )
              AND (
                  NOT jsonb_exists(execution.request_snapshot, 'requiredLabels')
                  OR (
                      jsonb_typeof(execution.request_snapshot -> 'requiredLabels') = 'object'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM jsonb_each(
                              CASE
                                  WHEN jsonb_typeof(
                                      execution.request_snapshot -> 'requiredLabels'
                                  ) = 'object'
                                  THEN execution.request_snapshot -> 'requiredLabels'
                                  ELSE '{}'::jsonb
                              END
                          ) AS required_label
                          WHERE jsonb_typeof(required_label.value) <> 'string'
                             OR NULLIF(BTRIM(required_label.value #>> '{}'), '') IS NULL
                      )
                  )
              )
              AND execution.suite_snapshot ->> 'engineId' IN (:runnerEngineIds)
              AND CAST(:runnerCapabilities AS jsonb) @>
                  COALESCE(
                      execution.request_snapshot -> 'requiredCapabilities',
                      '{}'::jsonb
                  )
              AND CAST(:runnerLabels AS jsonb) @>
                  COALESCE(
                      execution.request_snapshot -> 'requiredLabels',
                      '{}'::jsonb
                  )
            ORDER BY execution.requested_at ASC, execution.id ASC
            """;
    private static final String FIND_NEXT_COMPATIBLE =
            COMPATIBLE_CANDIDATE + "LIMIT 1";
    private static final String LOCK_NEXT_COMPATIBLE =
            COMPATIBLE_CANDIDATE + "FOR UPDATE OF execution SKIP LOCKED\nLIMIT 1";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SchedulingRequirementsParser parser;

    public PostgreSqlSchedulingCandidateRepository(
            NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.parser = new SchedulingRequirementsParser();
    }

    @Override
    public Optional<SchedulingCandidate> findNextCompatible(RunnerCapabilities runner) {
        return query(FIND_NEXT_COMPATIBLE, runner);
    }

    @Override
    public Optional<SchedulingCandidate> lockNextCompatible(RunnerCapabilities runner) {
        return query(LOCK_NEXT_COMPATIBLE, runner);
    }

    private Optional<SchedulingCandidate> query(
            String sql, RunnerCapabilities runner) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("runnerCapabilities", writeJson(runner.capabilities()))
                .addValue("runnerLabels", writeJson(runner.labels()))
                .addValue("runnerEngineIds", runner.engines().keySet());
        return jdbcTemplate.query(
                        sql,
                        parameters,
                        (resultSet, rowNumber) -> candidate(resultSet))
                .stream()
                .findFirst();
    }

    private SchedulingCandidate candidate(ResultSet resultSet) throws SQLException {
        UUID executionId = resultSet.getObject("id", UUID.class);
        OffsetDateTime requestedAt =
                resultSet.getObject("requested_at", OffsetDateTime.class);
        SchedulingRequirements requirements = parser.parse(
                resultSet.getObject("environment_id", UUID.class),
                resultSet.getObject("test_suite_id", UUID.class),
                ExecutionSelectionMode.valueOf(resultSet.getString("selection_mode")),
                resultSet.getString("requested_by"),
                requestedAt,
                readJson(resultSet.getString("environment_snapshot")),
                readJson(resultSet.getString("suite_snapshot")),
                readJson(resultSet.getString("request_snapshot")));
        return new SchedulingCandidate(executionId, requestedAt, requirements);
    }

    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Persisted execution snapshot is invalid", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Runner scheduling profile is invalid", exception);
        }
    }
}
