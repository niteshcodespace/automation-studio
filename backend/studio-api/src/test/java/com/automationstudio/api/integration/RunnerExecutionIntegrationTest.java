package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.builtin.BuiltinExecutionEngine;
import com.automationstudio.api.execution.evidence.ExecutionArtifact;
import com.automationstudio.api.execution.evidence.ExecutionArtifactReference;
import com.automationstudio.api.execution.evidence.ExecutionArtifactType;
import com.automationstudio.api.execution.evidence.ExecutionEvidence;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceSummary;
import com.automationstudio.api.execution.lifecycle.ExecutionFailureReason;
import com.automationstudio.api.execution.lifecycle.ExecutionLifecycleService;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;
import com.automationstudio.api.execution.lifecycle.ExecutionTerminationReason;
import com.automationstudio.api.execution.orchestration.ExecutionOwnershipException;
import com.automationstudio.api.execution.orchestration.RunnerExecutionException;
import com.automationstudio.api.execution.orchestration.RunnerExecutionRequest;
import com.automationstudio.api.execution.orchestration.RunnerExecutionService;
import java.sql.Timestamp;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Import(RunnerExecutionIntegrationTest.EngineConfiguration.class)
class RunnerExecutionIntegrationTest extends IntegrationTestBase {

    private static final String PREFIX = "as022d-";

