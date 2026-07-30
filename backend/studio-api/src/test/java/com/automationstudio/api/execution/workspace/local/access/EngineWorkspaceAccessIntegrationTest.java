package com.automationstudio.api.execution.workspace.local.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.preparation.SourcePreparationRequest;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationService;
import com.automationstudio.api.execution.preparation.SourcePreparationServiceImpl;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceProvider;
import com.automationstudio.api.execution.workspace.local.WorkspaceRootProperties;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.git.GitMaterializationProperties;
import com.automationstudio.api.source.materialization.git.GitSourceMaterializer;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineWorkspaceAccessIntegrationTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void opensMaterializedWorkspaceAndSeparatesHandleCloseFromRelease() throws Exception {
        assumeGitAvailable();
        Path origin = createRepository();
        String revision = runGit(origin, "rev-parse", "HEAD");
        Path root = temporaryDirectory.resolve("workspaces");
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(root.toString()), CLOCK);
        WorkspaceManager manager = new WorkspaceManager(provider);
        SourcePreparationService preparationService = new SourcePreparationServiceImpl(
                manager,
                new GitSourceMaterializer(
                        provider,
                        new SourceConfigurationValidator(),
                        new GitMaterializationProperties(
                                "git", Duration.ofSeconds(20), 65_536, true),
                        CLOCK),
                CLOCK);
        EngineWorkspaceAccessResolver resolver =
                new LocalEngineWorkspaceAccessResolver(provider);
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()),
                UUID.randomUUID(),
                LocalWorkspaceProvider.PROVIDER_ID);
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                origin.toUri().toASCIIString(),
                revision,
                null);
        SourcePreparationResult preparation = preparationService.prepare(
                new SourcePreparationRequest(planned, source));
        Path workspace = root.resolve(planned.workspaceId().value().toString());

        EngineWorkspaceAccess access =
                resolver.open(EngineWorkspaceAccessRequest.from(preparation));
        assertThat(Files.readString(access.sourceDirectory().resolve("scenario.txt")))
                .isEqualTo("secure-access-source");
        Files.writeString(access.artifactsDirectory().resolve("result.txt"), "artifact");
        Files.writeString(access.temporaryDirectory().resolve("scratch.txt"), "temporary");
        access.close();

        assertThatThrownBy(access::sourceDirectory)
                .isInstanceOf(EngineWorkspaceAccessException.class);
        assertThat(workspace).exists();
        assertThat(workspace.resolve("artifacts").resolve("result.txt")).exists();
        assertThat(workspace.resolve("temp").resolve("scratch.txt")).exists();

        WorkspaceDescriptor released = manager.release(preparation.workspace());
        assertThat(released.state()).isEqualTo(WorkspaceState.RELEASED);
        assertThat(workspace).doesNotExist();
    }

    private Path createRepository() throws Exception {
        Path origin = temporaryDirectory.resolve("origin");
        Files.createDirectory(origin);
        runGit(origin, "init");
        Files.writeString(origin.resolve("scenario.txt"), "secure-access-source");
        runGit(origin, "add", "--", "scenario.txt");
        runGit(origin, "-c", "user.name=Automation Studio Test",
                "-c", "user.email=automation-studio@example.invalid",
                "commit", "-m", "fixture");
        return origin;
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
}
