package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionEnvironmentSnapshot;
import com.automationstudio.api.execution.ExecutionMetadata;
import com.automationstudio.api.execution.ExecutionRetryPolicy;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.EngineExecutionState;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl;
import com.automationstudio.api.execution.preparation.SourcePreparationRequest;
import com.automationstudio.api.execution.secret.ExecutionSecretProviderRegistry;
import com.automationstudio.api.execution.secret.ExecutionSecretScopeFactory;
import com.automationstudio.api.execution.preparation.SourcePreparationServiceImpl;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceProvider;
import com.automationstudio.api.execution.workspace.local.WorkspaceRootProperties;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import com.automationstudio.api.source.materialization.git.GitMaterializationProperties;
import com.automationstudio.api.source.materialization.git.GitSourceMaterializer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionOrchestratorIntegrationTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:05:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime ENGINE_START =
            OffsetDateTime.parse("2026-07-30T12:04:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void orchestratesRealPreparationDummyExecutionAndWorkspaceRelease() throws Exception {
        assumeGitAvailable();
        Path origin = createRepository();
        String revision = runGit(origin, "rev-parse", "HEAD");
        Path workspaceRoot = temporaryDirectory.resolve("workspaces");
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(workspaceRoot.toString()), CLOCK);
        WorkspaceManager manager = new WorkspaceManager(provider);
        SourcePreparationServiceImpl preparationService = new SourcePreparationServiceImpl(
                manager,
                new GitSourceMaterializer(
                        provider,
                        new SourceConfigurationValidator(),
                        new GitMaterializationProperties(
                                "git", Duration.ofSeconds(20), 65_536, true),
                        CLOCK),
                CLOCK);
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionEngine engine = dummyEngine(revision, invoked);
        ExecutionOrchestrator orchestrator = new ExecutionOrchestratorImpl(
                preparationService,
                new ExecutionEngineRegistryImpl(List.of(engine)),
                manager,
                new ExecutionSecretScopeFactory(
                        new ExecutionSecretProviderRegistry(List.of())),
                CLOCK);
        UUID executionId = UUID.randomUUID();
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()),
                executionId,
                LocalWorkspaceProvider.PROVIDER_ID);
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                origin.toUri().toASCIIString(),
                revision,
                null);

        ExecutionOrchestrationResult result = orchestrator.execute(
                new ExecutionOrchestrationRequest(
                        context(executionId),
                        new SourcePreparationRequest(planned, source)));

        assertThat(invoked).isTrue();
        assertThat(result.engineResult().state()).isEqualTo(EngineExecutionState.SUCCEEDED);
        assertThat(result.engineResult().resolvedRevision()).isEqualTo(revision);
        assertThat(result.completedAt().toInstant()).isEqualTo(CLOCK.instant());
        assertThat(workspaceRoot.resolve(planned.workspaceId().value().toString()))
                .doesNotExist();
    }

    @Test
    void controlledCoordinatorUsesAdmittedRevisionAndPersistsNormalizedOutcome() throws Exception {
        String admittedRevision = "0123456789abcdef0123456789abcdef01234567";
        Path workspaceRoot = temporaryDirectory.resolve("controlled-workspaces");
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(workspaceRoot.toString()), CLOCK);
        WorkspaceManager manager = new WorkspaceManager(provider);
        SourcePreparationServiceImpl preparationService = new SourcePreparationServiceImpl(
                manager,
                request -> {
                    Path sourceDirectory = workspaceRoot
                            .resolve(request.workspaceId().value().toString())
                            .resolve("source");
                    try {
                        Files.writeString(sourceDirectory.resolve("scenario.json"), "{}");
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    return new SourceMaterializationResult(
                            request.workspaceId(),
                            request.sourceReference().sourceType(),
                            request.sourceReference().revision(),
                            SourceMaterializationState.MATERIALIZED,
                            CLOCK.instant().atOffset(ZoneOffset.UTC));
                },
                CLOCK);
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionOrchestrator orchestrator = new ExecutionOrchestratorImpl(
                preparationService,
                new ExecutionEngineRegistryImpl(List.of(dummyEngine(admittedRevision, invoked))),
                manager,
                new ExecutionSecretScopeFactory(
                        new ExecutionSecretProviderRegistry(List.of())),
                CLOCK);
        UUID executionId = UUID.randomUUID();
        ExecutionContext executionContext = context(executionId);
        ExecutionSourceReference admittedSource = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://example.test/controlled.git",
                admittedRevision,
                null);
        Map<String, Object> admittedSnapshot = admittedSource.toSnapshot();
        RecordingRunnerExecutionService lifecycle = new RecordingRunnerExecutionService(
                executionContext, admittedSnapshot);
        RunnerPipelineCoordinator coordinator = new RunnerPipelineCoordinatorImpl(
                lifecycle,
                orchestrator,
                new AdmittedSourceSnapshotMapper(new SourceConfigurationValidator()),
                LocalWorkspaceProvider.PROVIDER_ID);
        RunnerExecutionRequest request = new RunnerExecutionRequest(
                executionId, "controlled-runner", UUID.randomUUID(), 1, 2, 3);

        RunnerPipelineResult result = coordinator.execute(request);

        assertThat(invoked).isTrue();
        assertThat(result.completion().status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(lifecycle.terminalStatus).isEqualTo(ExecutionStatus.PASSED);
        assertThat(lifecycle.completionRequest.expectedExecutionVersion()).isEqualTo(4);
        assertThat(workspaceRoot.resolve(executionId.toString())).doesNotExist();
    }

    private ExecutionEngine dummyEngine(String revision, AtomicBoolean invoked) {
        return new ExecutionEngine() {
            private final ExecutionEngineDescriptor descriptor =
                    new ExecutionEngineDescriptor(
                            "dummy", "1.0", "Dummy Test Engine", Set.of(), Set.of());

            @Override
            public ExecutionEngineDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void validate(ExecutionContext context) {
            }

            @Override
            public EngineExecutionResult execute(EngineExecutionRequest request) {
                assertThat(request.preparation().source().resolvedRevision()).isEqualTo(revision);
                assertThat(request.preparation().workspace().workspaceId()).isNotNull();
                invoked.set(true);
                return new EngineExecutionResult(
                        request.executionId(),
                        descriptor.engineName(),
                        descriptor.engineVersion(),
                        request.preparation().workspace().workspaceId(),
                        revision,
                        EngineExecutionState.SUCCEEDED,
                        ENGINE_START,
                        ENGINE_START.plusSeconds(1),
                        Duration.ofSeconds(1));
            }
        };
    }

    private Path createRepository() throws Exception {
        Path origin = temporaryDirectory.resolve("origin");
        Files.createDirectory(origin);
        runGit(origin, "init");
        Files.writeString(origin.resolve("scenario.txt"), "orchestrated-engine-source");
        runGit(origin, "add", "--", "scenario.txt");
        runGit(origin, "-c", "user.name=Automation Studio Test",
                "-c", "user.email=automation-studio@example.invalid",
                "commit", "-m", "fixture");
        return origin;
    }

    private ExecutionContext context(UUID executionId) {
        return new ExecutionContext(
                executionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ExecutionSuiteSnapshot(
                        UUID.randomUUID(), "Suite", "dummy", "1.0", "TEST",
                        null, "tests", Map.of(), Map.of()),
                new ExecutionEnvironmentSnapshot(
                        UUID.randomUUID(), "QA", "TEST", "https://example.invalid",
                        Map.of(), Map.of()),
                List.of(),
                Map.of(),
                new ExecutionRunnerContext(
                        UUID.randomUUID(), "runner", "1", "windows", "amd64",
                        Map.of(), Map.of()),
                new ExecutionMetadata(
                        UUID.randomUUID(), ENGINE_START, ENGINE_START,
                        Duration.ofMinutes(5), ExecutionRetryPolicy.DISABLED));
    }

    private String runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("credential.helper=");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "Never");
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Git fixture command timed out");
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String error = new String(
                process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new AssertionError("Git fixture command failed: " + error);
        }
        return output;
    }

    private void assumeGitAvailable() throws Exception {
        Process process = new ProcessBuilder("git", "--version").start();
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            Assumptions.abort("Git executable is unavailable");
        }
    }

    private static final class RecordingRunnerExecutionService
            implements RunnerExecutionService {

        private final ExecutionContext context;
        private final Map<String, Object> sourceSnapshot;
        private RunnerExecutionRequest completionRequest;
        private ExecutionStatus terminalStatus;

        private RecordingRunnerExecutionService(
                ExecutionContext context, Map<String, Object> sourceSnapshot) {
            this.context = context;
            this.sourceSnapshot = sourceSnapshot;
        }

        @Override
        public ExecutionStartResult start(RunnerExecutionRequest request) {
            return new ExecutionStartResult(
                    request.executionId(),
                    ExecutionStatus.RUNNING,
                    4,
                    1,
                    3,
                    ENGINE_START,
                    context,
                    new ExecutionEngineDescriptor(
                            "dummy", "1.0", "Dummy Test Engine", Set.of(), Set.of()),
                    sourceSnapshot);
        }

        @Override
        public ExecutionCompletionResult prepareCompletion(RunnerExecutionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutionCompletionResult complete(
                RunnerExecutionRequest request, ExecutionStatus status) {
            completionRequest = request;
            terminalStatus = status;
            return new ExecutionCompletionResult(
                    request.executionId(), status, 5, 1, 4, CLOCK.instant().atOffset(ZoneOffset.UTC));
        }
    }
}
