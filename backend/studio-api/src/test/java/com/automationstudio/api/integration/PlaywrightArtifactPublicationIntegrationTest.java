package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataService;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageLimits;
import com.automationstudio.api.execution.artifact.storage.StorageBackedArtifactPublisher;
import com.automationstudio.api.execution.artifact.storage.local.LocalArtifactStorage;
import com.automationstudio.api.execution.engine.playwright.PlaywrightExecutionEngine;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightOrderedScenarioRunner;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightScenarioExecutionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.SelectorResolver;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationParser;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenario;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifest;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntime;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeMetrics;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeResult;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeSession;
import com.automationstudio.api.security.SensitiveKeyDetector;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.PreparedSourceAccess;
import com.automationstudio.engine.sdk.WorkspaceAccess;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PlaywrightArtifactPublicationIntegrationTest extends IntegrationTestBase {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T08:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;
    @Autowired ArtifactMetadataService metadataService;
    @Autowired JdbcTemplate jdbcTemplate;
    private UUID fixtureExecutionId;

    @AfterEach
    void cleanup() {
        if (fixtureExecutionId != null) {
            jdbcTemplate.update("DELETE FROM execution_artifact WHERE execution_id = ?",
                    fixtureExecutionId);
        }
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = 'as-028f-proof'");
        jdbcTemplate.update("DELETE FROM test_suite WHERE name LIKE 'AS-028F Suite%'");
        jdbcTemplate.update("DELETE FROM environment WHERE name LIKE 'AS-028F Environment%'");
        jdbcTemplate.update("DELETE FROM project WHERE name LIKE 'AS-028F Project%'");
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE 'as-028f-%'");
    }

    @Test
    void publishesFailureReportThroughDurableStorageAndScopedDiscovery() throws Exception {
        Fixture fixture = insertFixture();
        Path workspace = temporaryDirectory.resolve("workspace");
        Path durableRoot = temporaryDirectory.resolve("durable-artifacts");
        Files.createDirectories(workspace);
        Files.createDirectories(durableRoot);
        LocalArtifactStorage storage = new LocalArtifactStorage(durableRoot, CLOCK);
        var publisher = new StorageBackedArtifactPublisher(
                fixture.executionId(), storage,
                new ArtifactStorageLimits(durableRoot.toString(), 4096, 8192, 4, 1),
                fixture.workspaceId(), fixture.projectId(), metadataService, "retain:default");

        PlaywrightScenarioManifestLoader loader = mock(PlaywrightScenarioManifestLoader.class);
        when(loader.load(any(String.class), any(PreparedSourceAccess.class))).thenReturn(
                new PlaywrightScenarioManifest("1.0", "Proof",
                        List.of(new PlaywrightScenario("proof", "Proof", List.of(
                                new PlaywrightStep("navigate", PlaywrightActionType.NAVIGATE,
                                        null, "/", null, null, Duration.ofSeconds(1)))))));
        PlaywrightRuntime runtime = mock(PlaywrightRuntime.class);
        PlaywrightRuntimeSession session = mock(PlaywrightRuntimeSession.class);
        when(runtime.open(any())).thenReturn(session);
        when(session.result()).thenReturn(new PlaywrightRuntimeResult(
                PlaywrightRuntimeMetrics.startup(Duration.ZERO)));
        PlaywrightOrderedScenarioRunner runner = mock(PlaywrightOrderedScenarioRunner.class);
        PlaywrightRuntimeMetrics metrics = new PlaywrightRuntimeMetrics(
                3, 2, 1, Duration.ofSeconds(1), Duration.ZERO);
        when(runner.execute(any(), any(), any())).thenReturn(new PlaywrightScenarioExecutionOutcome(
                PlaywrightScenarioExecutionOutcome.Status.ASSERTION_FAILED,
                PlaywrightActionOutcome.assertionFailed("proof", "assert", "ASSERTION_MISMATCH"),
                metrics));

        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        PreparedSourceAccess sourceAccess = mock(PreparedSourceAccess.class);
        when(sourceAccess.workspaceId()).thenReturn(workspaceId);
        WorkspaceAccess workspaceAccess = mock(WorkspaceAccess.class);
        when(workspaceAccess.executionId()).thenReturn(fixture.executionId());
        when(workspaceAccess.workspaceId()).thenReturn(workspaceId);
        when(workspaceAccess.openPreparedSource()).thenReturn(sourceAccess);
        ExecutionSecretAccess secrets = mock(ExecutionSecretAccess.class);
        when(secrets.executionId()).thenReturn(fixture.executionId());
        EngineExecutionContext context = new EngineExecutionContext(
                fixture.executionId(), new EngineIdentity("playwright-java", "1.61.0"),
                "scenario.json", Map.of("captureFailureReport", true),
                "https://example.test", Map.of(), Map.of());
        EngineExecutionRequest request = new EngineExecutionRequest(
                context, new PreparedSource(workspaceId, "GIT_HTTPS", "revision"),
                workspaceAccess, secrets, publisher);
        PlaywrightExecutionEngine engine = new PlaywrightExecutionEngine(
                new PlaywrightConfigurationParser(new SensitiveKeyDetector()), workspaceRequest -> {
                    throw new AssertionError("Legacy workspace path must not be used");
                }, loader, runtime, runner, mock(SelectorResolver.class), CLOCK);

        assertThat(engine.execute(request).state().name()).isEqualTo("FAILED");
        publisher.complete();
        Files.delete(workspace);

        var discovered = metadataService.list(
                fixture.workspaceId(), fixture.projectId(), fixture.executionId());
        assertThat(discovered).singleElement().satisfies(artifact -> {
            assertThat(artifact.category()).isEqualTo("REPORT");
            assertThat(artifact.logicalName()).isEqualTo("execution-failure-report.json");
            assertThat(artifact.mediaType()).isEqualTo("application/json");
            assertThat(artifact.metadata()).containsEntry(
                    "capturePolicy", "assertion-failure-only");
            assertThat(artifact.sizeBytes()).isPositive();
            assertThat(artifact.checksumAlgorithm()).isEqualTo("SHA-256");
        });
        assertThat(workspace).doesNotExist();
        assertThat(storage.verify(new com.automationstudio.api.execution.artifact.storage.StoredArtifact(
                new com.automationstudio.api.execution.artifact.storage.ArtifactStorageReference(
                        discovered.getFirst().storageReference()),
                discovered.getFirst().sizeBytes(), discovered.getFirst().checksumAlgorithm(),
                discovered.getFirst().checksum(), discovered.getFirst().createdAt().toInstant())))
                .isTrue();
    }

    private Fixture insertFixture() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID suite = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        fixtureExecutionId = execution;
        jdbcTemplate.update("INSERT INTO workspace (id,name,slug,status) VALUES (?,?,?,'ACTIVE')",
                workspace, "AS-028F Workspace", "as-028f-" + workspace);
        jdbcTemplate.update("INSERT INTO project (id,workspace_id,name,status) VALUES (?,?,?,'ACTIVE')",
                project, workspace, "AS-028F Project " + project);
        jdbcTemplate.update("INSERT INTO environment (id,project_id,name,base_url,type,status) VALUES (?,?,?,'https://example.test','TEST','ACTIVE')",
                environment, project, "AS-028F Environment " + environment);
        jdbcTemplate.update("INSERT INTO test_suite (id,project_id,name,engine_type,suite_reference,status) VALUES (?,?,?,'PLAYWRIGHT',?,'ACTIVE')",
                suite, project, "AS-028F Suite " + suite, "scenario.json");
        jdbcTemplate.update("INSERT INTO execution (id,project_id,environment_id,test_suite_id,selection_mode,status,requested_by) VALUES (?,?,?,?,'SUITE','PENDING','as-028f-proof')",
                execution, project, environment, suite);
        return new Fixture(workspace, project, execution);
    }

    private record Fixture(UUID workspaceId, UUID projectId, UUID executionId) { }
}