    @Autowired private RunnerExecutionService executionService;
    @Autowired private ExecutionLifecycleService lifecycleService;
    @Autowired private ExecutionEngineRegistry engineRegistry;
    @Autowired private BuiltinExecutionEngine builtinExecutionEngine;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM execution_lease WHERE runner_id LIKE ?
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = ?", PREFIX + "test");
        jdbcTemplate.update("DELETE FROM runner WHERE runner_key LIKE ?", PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM environment
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM test_suite
                WHERE project_id IN (
                    SELECT project.id FROM project
                    JOIN workspace ON workspace.id = project.workspace_id
                    WHERE workspace.slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("""
                DELETE FROM project
                WHERE workspace_id IN (
                    SELECT id FROM workspace WHERE slug LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE ?", PREFIX + "%");
    }

    @Test
    void performsFencedStartAndPreparesCompletionWithoutAnotherTransition() {
        Fixture fixture = insertFixture("5 minutes");

        var started = executionService.start(request(fixture));

        assertThat(started.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(started.context().executionId()).isEqualTo(fixture.executionId());
        assertThat(started.engineDescriptor().engineName()).isEqualTo("test-engine");
        assertThat(status(fixture.executionId())).isEqualTo("RUNNING");
        assertThat(startedAt(fixture.executionId())).isNotNull();

        var completion = executionService.prepareCompletion(new RunnerExecutionRequest(
                fixture.executionId(),
                fixture.runnerKey(),
                fixture.claimToken(),
                1,
                fixture.leaseVersion(),
                started.executionVersion()));

        assertThat(completion.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(status(fixture.executionId())).isEqualTo("RUNNING");
        assertThat(artifactCount(fixture.executionId())).isZero();
    }

    @Test
    void executesProviderNeutralLifecycleToPassedState() {
        Fixture fixture = insertFixture("5 minutes");

        ExecutionResult result = lifecycleService.execute(request(fixture));

        assertThat(result.status())
                .isEqualTo(com.automationstudio.api.execution.lifecycle.ExecutionStatus.SUCCEEDED);
        assertThat(result.executionId()).isEqualTo(fixture.executionId());
        assertThat(result.evidence().artifacts()).hasSize(1);
        assertThat(result.evidence().summary().artifactCount()).isOne();
        assertThat(status(fixture.executionId())).isEqualTo("PASSED");
        assertThat(artifactCount(fixture.executionId())).isZero();
    }

    @Test
    void executesProductionBuiltinEngineToPassedWithNormalizedEvidence() {
        Fixture fixture = insertBuiltinFixture("SUCCEED", true, "approved message");

        ExecutionResult result = lifecycleService.execute(request(fixture));

        assertThat(engineRegistry.resolve("BUILTIN", "1.0.0").engine())
                .isSameAs(builtinExecutionEngine);
        assertThat(result.status())
                .isEqualTo(com.automationstudio.api.execution.lifecycle.ExecutionStatus.SUCCEEDED);
        assertThat(result.executionId()).isEqualTo(fixture.executionId());
        assertThat(result.metadata()).containsEntry("engine", "BUILTIN");
        assertThat(result.metadata()).containsEntry("message", "approved message");
        assertThat(result.evidence().artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.reference().uri().getScheme()).isEqualTo("builtin");
            assertThat(artifact.reference().uri().getUserInfo()).isNull();
        });
        assertThat(result.evidence().summary().duration()).isEqualTo(result.duration());
        assertThat(status(fixture.executionId())).isEqualTo("PASSED");
        assertThat(startedAt(fixture.executionId())).isNotNull();
        assertThat(finishedAt(fixture.executionId())).isNotNull();
        assertThat(executionVersion(fixture.executionId()))
                .isGreaterThan(fixture.executionVersion());
        assertThat(artifactCount(fixture.executionId())).isZero();
        assertThat(result.toString()).doesNotContain(fixture.claimToken().toString());
        assertThat(persistedError(fixture.executionId())).isNull();
    }

    @Test
    void executesProductionBuiltinEngineToProviderDeclaredFailure() {
        Fixture fixture = insertBuiltinFixture("FAIL", false, "safe failure");

        ExecutionResult result = lifecycleService.execute(request(fixture));

        assertThat(result.status())
                .isEqualTo(com.automationstudio.api.execution.lifecycle.ExecutionStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(
                ExecutionFailureReason.ENGINE_REPORTED_FAILURE);
        assertThat(result.evidence().artifacts()).isEmpty();
        assertThat(status(fixture.executionId())).isEqualTo("FAILED");
        assertThat(artifactCount(fixture.executionId())).isZero();
        assertThat(result.toString()).doesNotContain(fixture.claimToken().toString());
        assertThat(persistedError(fixture.executionId())).isNull();
    }

    @Test
    void rejectsInvalidBuiltinConfigurationBeforeLifecycleMutation() {
        Fixture fixture = insertBuiltinFixture("UNSUPPORTED", true, null);

        Throwable failure = catchThrowable(() -> lifecycleService.execute(request(fixture)));

        assertThat(failure)
                .isInstanceOf(com.automationstudio.api.execution.engine.builtin
                        .BuiltinExecutionEngineException.class);
        assertThat(status(fixture.executionId())).isEqualTo("CLAIMED");
        assertThat(startedAt(fixture.executionId())).isNull();
        assertThat(artifactCount(fixture.executionId())).isZero();
    }

    @Test
    void rejectsExpiredGenerationRunnerAndOptimisticVersionFencesWithoutMutation() {
        Fixture fixture = insertFixture("-1 second");
        assertThat(catchThrowable(() -> executionService.start(request(fixture))))
                .isInstanceOf(ExecutionOwnershipException.class);
        assertThat(status(fixture.executionId())).isEqualTo("CLAIMED");

        Fixture current = insertFixture("5 minutes");
        List<RunnerExecutionRequest> invalid = List.of(
                new RunnerExecutionRequest(
                        current.executionId(), "wrong", current.claimToken(),
                        1, current.leaseVersion(), current.executionVersion()),
                new RunnerExecutionRequest(
                        current.executionId(), current.runnerKey(), current.claimToken(),
                        2, current.leaseVersion(), current.executionVersion()),
                new RunnerExecutionRequest(
                        current.executionId(), current.runnerKey(), current.claimToken(),
                        1, current.leaseVersion() + 1, current.executionVersion()),
                new RunnerExecutionRequest(
                        current.executionId(), current.runnerKey(), current.claimToken(),
                        1, current.leaseVersion(), current.executionVersion() + 1));
        invalid.forEach(request -> assertThat(
                catchThrowable(() -> executionService.start(request)))
                .isInstanceOf(ExecutionOwnershipException.class));
        assertThat(status(current.executionId())).isEqualTo("CLAIMED");
    }

    @Test
    void concurrentStartsHaveExactlyOneWinner() throws Exception {
        Fixture fixture = insertFixture("5 minutes");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return catchThrowable(() -> executionService.start(request(fixture)));
            });
            var second = executor.submit(() -> {
                start.await();
                return catchThrowable(() -> executionService.start(request(fixture)));
            });
            start.countDown();
            List<Throwable> results = java.util.Arrays.asList(first.get(), second.get());

            assertThat(results).filteredOn(result -> result == null).hasSize(1);
            assertThat(results).filteredOn(RunnerExecutionException.class::isInstance)
                    .hasSize(1);
        }
        assertThat(status(fixture.executionId())).isEqualTo("RUNNING");
    }

    private Fixture insertFixture(String expiryInterval) {
        return insertFixture(
                expiryInterval,
                "test-engine",
                "1",
                "PLAYWRIGHT",
                Map.of());
    }

    private Fixture insertBuiltinFixture(
            String operation, boolean evidenceEnabled, String message) {
        Map<String, Object> configuration = new java.util.LinkedHashMap<>();
        configuration.put("operation", operation);
        configuration.put("evidence", Map.of("enabled", evidenceEnabled));
        if (message != null) {
            configuration.put("message", message);
        }
        return insertFixture(
                "5 minutes",
                "BUILTIN",
                "1.0.0",
                "BUILTIN",
                configuration);
    }

    private Fixture insertFixture(
            String expiryInterval,
            String engineId,
            String engineVersion,
            String engineType,
            Map<String, Object> engineConfiguration) {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID suite = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        UUID runner = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        String runnerKey = PREFIX + runner;
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, slug, status) VALUES (?, 'AS-022D', ?, 'ACTIVE')",
                workspace, PREFIX + workspace);
        jdbcTemplate.update(
                "INSERT INTO project (id, workspace_id, name, status) "
                        + "VALUES (?, ?, 'AS-022D', 'ACTIVE')",
                project, workspace);
        jdbcTemplate.update("""
                INSERT INTO environment (id, project_id, name, base_url, type, status)
                VALUES (?, ?, 'QA', 'https://example.test', 'TEST', 'ACTIVE')
                """, environment, project);
        jdbcTemplate.update("""
                INSERT INTO test_suite (
                    id, project_id, name, engine_type, engine_id, suite_reference, status)
                VALUES (?, ?, 'Smoke', ?, ?, 'tests/smoke', 'ACTIVE')
                """, suite, project, engineType, engineId);
        jdbcTemplate.update("""
                INSERT INTO runner (
                    id, runner_key, name, agent_version, hostname,
                    operating_system, architecture, max_concurrency,
                    capabilities, labels, status, registered_at, last_registered_at,
                    version, created_at, updated_at)
                VALUES (?, ?, 'AS-022D', '1.0', 'runner.test',
                    'linux', 'amd64', 1, ?::jsonb, '{}'::jsonb, 'ACTIVE',
                    clock_timestamp(), clock_timestamp(), 0,
                    clock_timestamp(), clock_timestamp())
                """, runner, runnerKey,
                json(Map.of("engines", Map.of(engineId, engineVersion))));
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id, project_id, environment_id, test_suite_id, selection_mode,
                    status, requested_by, requested_at,
                    environment_snapshot, suite_snapshot, request_snapshot)
                VALUES (?, ?, ?, ?, 'SUITE', 'CLAIMED', ?, clock_timestamp(),
                    ?::jsonb, ?::jsonb, '{}'::jsonb)
                """, execution, project, environment, suite, PREFIX + "test",
                json(Map.of(
                        "id", environment.toString(),
                        "name", "QA",
                        "type", "TEST",
                        "baseUrl", "https://example.test",
                        "configuration", Map.of(),
                        "secretReferences", Map.of())),
                json(Map.of(
                        "id", suite.toString(),
                        "name", "Smoke",
                        "engineType", engineType,
                        "engineId", engineId,
                        "suiteReference", "tests/smoke",
                        "configuration", engineConfiguration)));
        String insertedExpiry = expiryInterval.startsWith("-")
                ? "5 minutes"
                : expiryInterval;
        jdbcTemplate.update("""
                INSERT INTO execution_lease (
                    execution_id, runner_id, claim_token, lease_generation,
                    claimed_at, last_heartbeat_at, lease_expires_at, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, clock_timestamp(), clock_timestamp(),
                    clock_timestamp() + CAST(? AS INTERVAL), 0,
                    clock_timestamp(), clock_timestamp())
                """, execution, runnerKey, token, insertedExpiry);
        if (expiryInterval.startsWith("-")) {
            jdbcTemplate.update("""
                    UPDATE execution_lease
                    SET claimed_at = clock_timestamp() - INTERVAL '2 minutes',
                        last_heartbeat_at = clock_timestamp() - INTERVAL '2 minutes',
                        lease_expires_at = clock_timestamp() - INTERVAL '1 minute'
                    WHERE execution_id = ?
                    """, execution);
        }
        Map<String, Object> executionRow = jdbcTemplate.queryForMap(
                "SELECT version FROM execution WHERE id = ?", execution);
        Map<String, Object> leaseRow = jdbcTemplate.queryForMap(
                "SELECT version FROM execution_lease WHERE execution_id = ?", execution);
        return new Fixture(
                execution,
                runnerKey,
                token,
                ((Number) executionRow.get("version")).longValue(),
                ((Number) leaseRow.get("version")).longValue());
    }

    private RunnerExecutionRequest request(Fixture fixture) {
        return new RunnerExecutionRequest(
                fixture.executionId(),
                fixture.runnerKey(),
                fixture.claimToken(),
                1,
                fixture.leaseVersion(),
                fixture.executionVersion());
    }

    private String status(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM execution WHERE id = ?", String.class, executionId);
    }

    private Timestamp startedAt(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT started_at FROM execution WHERE id = ?", Timestamp.class, executionId);
    }

    private Timestamp finishedAt(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT finished_at FROM execution WHERE id = ?", Timestamp.class, executionId);
    }

    private long executionVersion(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM execution WHERE id = ?", Long.class, executionId);
    }

    private String persistedError(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT error_message FROM execution WHERE id = ?", String.class, executionId);
    }

    private int artifactCount(UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_artifact WHERE execution_id = ?",
                Integer.class,
                executionId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            UUID executionId,
            String runnerKey,
            UUID claimToken,
            long executionVersion,
            long leaseVersion) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EngineConfiguration {

        @Bean
        ExecutionEngine testExecutionEngine() {
            return new ExecutionEngine() {
                @Override
                public ExecutionEngineDescriptor descriptor() {
                    return new ExecutionEngineDescriptor(
                            "test-engine", "1", "Test Engine", Set.of(), Set.of());
                }

                @Override
                public void validate(ExecutionContext context) {
                }

                @Override
                public ExecutionResult execute(ExecutionContext context) {
                    OffsetDateTime startedAt = OffsetDateTime.now();
                    OffsetDateTime finishedAt = startedAt.plusNanos(1_000_000);
                    return new ExecutionResult(
                            context.executionId(),
                            context.runner().runnerId(),
                            com.automationstudio.api.execution.lifecycle.ExecutionStatus.SUCCEEDED,
                            startedAt,
                            finishedAt,
                            Duration.between(startedAt, finishedAt),
                            ExecutionTerminationReason.COMPLETED,
                            ExecutionFailureReason.NONE,
                            Map.of("engine", "test-engine"),
                            evidence(context, finishedAt, Duration.between(
                                    startedAt, finishedAt)));
                }

                private ExecutionEvidence evidence(
                        ExecutionContext context,
                        OffsetDateTime capturedAt,
                        Duration duration) {
                    ExecutionArtifact artifact = new ExecutionArtifact(
                            UUID.randomUUID(),
                            ExecutionArtifactType.REPORT,
                            "test-report.xml",
                            "application/xml",
                            128,
                            new ExecutionArtifactReference(
                                    URI.create("artifact://test-runner/test-report.xml"),
                                    "test-runner",
                                    "sha256:test",
                                    null),
                            Map.of("provider", "fake"));
                    return new ExecutionEvidence(
                            context.executionId(),
                            context.runner().runnerId(),
                            capturedAt,
                            List.of(artifact),
                            Map.of("source", "fake-engine"),
                            new ExecutionEvidenceSummary(1, 0, 0, duration));
                }
            };
        }
    }
}
