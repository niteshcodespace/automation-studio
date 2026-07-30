package com.automationstudio.api.source.materialization.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspacePreparationRequest;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceProvider;
import com.automationstudio.api.execution.workspace.local.WorkspaceRootProperties;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationException;
import com.automationstudio.api.source.materialization.SourceMaterializationRequest;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSourceMaterializerIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private Path workspaceRoot;
    private LocalWorkspaceProvider workspaceProvider;
    private RepositoryFixture repository;

    @BeforeEach
    void setUp() throws Exception {
        assumeGitAvailable();
        workspaceRoot = temporaryDirectory.resolve("workspaces");
        workspaceProvider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(workspaceRoot.toString()), CLOCK);
        repository = createRepositoryFixture();
    }

    @Test
    void materializesExactCommitWithDetachedHeadAndPathFreeResult() throws Exception {
        WorkspaceId workspaceId = prepareWorkspace();
        GitSourceMaterializer materializer = materializer(true, "git");

        SourceMaterializationResult result =
                materializer.materialize(request(workspaceId, repository));
        Path source = sourceDirectory(workspaceId);

        assertThat(result.workspaceId()).isEqualTo(workspaceId);
        assertThat(result.sourceType()).isEqualTo(SourceType.GIT_HTTPS);
        assertThat(result.resolvedRevision()).isEqualTo(repository.revision());
        assertThat(result.state()).isEqualTo(SourceMaterializationState.MATERIALIZED);
        assertThat(result.materializedAt().toInstant()).isEqualTo(CLOCK.instant());
        assertThat(Files.readString(source.resolve("scenario.txt")))
                .isEqualTo("immutable-source");
        assertThat(runGit(source, "rev-parse", "HEAD").output())
                .isEqualTo(repository.revision());
        assertThat(runGit(source, "rev-parse", "--abbrev-ref", "HEAD").output())
                .isEqualTo("HEAD");
        assertThat(source.resolve(".git")).isDirectory();
        assertThat(workspaceRoot.resolve(workspaceId.value().toString())
                .resolve("temp").resolve("git-environment")).doesNotExist();
    }

    @Test
    void rejectsDuplicateAndNonEmptySourceWithoutDeletingExistingContent() throws Exception {
        WorkspaceId workspaceId = prepareWorkspace();
        GitSourceMaterializer materializer = materializer(true, "git");
        SourceMaterializationRequest request = request(workspaceId, repository);
        materializer.materialize(request);

        assertThatThrownBy(() -> materializer.materialize(request))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("SOURCE_ALREADY_MATERIALIZED");
        assertThat(sourceDirectory(workspaceId).resolve("scenario.txt")).exists();

        WorkspaceId another = prepareWorkspace();
        Path sentinel = sourceDirectory(another).resolve("keep.txt");
        Files.writeString(sentinel, "keep");
        assertThatThrownBy(() -> materializer.materialize(request(another, repository)))
                .isInstanceOf(SourceMaterializationException.class);
        assertThat(sentinel).exists();
    }

    @Test
    void cleansOnlySourceAfterProcessStartFailureAndPreservesSiblings() {
        WorkspaceId workspaceId = prepareWorkspace();
        GitSourceMaterializer materializer =
                materializer(true, "missing-git-executable-for-as-023e");

        assertThatThrownBy(() -> materializer.materialize(request(workspaceId, repository)))
                .isInstanceOf(SourceMaterializationException.class)
                .hasMessageNotContaining(repository.uri())
                .hasMessageNotContaining(workspaceRoot.toString())
                .hasMessageNotContaining("missing-git-executable");

        Path workspace = workspaceRoot.resolve(workspaceId.value().toString());
        assertThat(sourceDirectory(workspaceId)).isEmptyDirectory();
        assertThat(workspace.resolve("metadata")).isDirectory();
        assertThat(workspace.resolve("artifacts")).isDirectory();
        assertThat(workspace.resolve("temp")).isEmptyDirectory();
    }

    @Test
    void rejectsMissingWorkspaceAndMissingSourceDirectory() throws Exception {
        GitSourceMaterializer materializer = materializer(true, "git");
        WorkspaceId missing = new WorkspaceId(UUID.randomUUID());
        assertThatThrownBy(() -> materializer.materialize(request(missing, repository)))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("INVALID_WORKSPACE_LOCATION");

        WorkspaceId invalid = prepareWorkspace();
        Files.delete(sourceDirectory(invalid));
        assertThatThrownBy(() -> materializer.materialize(request(invalid, repository)))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("INVALID_WORKSPACE_LOCATION");
    }

    @Test
    void productionPolicyRejectsLocalAndUnsafeRepositoryUris() {
        WorkspaceId workspaceId = prepareWorkspace();
        GitSourceMaterializer production = materializer(false, "git");

        List<String> invalid = List.of(
                "",
                "not a uri",
                repository.uri(),
                "C:\\repository",
                "\\\\server\\share\\repository",
                "git://example.com/repository.git",
                "ssh://git@example.com/repository.git",
                "git@example.com:repository.git",
                "https://user:password@example.com/repository.git",
                " https://example.com/repository.git",
                "https://example.com/repository.git#fragment");
        for (String repositoryUri : invalid) {
            ExecutionSourceReference source = new ExecutionSourceReference(
                    SourceType.GIT_HTTPS,
                    repositoryUri,
                    repository.revision(),
                    null);
            assertThatThrownBy(() -> production.materialize(
                    new SourceMaterializationRequest(workspaceId, source)))
                    .isInstanceOf(SourceMaterializationException.class)
                    .extracting(exception -> ((SourceMaterializationException) exception).code())
                    .isEqualTo("INVALID_SOURCE_CONFIGURATION");
        }
    }

    @Test
    void rejectsSymbolicAndOptionLikeRevisions() {
        WorkspaceId workspaceId = prepareWorkspace();
        GitSourceMaterializer materializer = materializer(true, "git");
        for (String revision : List.of(
                "main", "v1.0.0", "HEAD", "abc1234", "main~1", "--help",
                "gggggggggggggggggggggggggggggggggggggggg",
                " " + repository.revision(), repository.revision() + " ")) {
            ExecutionSourceReference source = new ExecutionSourceReference(
                    SourceType.GIT_HTTPS, repository.uri(), revision, null);
            assertThatThrownBy(() -> materializer.materialize(
                    new SourceMaterializationRequest(workspaceId, source)))
                    .isInstanceOf(SourceMaterializationException.class)
                    .extracting(exception -> ((SourceMaterializationException) exception).code())
                    .isEqualTo("INVALID_SOURCE_CONFIGURATION");
        }
    }

    @Test
    void controlsSameWorkspaceConcurrencyAndAllowsDifferentWorkspaces() throws Exception {
        GitSourceMaterializer materializer = materializer(true, "git");
        WorkspaceId shared = prepareWorkspace();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(
                    () -> invoke(materializer, request(shared, repository), start));
            Future<Object> second = executor.submit(
                    () -> invoke(materializer, request(shared, repository), start));
            start.countDown();
            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(SourceMaterializationResult.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream().filter(SourceMaterializationException.class::isInstance))
                    .hasSize(1);
        }

        WorkspaceId one = prepareWorkspace();
        WorkspaceId two = prepareWorkspaceWithDifferentStripe(one);
        CountDownLatch secondStart = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(
                    () -> invoke(materializer, request(one, repository), secondStart));
            Future<Object> second = executor.submit(
                    () -> invoke(materializer, request(two, repository), secondStart));
            secondStart.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(SourceMaterializationResult.class);
            assertThat(second.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(SourceMaterializationResult.class);
        }
    }

    private GitSourceMaterializer materializer(
            boolean allowLocal,
            String executable) {
        return new GitSourceMaterializer(
                workspaceProvider,
                new SourceConfigurationValidator(),
                new GitMaterializationProperties(
                        executable, Duration.ofSeconds(20), 65_536, allowLocal),
                CLOCK);
    }

    private WorkspaceId prepareWorkspace() {
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                workspaceId,
                UUID.randomUUID(),
                LocalWorkspaceProvider.PROVIDER_ID);
        workspaceProvider.prepare(new WorkspacePreparationRequest(
                planned.transitionTo(WorkspaceState.PREPARING, null), null));
        return workspaceId;
    }

    private WorkspaceId prepareWorkspaceWithDifferentStripe(WorkspaceId other) {
        WorkspaceId candidate;
        do {
            candidate = new WorkspaceId(UUID.randomUUID());
        } while (Math.floorMod(candidate.hashCode(), 64)
                == Math.floorMod(other.hashCode(), 64));
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                candidate, UUID.randomUUID(), LocalWorkspaceProvider.PROVIDER_ID);
        workspaceProvider.prepare(new WorkspacePreparationRequest(
                planned.transitionTo(WorkspaceState.PREPARING, null), null));
        return candidate;
    }

    private SourceMaterializationRequest request(
            WorkspaceId workspaceId,
            RepositoryFixture fixture) {
        return new SourceMaterializationRequest(
                workspaceId,
                new ExecutionSourceReference(
                        SourceType.GIT_HTTPS,
                        fixture.uri(),
                        fixture.revision(),
                        null));
    }

    private Path sourceDirectory(WorkspaceId workspaceId) {
        return workspaceRoot.resolve(workspaceId.value().toString()).resolve("source");
    }

    private Object invoke(
            GitSourceMaterializer materializer,
            SourceMaterializationRequest request,
            CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return materializer.materialize(request);
        } catch (SourceMaterializationException exception) {
            return exception;
        }
    }

    private RepositoryFixture createRepositoryFixture() throws Exception {
        Path repositoryPath = temporaryDirectory.resolve("origin");
        Files.createDirectory(repositoryPath);
        runGit(repositoryPath, "init");
        Files.writeString(
                repositoryPath.resolve("scenario.txt"),
                "immutable-source",
                StandardCharsets.UTF_8);
        runGit(repositoryPath, "add", "--", "scenario.txt");
        runGit(
                repositoryPath,
                "-c", "user.name=Automation Studio Test",
                "-c", "user.email=automation-studio@example.invalid",
                "commit", "-m", "fixture");
        String revision = runGit(repositoryPath, "rev-parse", "HEAD").output();
        return new RepositoryFixture(repositoryPath.toUri().toASCIIString(), revision);
    }

    private CommandResult runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("credential.helper=");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "Never");
        Process process = builder.start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
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
        return new CommandResult(output);
    }

    private void assumeGitAvailable() throws Exception {
        Process process = new ProcessBuilder("git", "--version").start();
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            org.junit.jupiter.api.Assumptions.abort("Git executable is unavailable");
        }
    }

    private record RepositoryFixture(String uri, String revision) {
    }

    private record CommandResult(String output) {
    }
}
