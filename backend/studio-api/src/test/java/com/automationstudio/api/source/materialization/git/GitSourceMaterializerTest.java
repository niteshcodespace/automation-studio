package com.automationstudio.api.source.materialization.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceLocation;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationException;
import com.automationstudio.api.source.materialization.SourceMaterializationRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSourceMaterializerTest {

    private static final String REVISION =
            "0123456789abcdef0123456789abcdef01234567";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsRedirectedGitMetadataAndCleansPartialSource() throws Exception {
        Fixture fixture = fixture();
        GitCommandRunner runner = (arguments, workingDirectory, home) -> {
            if (arguments.contains("init")) {
                write(workingDirectory.resolve(".git"), "gitdir: C:/external");
                write(workingDirectory.resolve("partial.txt"), "partial");
            }
            return resultFor(arguments, REVISION);
        };

        assertThatThrownBy(() -> materializer(fixture, runner).materialize(fixture.request()))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("INVALID_GIT_METADATA");
        assertThat(fixture.location().sourceDirectory()).isEmptyDirectory();
        assertThat(fixture.location().metadataDirectory()).isDirectory();
    }

    @Test
    void rejectsHeadMismatchAndCleansPartialSource() throws Exception {
        Fixture fixture = fixture();
        GitCommandRunner runner = (arguments, workingDirectory, home) -> {
            if (arguments.contains("init")) {
                createDirectory(workingDirectory.resolve(".git"));
                write(workingDirectory.resolve("partial.txt"), "partial");
            }
            return resultFor(
                    arguments,
                    "ffffffffffffffffffffffffffffffffffffffff");
        };

        assertThatThrownBy(() -> materializer(fixture, runner).materialize(fixture.request()))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("SOURCE_REVISION_MISMATCH");
        assertThat(fixture.location().sourceDirectory()).isEmptyDirectory();
    }

    @Test
    void preservesCauseButSanitizesCommandFailureAndReleasesLock() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger attempts = new AtomicInteger();
        GitCommandRunner runner = (arguments, workingDirectory, home) -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException(
                        "secret output https://user:token@example.com C:/sensitive");
            }
            if (arguments.contains("init")) {
                createDirectory(workingDirectory.resolve(".git"));
            }
            return resultFor(arguments, REVISION);
        };
        GitSourceMaterializer materializer = materializer(fixture, runner);

        assertThatThrownBy(() -> materializer.materialize(fixture.request()))
                .isInstanceOf(SourceMaterializationException.class)
                .hasMessage("Git source materialization failed")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("sensitive")
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(materializer.materialize(fixture.request()).resolvedRevision())
                .isEqualTo(REVISION);
    }

    @Test
    void rejectsNullRequest() throws Exception {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> materializer(
                fixture, (arguments, workingDirectory, home) ->
                        new GitCommandResult("")).materialize(null))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("INVALID_MATERIALIZATION_REQUEST");
    }

    private GitCommandResult resultFor(List<String> arguments, String revision) {
        if (arguments.contains("--abbrev-ref")) {
            return new GitCommandResult("HEAD");
        }
        if (arguments.contains("rev-parse")) {
            return new GitCommandResult(revision);
        }
        return new GitCommandResult("");
    }

    private void createDirectory(Path directory) {
        try {
            Files.createDirectory(directory);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private void write(Path file, String value) {
        try {
            Files.writeString(file, value);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private GitSourceMaterializer materializer(
            Fixture fixture,
            GitCommandRunner runner) {
        return new GitSourceMaterializer(
                ignored -> fixture.location(),
                new SourceConfigurationValidator(),
                new GitMaterializationProperties(
                        "git", Duration.ofSeconds(5), 1024, false),
                runner,
                CLOCK);
    }

    private Fixture fixture() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace-" + System.nanoTime());
        Path source = workspace.resolve("source");
        Path metadata = workspace.resolve("metadata");
        Path artifacts = workspace.resolve("artifacts");
        Path temp = workspace.resolve("temp");
        Files.createDirectories(source);
        Files.createDirectory(metadata);
        Files.createDirectory(artifacts);
        Files.createDirectory(temp);
        LocalWorkspaceLocation location =
                new LocalWorkspaceLocation(workspace, source, metadata, artifacts, temp);
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        SourceMaterializationRequest request = new SourceMaterializationRequest(
                workspaceId,
                new ExecutionSourceReference(
                        SourceType.GIT_HTTPS,
                        "https://example.com/repository.git",
                        REVISION,
                        null));
        return new Fixture(location, request);
    }

    private record Fixture(
            LocalWorkspaceLocation location,
            SourceMaterializationRequest request) {
    }
}
