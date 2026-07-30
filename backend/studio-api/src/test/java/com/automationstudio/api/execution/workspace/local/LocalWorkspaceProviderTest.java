package com.automationstudio.api.execution.workspace.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspacePreparationRequest;
import com.automationstudio.api.execution.workspace.WorkspaceReleaseRequest;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceProviderTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC);
    private static final List<String> CHILDREN =
            List.of("metadata", "source", "artifacts", "temp");

    @TempDir
    Path temporaryDirectory;

    @Test
    void lazilyCreatesDeterministicLayoutAndReleasesIt() throws IOException {
        Path root = temporaryDirectory.resolve("runner-workspaces");
        LocalWorkspaceProvider provider = provider(root);
        WorkspacePreparationRequest request = preparationRequest();

        assertThat(root).doesNotExist();
        WorkspaceDescriptor ready = provider.prepare(request).workspace();
        Path workspace = root.resolve(
                request.workspace().workspaceId().value().toString());

        assertThat(root).isDirectory();
        assertThat(workspace).isDirectory();
        try (var children = Files.list(workspace)) {
            assertThat(children.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrderElementsOf(CHILDREN);
        }
        assertThat(ready.state()).isEqualTo(WorkspaceState.READY);
        assertThat(ready.metadata().preparedAt().toInstant())
                .isEqualTo(CLOCK.instant());
        Path nestedOutput = workspace.resolve("artifacts").resolve("reports");
        Files.createDirectory(nestedOutput);
        writeText(nestedOutput.resolve("result.txt"));

        WorkspaceDescriptor releasing = ready
                .transitionTo(WorkspaceState.IN_USE, null)
                .transitionTo(WorkspaceState.RELEASING, null);
        WorkspaceReleaseRequest release = new WorkspaceReleaseRequest(releasing);

        assertThat(provider.release(release).workspace().state())
                .isEqualTo(WorkspaceState.RELEASED);
        assertThat(workspace).doesNotExist();
        assertThat(provider.release(release).workspace().state())
                .isEqualTo(WorkspaceState.RELEASED);
        assertThat(workspace).doesNotExist();
    }

    @Test
    void rejectsDuplicatePrepareWithoutChangingExistingWorkspace() {
        Path root = temporaryDirectory.resolve("duplicate");
        LocalWorkspaceProvider provider = provider(root);
        WorkspacePreparationRequest request = preparationRequest();
        provider.prepare(request);
        Path workspace = workspace(root, request);
        Path sentinel = workspace.resolve("source").resolve("existing.txt");
        writeText(sentinel);

        assertThatThrownBy(() -> provider.prepare(request))
                .isInstanceOf(LocalWorkspaceException.class)
                .hasMessage("Workspace already exists");
        assertThat(sentinel).exists();
    }

    @Test
    void permitsOnlyOneConcurrentPrepareForAnIdentity() throws Exception {
        Path root = temporaryDirectory.resolve("concurrent-prepare");
        LocalWorkspaceProvider provider = provider(root);
        WorkspacePreparationRequest request = preparationRequest();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> invokePrepare(provider, request, start));
            Future<Object> second = executor.submit(() -> invokePrepare(provider, request, start));
            start.countDown();

            List<Object> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(WorkspaceDescriptor.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream().filter(LocalWorkspaceException.class::isInstance))
                    .hasSize(1);
        }
    }

    @Test
    void concurrentReleaseIsIdempotent() throws Exception {
        Path root = temporaryDirectory.resolve("concurrent-release");
        LocalWorkspaceProvider provider = provider(root);
        WorkspacePreparationRequest preparation = preparationRequest();
        WorkspaceDescriptor releasing = provider.prepare(preparation).workspace()
                .transitionTo(WorkspaceState.IN_USE, null)
                .transitionTo(WorkspaceState.RELEASING, null);
        WorkspaceReleaseRequest release = new WorkspaceReleaseRequest(releasing);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<WorkspaceDescriptor> first =
                    executor.submit(() -> invokeRelease(provider, release, start));
            Future<WorkspaceDescriptor> second =
                    executor.submit(() -> invokeRelease(provider, release, start));
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).state())
                    .isEqualTo(WorkspaceState.RELEASED);
            assertThat(second.get(10, TimeUnit.SECONDS).state())
                    .isEqualTo(WorkspaceState.RELEASED);
        }
        assertThat(workspace(root, preparation)).doesNotExist();
    }

    @Test
    void rejectsRelativeTraversalAndBroadRoots() {
        assertThatThrownBy(() -> provider(Path.of("relative", "..", "workspace")))
                .isInstanceOf(LocalWorkspaceException.class);
        assertThatThrownBy(() -> provider(
                temporaryDirectory.resolve("parent").resolve("..").resolve("workspace")))
                .isInstanceOf(LocalWorkspaceException.class);
        assertThatThrownBy(() -> provider(temporaryDirectory.getRoot()))
                .isInstanceOf(LocalWorkspaceException.class);
        assertThatThrownBy(() -> provider(Path.of(System.getProperty("user.home"))).prepare(
                preparationRequest()))
                .isInstanceOf(LocalWorkspaceException.class)
                .hasMessageContaining("user home");
    }

    @Test
    void rejectsWorkspaceSymlinkAndDoesNotDeleteExternalContent() throws Exception {
        Path root = temporaryDirectory.resolve("symlink-release");
        LocalWorkspaceProvider provider = provider(root);
        WorkspacePreparationRequest preparation = preparationRequest();
        WorkspaceDescriptor ready = provider.prepare(preparation).workspace();
        Path workspace = workspace(root, preparation);
        Path external = temporaryDirectory.resolve("external");
        Files.createDirectory(external);
        Path externalFile = external.resolve("keep.txt");
        writeText(externalFile);
        Path link = workspace.resolve("source").resolve("escape");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }
        WorkspaceReleaseRequest release = new WorkspaceReleaseRequest(
                ready.transitionTo(WorkspaceState.IN_USE, null)
                        .transitionTo(WorkspaceState.RELEASING, null));

        assertThatThrownBy(() -> provider.release(release))
                .isInstanceOf(LocalWorkspaceException.class)
                .hasMessageContaining("unsupported filesystem entry");
        assertThat(externalFile).exists();
        assertThat(workspace).exists();
    }

    @Test
    void rejectsConfiguredRootThatResolvesThroughSymlink() throws Exception {
        Path actual = temporaryDirectory.resolve("actual-root");
        Files.createDirectory(actual);
        Path link = temporaryDirectory.resolve("linked-root");
        try {
            Files.createSymbolicLink(link, actual);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThatThrownBy(() -> provider(link).prepare(preparationRequest()))
                .isInstanceOf(LocalWorkspaceException.class)
                .hasMessageContaining("real directory");
    }

    @Test
    void resolvesOnlyCanonicalPreparedWorkspaceChildren() throws Exception {
        Path root = temporaryDirectory.resolve("location-resolution");
        LocalWorkspaceProvider provider = provider(root);
        WorkspacePreparationRequest preparation = preparationRequest();
        provider.prepare(preparation);

        LocalWorkspaceLocation location =
                provider.resolve(preparation.workspace().workspaceId());
        assertThat(location.workspaceDirectory())
                .isEqualTo(workspace(root, preparation).toRealPath());
        assertThat(location.sourceDirectory().getParent())
                .isEqualTo(location.workspaceDirectory());
        assertThat(location.sourceDirectory()).isDirectory();

        Files.delete(location.sourceDirectory());
        assertThatThrownBy(() -> provider.resolve(
                preparation.workspace().workspaceId()))
                .isInstanceOf(LocalWorkspaceException.class)
                .hasMessageContaining("child directory");
    }

    private LocalWorkspaceProvider provider(Path root) {
        return new LocalWorkspaceProvider(
                new WorkspaceRootProperties(root.toString()), CLOCK);
    }

    private WorkspacePreparationRequest preparationRequest() {
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()),
                UUID.randomUUID(),
                LocalWorkspaceProvider.PROVIDER_ID);
        return new WorkspacePreparationRequest(
                planned.transitionTo(WorkspaceState.PREPARING, null), null);
    }

    private Path workspace(Path root, WorkspacePreparationRequest request) {
        return root.resolve(request.workspace().workspaceId().value().toString());
    }

    private Object invokePrepare(
            LocalWorkspaceProvider provider,
            WorkspacePreparationRequest request,
            CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return provider.prepare(request).workspace();
        } catch (LocalWorkspaceException exception) {
            return exception;
        }
    }

    private WorkspaceDescriptor invokeRelease(
            LocalWorkspaceProvider provider,
            WorkspaceReleaseRequest request,
            CountDownLatch start) throws InterruptedException {
        start.await();
        return provider.release(request).workspace();
    }

    private void writeText(Path file) {
        try {
            Files.writeString(file, "sentinel");
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
