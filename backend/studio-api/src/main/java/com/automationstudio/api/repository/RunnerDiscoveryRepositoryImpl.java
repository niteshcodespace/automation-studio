package com.automationstudio.api.repository;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.service.query.RunnerQueryFilter;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RunnerDiscoveryRepositoryImpl implements RunnerDiscoveryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "runner.name",
            "runnerKey", "runner.runner_key",
            "status", "runner.status",
            "registeredAt", "runner.registered_at",
            "lastRegisteredAt", "runner.last_registered_at",
            "lastSeenAt", "runtime.last_seen_at",
            "heartbeatCount", "runtime.heartbeat_count",
            "id", "runner.id");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RunnerDiscoveryRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Page<UUID> findRunnerIds(
            RunnerQueryFilter filter,
            OffsetDateTime evaluatedAt,
            Duration onlineThreshold,
            Duration offlineThreshold,
            Pageable pageable) {
        QueryParts query = queryParts(
                filter, evaluatedAt, onlineThreshold, offlineThreshold);
        String fromAndWhere = """
                FROM runner
                JOIN runner_runtime runtime ON runtime.runner_id = runner.id
                """ + query.whereClause();
        String selectSql = "SELECT runner.id " + fromAndWhere
                + orderBy(pageable.getSort(), query.healthSortExpression())
                + " LIMIT :limit OFFSET :offset";
        query.parameters()
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        List<UUID> ids = jdbcTemplate.query(
                selectSql,
                query.parameters(),
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) " + fromAndWhere,
                query.parameters(),
                Long.class);
        return new PageImpl<>(ids, pageable, total == null ? 0 : total);
    }

    private QueryParts queryParts(
            RunnerQueryFilter filter,
            OffsetDateTime evaluatedAt,
            Duration onlineThreshold,
            Duration offlineThreshold) {
        List<String> predicates = new ArrayList<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("onlineCutoff", Timestamp.from(
                        evaluatedAt.minus(onlineThreshold).toInstant()))
                .addValue("offlineCutoff", Timestamp.from(
                        evaluatedAt.minus(offlineThreshold).toInstant()));

        if (filter.status() != null) {
            predicates.add("runner.status = :status");
            parameters.addValue("status", filter.status().name());
        }
        if (filter.health() != null) {
            predicates.add(healthPredicate(filter.health()));
        }
        if (filter.available() != null) {
            String availability = """
                    runner.status = 'ACTIVE'
                    AND runtime.last_seen_at >= :onlineCutoff
                    AND runner.max_concurrency > 0
                    """;
            predicates.add(filter.available()
                    ? "(" + availability + ")"
                    : "NOT (" + availability + ")");
        }
        if (filter.capability() != null) {
            predicates.add("jsonb_exists(runner.capabilities -> 'engines', :capability)");
            parameters.addValue("capability", filter.capability());
        }
        if (filter.label() != null) {
            predicates.add("""
                    EXISTS (
                        SELECT 1
                        FROM jsonb_each_text(runner.labels) AS runner_label
                        WHERE runner_label.value = :label
                    )
                    """);
            parameters.addValue("label", filter.label());
        }

        String whereClause = predicates.isEmpty()
                ? ""
                : " WHERE " + String.join(" AND ", predicates);
        return new QueryParts(whereClause, parameters, healthSortExpression());
    }

    private String healthPredicate(RunnerHealth health) {
        return switch (health) {
            case ONLINE -> "runtime.last_seen_at >= :onlineCutoff";
            case STALE -> """
                    runtime.last_seen_at < :onlineCutoff
                    AND runtime.last_seen_at >= :offlineCutoff
                    """;
            case OFFLINE -> "runtime.last_seen_at < :offlineCutoff";
        };
    }

    private String healthSortExpression() {
        return """
                CASE
                    WHEN runtime.last_seen_at >= :onlineCutoff THEN 0
                    WHEN runtime.last_seen_at >= :offlineCutoff THEN 1
                    ELSE 2
                END
                """.strip();
    }

    private String orderBy(Sort sort, String healthSortExpression) {
        List<String> orders = new ArrayList<>();
        sort.forEach(order -> {
            String expression = "health".equals(order.getProperty())
                    ? healthSortExpression
                    : SORT_COLUMNS.get(order.getProperty());
            orders.add(expression + " " + order.getDirection().name());
        });
        if (orders.stream().noneMatch(order -> order.startsWith("runner.id "))) {
            orders.add("runner.id ASC");
        }
        return " ORDER BY " + String.join(", ", orders);
    }

    private record QueryParts(
            String whereClause,
            MapSqlParameterSource parameters,
            String healthSortExpression) {
    }
}
