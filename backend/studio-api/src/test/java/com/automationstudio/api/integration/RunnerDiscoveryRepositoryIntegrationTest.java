package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.repository.RunnerDiscoveryRepository;
import com.automationstudio.api.service.RunnerRegistrationService;
import com.automationstudio.api.service.command.RegisterRunnerCommand;
import com.automationstudio.api.service.query.RunnerQueryFilter;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class RunnerDiscoveryRepositoryIntegrationTest extends IntegrationTestBase {

    private static final String PREFIX = "as-020g-";
    private static final OffsetDateTime EVALUATED_AT =
            OffsetDateTime.parse("2026-07-26T10:00:00Z");
    private static final Duration ONLINE = Duration.ofMinutes(1);
    private static final Duration OFFLINE = Duration.ofMinutes(5);

    @Autowired
    private RunnerDiscoveryRepository discoveryRepository;
    @Autowired
    private RunnerRegistrationService registrationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM runner_runtime
                WHERE runner_id IN (SELECT id FROM runner WHERE runner_key LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM runner WHERE runner_key LIKE ?", PREFIX + "%");
    }

    @Test
    void combinesStatusHealthAvailabilityEngineAndExactLabelFilters() {
        Runner matching = runner(
                "matching", "Alpha", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusSeconds(30));
        runner(
                "stale", "Beta", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusMinutes(2));
        runner(
                "disabled", "Gamma", "playwright-java", "linux",
                RunnerStatus.DISABLED, EVALUATED_AT.minusSeconds(30));
        runner(
                "other-engine", "Delta", "selenium-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusSeconds(30));

        var page = discoveryRepository.findRunnerIds(
                new RunnerQueryFilter(
                        RunnerStatus.ACTIVE,
                        RunnerHealth.ONLINE,
                        true,
                        "playwright-java",
                        "linux"),
                EVALUATED_AT,
                ONLINE,
                OFFLINE,
                PageRequest.of(0, 20, Sort.by("name")));

        assertThat(page.getContent()).containsExactly(matching.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(discoveryRepository.findRunnerIds(
                        new RunnerQueryFilter(
                                null, null, null, "missing-engine", null),
                        EVALUATED_AT,
                        ONLINE,
                        OFFLINE,
                        PageRequest.of(0, 20, Sort.by("id"))))
                .isEmpty();
    }

    @Test
    void appliesExactHealthBoundariesAndFalseAvailability() {
        Runner online = runner(
                "online-boundary", "Online", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minus(ONLINE));
        Runner stale = runner(
                "stale-boundary", "Stale", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minus(OFFLINE));
        Runner offline = runner(
                "offline", "Offline", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minus(OFFLINE).minusNanos(1_000));

        assertThat(ids(RunnerHealth.ONLINE)).contains(online.getId());
        assertThat(ids(RunnerHealth.STALE)).contains(stale.getId());
        assertThat(ids(RunnerHealth.OFFLINE)).contains(offline.getId());
        assertThat(discoveryRepository.findRunnerIds(
                        new RunnerQueryFilter(null, null, false, null, null),
                        EVALUATED_AT,
                        ONLINE,
                        OFFLINE,
                        PageRequest.of(0, 20, Sort.by("id")))
                .getContent()).contains(stale.getId(), offline.getId());
    }

    @Test
    void sortsRuntimeColumnsAndPaginatesWithStableIdTieBreaker() {
        Runner oldest = runner(
                "oldest", "Same", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusMinutes(4));
        Runner middle = runner(
                "middle", "Same", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusMinutes(2));
        Runner newest = runner(
                "newest", "Same", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusSeconds(20));

        var first = discoveryRepository.findRunnerIds(
                emptyFilter(),
                EVALUATED_AT,
                ONLINE,
                OFFLINE,
                PageRequest.of(0, 2, Sort.by(Sort.Order.desc("lastSeenAt"))));
        var second = discoveryRepository.findRunnerIds(
                emptyFilter(),
                EVALUATED_AT,
                ONLINE,
                OFFLINE,
                PageRequest.of(1, 2, Sort.by(Sort.Order.desc("lastSeenAt"))));

        assertThat(first.getContent()).containsExactly(newest.getId(), middle.getId());
        assertThat(second.getContent()).containsExactly(oldest.getId());
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void sortsByDerivedHealthAndHeartbeatCountInBothDirections() {
        Runner online = runner(
                "sort-online", "Online", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusSeconds(20));
        Runner stale = runner(
                "sort-stale", "Stale", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusMinutes(2));
        Runner offline = runner(
                "sort-offline", "Offline", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusMinutes(6));
        jdbcTemplate.update(
                "UPDATE runner_runtime SET heartbeat_count = 1 WHERE runner_id = ?",
                online.getId());
        jdbcTemplate.update(
                "UPDATE runner_runtime SET heartbeat_count = 2 WHERE runner_id = ?",
                stale.getId());
        jdbcTemplate.update(
                "UPDATE runner_runtime SET heartbeat_count = 3 WHERE runner_id = ?",
                offline.getId());

        assertThat(discoveryRepository.findRunnerIds(
                        emptyFilter(), EVALUATED_AT, ONLINE, OFFLINE,
                        PageRequest.of(0, 20, Sort.by(Sort.Order.asc("health"))))
                .getContent()).containsExactly(
                        online.getId(), stale.getId(), offline.getId());
        assertThat(discoveryRepository.findRunnerIds(
                        emptyFilter(), EVALUATED_AT, ONLINE, OFFLINE,
                        PageRequest.of(
                                0, 20, Sort.by(Sort.Order.desc("heartbeatCount"))))
                .getContent()).containsExactly(
                        offline.getId(), stale.getId(), online.getId());
    }

    @Test
    void discoveryApiReturnsFilteredPageAndStructuredValidationErrors() throws Exception {
        Runner matching = runner(
                "api-match", "API Match", "playwright-java", "linux",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusSeconds(30));
        runner(
                "api-other", "API Other", "selenium-java", "windows",
                RunnerStatus.ACTIVE, EVALUATED_AT.minusSeconds(30));
        jdbcTemplate.update(
                "UPDATE runner_runtime SET last_seen_at = clock_timestamp() "
                        + "WHERE runner_id IN (?, ?)",
                matching.getId(),
                jdbcTemplate.queryForObject(
                        "SELECT id FROM runner WHERE runner_key = ?",
                        UUID.class,
                        PREFIX + "api-other"));

        mockMvc.perform(get("/api/v1/runners")
                        .param("status", "ACTIVE")
                        .param("health", "ONLINE")
                        .param("available", "true")
                        .param("capability", "playwright-java")
                        .param("label", "linux")
                        .param("sort", "lastSeenAt")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id")
                        .value(matching.getId().toString()));

        mockMvc.perform(get("/api/v1/runners").param("direction", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Runner sort direction must be asc or desc"));
        mockMvc.perform(get("/api/v1/runners").param("sort", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Unsupported runner sort field: unknown"));
    }

    private java.util.List<UUID> ids(RunnerHealth health) {
        return discoveryRepository.findRunnerIds(
                        new RunnerQueryFilter(null, health, null, null, null),
                        EVALUATED_AT,
                        ONLINE,
                        OFFLINE,
                        PageRequest.of(0, 20, Sort.by("id")))
                .getContent();
    }

    private Runner runner(
            String suffix,
            String name,
            String engineId,
            String osLabel,
            RunnerStatus status,
            OffsetDateTime lastSeenAt) {
        Runner runner = registrationService.register(new RegisterRunnerCommand(
                PREFIX + suffix,
                name,
                null,
                "1.0.0",
                suffix + ".internal",
                "linux",
                "amd64",
                4,
                Map.of("engines", Map.of(engineId, "1.0")),
                Map.of("os", osLabel)));
        if (status != RunnerStatus.ACTIVE) {
            jdbcTemplate.update(
                    "UPDATE runner SET status = ? WHERE id = ?",
                    status.name(),
                    runner.getId());
        }
        jdbcTemplate.update(
                "UPDATE runner_runtime SET last_seen_at = ? WHERE runner_id = ?",
                lastSeenAt,
                runner.getId());
        return runner;
    }

    private RunnerQueryFilter emptyFilter() {
        return new RunnerQueryFilter(null, null, null, null, null);
    }
}
