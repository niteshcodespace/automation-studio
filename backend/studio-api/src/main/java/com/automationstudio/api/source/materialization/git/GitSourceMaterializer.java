package com.automationstudio.api.source.materialization.git;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceLocation;
import com.automationstudio.api.execution.workspace.local.WorkspaceLocationResolver;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationException;
import com.automationstudio.api.source.materialization.SourceMaterializationRequest;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import com.automationstudio.api.source.materialization.SourceMaterializer;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class GitSourceMaterializer implements SourceMaterializer {

    private static final int LOCK_COUNT = 64;

    private final WorkspaceLocationResolver locationResolver;
    private final SourceConfigurationValidator sourceValidator;
    private final GitMaterializationProperties properties;
    private final GitCommandRunner commandRunner;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_COUNT];

    public GitSourceMaterializer(
            WorkspaceLocationResolver locationResolver,
            SourceConfigurationValidator sourceValidator,
            GitMaterializationProperties properties,
            Clock clock) {
        this(
                locationResolver,
                sourceValidator,
                properties,
                new ProcessGitCommandRunner(properties),
                clock);
    }

    GitSourceMaterializer(
            WorkspaceLocationResolver locationResolver,
            SourceConfigurationValidator sourceValidator,
            GitMaterializationProperties properties,
            GitCommandRunner commandRunner,
            Clock clock) {
        this.locationResolver = Objects.requireNonNull(
                locationResolver, "Workspace location resolver must not be null");
        this.sourceValidator = Objects.requireNonNull(
                sourceValidator, "Source validator must not be null");
        this.properties = Objects.requireNonNull(
                properties, "Git materialization properties must not be null");
        this.commandRunner = Objects.requireNonNull(
                commandRunner, "Git command runner must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    @Override
    public SourceMaterializationResult materialize(
            SourceMaterializationRequest request) {
        if (request == null) {
            throw failure(
                    "INVALID_MATERIALIZATION_REQUEST",
                    "Source materialization request is invalid",
                    null);
        }
        ExecutionSourceReference source = validateSource(request.sourceReference());
        ReentrantLock lock = lockFor(request.workspaceId());
        lock.lock();
        try {
            return materializeLocked(request.workspaceId(), source);
        } finally {
            lock.unlock();
        }
    }

    private SourceMaterializationResult materializeLocked(
            WorkspaceId workspaceId,
            ExecutionSourceReference source) {
        LocalWorkspaceLocation location;
        try {
            location = locationResolver.resolve(workspaceId);
        } catch (RuntimeException exception) {
            throw failure(
                    "INVALID_WORKSPACE_LOCATION",
                    "Source workspace validation failed",
                    exception);
        }
        Path sourceDirectory = location.sourceDirectory();
        requireEmptySource(sourceDirectory);
        Path isolatedHome = location.tempDirectory().resolve("git-environment");
        boolean sourceMutationStarted = false;
        try {
            createIsolatedEnvironment(location, isolatedHome);
            sourceMutationStarted = true;
            run(sourceDirectory, isolatedHome, "init", "--template="
                    + isolatedHome.resolve("template"), ".");
            run(sourceDirectory, isolatedHome, "remote", "add", "origin", source.repository());
            run(sourceDirectory, isolatedHome,
                    "fetch", "--no-tags", "--force", "--depth=1", "origin", source.revision());
            run(sourceDirectory, isolatedHome,
                    "checkout", "--detach", "--no-recurse-submodules", source.revision(), "--");
            String resolvedRevision = run(
                    sourceDirectory, isolatedHome, "rev-parse", "--verify", "HEAD")
                    .toLowerCase(Locale.ROOT);
            String headName = run(
                    sourceDirectory, isolatedHome, "rev-parse", "--abbrev-ref", "HEAD");
            if (!source.revision().equals(resolvedRevision) || !"HEAD".equals(headName)) {
                throw failure(
                        "SOURCE_REVISION_MISMATCH",
                        "Materialized source revision verification failed",
                        null);
            }
            validateRepositoryMetadata(sourceDirectory);
            deleteContainedTree(isolatedHome, location.tempDirectory(), true);
            return new SourceMaterializationResult(
                    workspaceId,
                    source.sourceType(),
                    resolvedRevision,
                    SourceMaterializationState.MATERIALIZED,
                    OffsetDateTime.now(clock));
        } catch (RuntimeException exception) {
            if (sourceMutationStarted) {
                cleanupAfterFailure(
                        sourceDirectory,
                        location.sourceDirectory(),
                        isolatedHome,
                        location.tempDirectory(),
                        exception);
            }
            throw sanitize(exception);
        }
    }

    private ExecutionSourceReference validateSource(ExecutionSourceReference source) {
        if (source == null || source.sourceType() != SourceType.GIT_HTTPS) {
            throw failure(
                    "UNSUPPORTED_SOURCE_TYPE",
                    "Source type is not supported for Git materialization",
                    null);
        }
        try {
            String repository = validateRepository(source.repository());
            String revision = sourceValidator.normalizeRevision(source.revision());
            String sourceLocation =
                    sourceValidator.normalizeSourceLocation(source.sourceLocation());
            return new ExecutionSourceReference(
                    source.sourceType(), repository, revision, sourceLocation);
        } catch (RuntimeException exception) {
            throw failure(
                    "INVALID_SOURCE_CONFIGURATION",
                    "Source configuration is invalid",
                    exception);
        }
    }

    private String validateRepository(String repository) {
        if (!properties.allowLocalRepositories()) {
            return sourceValidator.normalizeRepository(repository);
        }
        if (repository == null
                || repository.isBlank()
                || !repository.equals(repository.trim())
                || repository.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Local repository URI is invalid");
        }
        try {
            URI uri = new URI(repository);
            if (!"file".equalsIgnoreCase(uri.getScheme())
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPath() == null
                    || uri.getPath().isBlank()) {
                throw new IllegalArgumentException("Local repository URI is invalid");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Local repository URI is invalid", exception);
        }
    }

    private void requireEmptySource(Path sourceDirectory) {
        try {
            if (Files.isSymbolicLink(sourceDirectory)
                    || !Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(
                        "INVALID_SOURCE_DIRECTORY",
                        "Source directory validation failed",
                        null);
            }
            try (var entries = Files.list(sourceDirectory)) {
                if (entries.findAny().isPresent()) {
                    throw failure(
                            "SOURCE_ALREADY_MATERIALIZED",
                            "Source directory must be empty",
                            null);
                }
            }
        } catch (IOException exception) {
            throw failure(
                    "INVALID_SOURCE_DIRECTORY",
                    "Source directory validation failed",
                    exception);
        }
    }

    private void createIsolatedEnvironment(
            LocalWorkspaceLocation location,
            Path isolatedHome) {
        try {
            if (Files.exists(isolatedHome, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(
                        "GIT_ENVIRONMENT_EXISTS",
                        "Git isolation environment is invalid",
                        null);
            }
            Files.createDirectory(isolatedHome);
            Files.createDirectory(isolatedHome.resolve("hooks"));
            Files.createDirectory(isolatedHome.resolve("template"));
            requireDirectChild(location.tempDirectory(), isolatedHome);
        } catch (IOException exception) {
            throw failure(
                    "GIT_ENVIRONMENT_CREATION_FAILED",
                    "Git isolation environment creation failed",
                    exception);
        }
    }

    private String run(Path workingDirectory, Path isolatedHome, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("-c");
        command.add(properties.allowLocalRepositories()
                ? "protocol.file.allow=always"
                : "protocol.file.allow=never");
        command.addAll(Arrays.asList(arguments));
        return commandRunner.execute(command, workingDirectory, isolatedHome).standardOutput();
    }

    private void validateRepositoryMetadata(Path sourceDirectory) {
        try {
            Path gitDirectory = sourceDirectory.resolve(".git").normalize();
            requireDirectChild(sourceDirectory, gitDirectory);
            if (Files.isSymbolicLink(gitDirectory)
                    || !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)
                    || !gitDirectory.toRealPath().equals(gitDirectory)) {
                throw failure(
                        "INVALID_GIT_METADATA",
                        "Git repository metadata validation failed",
                        null);
            }
            if (Files.exists(gitDirectory.resolve("commondir"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(
                            gitDirectory.resolve("objects").resolve("info").resolve("alternates"),
                            LinkOption.NOFOLLOW_LINKS)) {
                throw failure(
                        "INVALID_GIT_METADATA",
                        "Git repository metadata validation failed",
                        null);
            }
            validateNoSpecialEntries(sourceDirectory);
        } catch (IOException exception) {
            throw failure(
                    "INVALID_GIT_METADATA",
                    "Git repository metadata validation failed",
                    exception);
        }
    }

    private void validateNoSpecialEntries(Path root) throws IOException {
        Path realRoot = root.toRealPath();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()
                        || attributes.isOther()
                        || !directory.toRealPath().startsWith(realRoot)) {
                    throw failure(
                            "UNSAFE_SOURCE_CONTENT",
                            "Materialized source contains an unsafe filesystem entry",
                            null);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw failure(
                            "UNSAFE_SOURCE_CONTENT",
                            "Materialized source contains an unsafe filesystem entry",
                            null);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void cleanupAfterFailure(
            Path sourceDirectory,
            Path sourceRoot,
            Path isolatedHome,
            Path tempRoot,
            RuntimeException original) {
        try {
            deleteContainedTree(sourceDirectory, sourceRoot, false);
            if (Files.exists(isolatedHome, LinkOption.NOFOLLOW_LINKS)) {
                deleteContainedTree(isolatedHome, tempRoot, true);
            }
        } catch (RuntimeException cleanupFailure) {
            cleanupFailure.addSuppressed(original);
            throw failure(
                    "SOURCE_CLEANUP_FAILED",
                    "Source materialization cleanup failed",
                    cleanupFailure);
        }
    }

    private void deleteContainedTree(Path target, Path containmentRoot, boolean deleteTarget) {
        try {
            Path normalizedTarget = target.toAbsolutePath().normalize();
            Path normalizedRoot = containmentRoot.toRealPath();
            if (!normalizedTarget.startsWith(normalizedRoot)
                    || (!deleteTarget && !normalizedTarget.equals(normalizedRoot))
                    || (deleteTarget && normalizedTarget.equals(normalizedRoot))) {
                throw failure(
                        "SOURCE_CLEANUP_FAILED",
                        "Source materialization cleanup failed",
                        null);
            }
            Files.walkFileTree(normalizedTarget, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes) throws IOException {
                    if (!file.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
                        throw new IOException("Containment validation failed");
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                        Path directory,
                        IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    if (deleteTarget || !directory.equals(normalizedTarget)) {
                        Files.delete(directory);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw failure(
                    "SOURCE_CLEANUP_FAILED",
                    "Source materialization cleanup failed",
                    exception);
        }
    }

    private void requireDirectChild(Path parent, Path child) throws IOException {
        Path realParent = parent.toRealPath();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!Objects.equals(normalizedChild.getParent(), realParent)
                || !normalizedChild.startsWith(realParent)
                || Files.isSymbolicLink(normalizedChild)) {
            throw failure(
                    "INVALID_WORKSPACE_LOCATION",
                    "Source workspace validation failed",
                    null);
        }
    }

    private RuntimeException sanitize(RuntimeException exception) {
        if (exception instanceof SourceMaterializationException) {
            return exception;
        }
        return failure(
                "SOURCE_MATERIALIZATION_FAILED",
                "Git source materialization failed",
                exception);
    }

    private GitMaterializationException failure(
            String code,
            String message,
            Throwable cause) {
        return new GitMaterializationException(code, message, cause);
    }

    private ReentrantLock lockFor(WorkspaceId workspaceId) {
        int index = Math.floorMod(workspaceId.hashCode(), locks.length);
        return locks[index];
    }
}
