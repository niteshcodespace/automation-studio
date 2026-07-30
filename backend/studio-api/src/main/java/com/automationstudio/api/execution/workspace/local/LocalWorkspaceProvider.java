package com.automationstudio.api.execution.workspace.local;

import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspacePreparationRequest;
import com.automationstudio.api.execution.workspace.WorkspacePreparationResult;
import com.automationstudio.api.execution.workspace.WorkspaceProvider;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.execution.workspace.WorkspaceReleaseRequest;
import com.automationstudio.api.execution.workspace.WorkspaceReleaseResult;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class LocalWorkspaceProvider
        implements WorkspaceProvider, WorkspaceLocationResolver {

    public static final WorkspaceProviderId PROVIDER_ID =
            new WorkspaceProviderId("local-filesystem");

    private static final List<String> CHILD_DIRECTORIES =
            List.of("metadata", "source", "artifacts", "temp");
    private static final int LOCK_COUNT = 64;

    private final Path configuredRoot;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_COUNT];

    public LocalWorkspaceProvider(WorkspaceRootProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "Workspace root properties must not be null");
        this.configuredRoot = validateConfiguredRoot(properties.root());
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    @Override
    public WorkspaceProviderId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public WorkspacePreparationResult prepare(WorkspacePreparationRequest request) {
        Objects.requireNonNull(request, "Preparation request must not be null");
        requireOwnership(request.workspace());
        ReentrantLock lock = lockFor(request.workspace().workspaceId());
        lock.lock();
        try {
            Path root = ensureCanonicalRoot();
            Path workspace = resolveWorkspace(root, request.workspace().workspaceId());
            if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new LocalWorkspaceException("Workspace already exists");
            }
            try {
                Files.createDirectory(workspace);
                requireCanonicalWorkspace(root, workspace);
                for (String child : CHILD_DIRECTORIES) {
                    Files.createDirectory(workspace.resolve(child));
                }
            } catch (IOException | RuntimeException exception) {
                cleanupPartial(root, workspace, exception);
                throw localFailure("Workspace creation failed", exception);
            }

            WorkspaceMetadata metadata = new WorkspaceMetadata(
                    OffsetDateTime.now(clock), request.sourceReference());
            WorkspaceDescriptor ready =
                    request.workspace().transitionTo(WorkspaceState.READY, metadata);
            return new WorkspacePreparationResult(request, ready);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public WorkspaceReleaseResult release(WorkspaceReleaseRequest request) {
        Objects.requireNonNull(request, "Release request must not be null");
        requireOwnership(request.workspace());
        ReentrantLock lock = lockFor(request.workspace().workspaceId());
        lock.lock();
        try {
            Path root = ensureCanonicalRoot();
            Path workspace = resolveWorkspace(root, request.workspace().workspaceId());
            if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
                validateTree(root, workspace);
                deleteTree(workspace);
            }
            WorkspaceDescriptor released =
                    request.workspace().transitionTo(WorkspaceState.RELEASED, null);
            return new WorkspaceReleaseResult(request, released);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public LocalWorkspaceLocation resolve(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        ReentrantLock lock = lockFor(workspaceId);
        lock.lock();
        try {
            Path root = ensureCanonicalRoot();
            Path workspace = resolveWorkspace(root, workspaceId);
            try {
                requireCanonicalWorkspace(root, workspace);
                return new LocalWorkspaceLocation(
                        workspace,
                        requireCanonicalChild(workspace, "source"),
                        requireCanonicalChild(workspace, "metadata"),
                        requireCanonicalChild(workspace, "artifacts"),
                        requireCanonicalChild(workspace, "temp"));
            } catch (IOException exception) {
                throw new LocalWorkspaceException(
                        "Workspace location validation failed", exception);
            }
        } finally {
            lock.unlock();
        }
    }

    private Path validateConfiguredRoot(String root) {
        if (root == null || root.isBlank()) {
            throw new LocalWorkspaceException("Workspace root must not be blank");
        }
        if (!root.equals(root.trim())) {
            throw new LocalWorkspaceException(
                    "Workspace root must not contain surrounding whitespace");
        }
        final Path path;
        try {
            path = Path.of(root);
        } catch (RuntimeException exception) {
            throw new LocalWorkspaceException("Workspace root is invalid", exception);
        }
        if (!path.isAbsolute()) {
            throw new LocalWorkspaceException("Workspace root must be absolute");
        }
        for (Path segment : path) {
            if ("..".equals(segment.toString()) || ".".equals(segment.toString())) {
                throw new LocalWorkspaceException(
                        "Workspace root must not contain relative segments");
            }
        }
        Path normalized = path.normalize();
        if (normalized.getParent() == null) {
            throw new LocalWorkspaceException("Workspace root must not be a filesystem root");
        }
        return normalized;
    }

    private Path ensureCanonicalRoot() {
        try {
            Files.createDirectories(configuredRoot);
            if (Files.isSymbolicLink(configuredRoot)
                    || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new LocalWorkspaceException(
                        "Workspace root must be a real directory");
            }
            Path realRoot = configuredRoot.toRealPath();
            if (!realRoot.equals(configuredRoot)) {
                throw new LocalWorkspaceException(
                        "Workspace root must not resolve through links");
            }
            rejectBroadRoot(realRoot);
            return realRoot;
        } catch (IOException exception) {
            throw new LocalWorkspaceException("Workspace root validation failed", exception);
        }
    }

    private void rejectBroadRoot(Path realRoot) throws IOException {
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            Path home = Path.of(userHome);
            if (Files.exists(home) && realRoot.equals(home.toRealPath())) {
                throw new LocalWorkspaceException(
                        "Workspace root must not be the user home");
            }
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(workingDirectory)
                && realRoot.equals(workingDirectory.toRealPath())) {
            throw new LocalWorkspaceException(
                    "Workspace root must not be the working directory");
        }
    }

    private Path resolveWorkspace(Path root, WorkspaceId workspaceId) {
        Path workspace = root.resolve(workspaceId.value().toString()).normalize();
        if (!workspace.startsWith(root)
                || workspace.equals(root)
                || !Objects.equals(workspace.getParent(), root)) {
            throw new LocalWorkspaceException(
                    "Workspace location is outside the configured root");
        }
        return workspace;
    }

    private void requireCanonicalWorkspace(Path root, Path workspace) throws IOException {
        if (Files.isSymbolicLink(workspace)
                || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new LocalWorkspaceException("Workspace must be a real directory");
        }
        Path realWorkspace = workspace.toRealPath();
        if (!realWorkspace.equals(workspace)
                || !realWorkspace.startsWith(root)
                || !Objects.equals(realWorkspace.getParent(), root)) {
            throw new LocalWorkspaceException(
                    "Workspace canonical location is invalid");
        }
    }

    private Path requireCanonicalChild(Path workspace, String name) throws IOException {
        Path child = workspace.resolve(name).normalize();
        if (!Objects.equals(child.getParent(), workspace)
                || Files.isSymbolicLink(child)
                || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            throw new LocalWorkspaceException(
                    "Workspace child directory is invalid");
        }
        Path realChild = child.toRealPath();
        if (!realChild.equals(child)
                || !realChild.startsWith(workspace)
                || !Objects.equals(realChild.getParent(), workspace)) {
            throw new LocalWorkspaceException(
                    "Workspace child canonical location is invalid");
        }
        return realChild;
    }

    private void validateTree(Path root, Path workspace) {
        try {
            requireCanonicalWorkspace(root, workspace);
            Path realWorkspace = workspace.toRealPath();
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attributes) throws IOException {
                    requireSafeEntry(directory, attributes, realWorkspace);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes) throws IOException {
                    requireSafeEntry(file, attributes, realWorkspace);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new LocalWorkspaceException(
                    "Workspace cleanup validation failed", exception);
        }
    }

    private void requireSafeEntry(
            Path entry,
            BasicFileAttributes attributes,
            Path realWorkspace) throws IOException {
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new LocalWorkspaceException(
                    "Workspace contains an unsupported filesystem entry");
        }
        if (attributes.isDirectory()) {
            Path realEntry = entry.toRealPath();
            if (!realEntry.startsWith(realWorkspace)) {
                throw new LocalWorkspaceException(
                        "Workspace entry escapes its canonical directory");
            }
        }
    }

    private void deleteTree(Path workspace) {
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        throw new LocalWorkspaceException(
                                "Workspace changed during cleanup");
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
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new LocalWorkspaceException("Workspace cleanup failed", exception);
        }
    }

    private void cleanupPartial(Path root, Path workspace, Throwable original) {
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            validateTree(root, workspace);
            deleteTree(workspace);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private LocalWorkspaceException localFailure(String message, Throwable exception) {
        return exception instanceof LocalWorkspaceException local
                ? local
                : new LocalWorkspaceException(message, exception);
    }

    private void requireOwnership(WorkspaceDescriptor workspace) {
        if (!PROVIDER_ID.equals(workspace.providerId())) {
            throw new LocalWorkspaceException(
                    "Workspace is owned by another provider");
        }
    }

    private ReentrantLock lockFor(WorkspaceId workspaceId) {
        int index = Math.floorMod(workspaceId.hashCode(), locks.length);
        return locks[index];
    }
}
