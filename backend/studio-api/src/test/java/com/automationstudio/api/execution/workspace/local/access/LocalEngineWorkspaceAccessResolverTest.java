package com.automationstudio.api.execution.workspace.local.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceProvider;
import com.automationstudio.api.execution.workspace.local.WorkspaceRootProperties;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalEngineWorkspaceAccessResolverTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);
    private static final String REVISION = "0123456789012345678901234567890123456789";

    @TempDir
    Path temporaryDirectory;

    private Path root;
    private LocalWorkspaceProvider provider;
    private WorkspaceManager manager;
    private EngineWorkspaceAccessResolver resolver;

    @BeforeEach
    void setUp() {
        root = temporaryDirectory.resolve("workspaces");
        provider = new LocalWorkspaceProvider(
                new WorkspaceRootProperties(root.toString()), CLOCK);
        manager = new WorkspaceManager(provider);
        resolver = new LocalEngineWorkspaceAccessResolver(provider);
    }

    @Test
    void exposesOnlyApprovedContainedLocationsAndCloseDoesNotRelease() throws Exception {
        SourcePreparationResult preparation = prepare();
        Path workspace = root.resolve(
                preparation.workspace().workspaceId().value().toString());

        EngineWorkspaceAccess access =
                resolver.open(EngineWorkspaceAccessRequest.from(preparation));

        assertThat(access.isOpen()).isTrue();
        assertThat(access.workspaceId()).isEqualTo(preparation.workspace().workspaceId());
        assertApproved(access.sourceDirectory(), workspace, "source");
        assertApproved(access.artifactsDirectory(), workspace, "artifacts");
        assertApproved(access.metadataDirectory(), workspace, "metadata");
        assertApproved(access.temporaryDirectory(), workspace, "temp");
        assertThat(EngineWorkspaceAccess.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("resolve", "workspaceDirectory", "rootDirectory");

        access.close();
        access.close();

        assertThat(access.isOpen()).isFalse();
        assertClosed(access::workspaceId);
        assertClosed(access::sourceDirectory);
        assertClosed(access::artifactsDirectory);
        assertClosed(access::metadataDirectory);
        assertClosed(access::temporaryDirectory);
        assertThat(workspace).exists();
    }

    @Test
    void missingWorkspaceFailsClosed() {
        SourcePreparationResult preparation = evidence(UUID.randomUUID());

        assertCode(
                () -> resolver.open(EngineWorkspaceAccessRequest.from(preparation)),
                "WORKSPACE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "artifacts", "metadata", "temp"})
    void missingChildDirectoryFailsClosed(String child) throws Exception {
        SourcePreparationResult preparation = prepare();
        Files.delete(workspace(preparation).resolve(child));

        assertCode(
                () -> resolver.open(EngineWorkspaceAccessRequest.from(preparation)),
                "WORKSPACE_LAYOUT_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "artifacts", "metadata", "temp"})
    void childRegularFileFailsClosed(String child) throws Exception {
        SourcePreparationResult preparation = prepare();
        Path location = workspace(preparation).resolve(child);
        Files.delete(location);
        Files.writeString(location, "not-a-directory");

        assertCode(
                () -> resolver.open(EngineWorkspaceAccessRequest.from(preparation)),
                "WORKSPACE_LAYOUT_INVALID");
    }

    @Test
    void workspaceRegularFileFailsClosed() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        SourcePreparationResult preparation = evidence(workspaceId);
        Files.createDirectories(root);
        Files.writeString(root.resolve(workspaceId.toString()), "not-a-directory");

        assertCode(
                () -> resolver.open(EngineWorkspaceAccessRequest.from(preparation)),
                "WORKSPACE_LAYOUT_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "artifacts", "metadata", "temp"})
    void childSymlinkIsRejected(String child) throws Exception {
        SourcePreparationResult preparation = prepare();
        Path external = temporaryDirectory.resolve("external-" + child);
        Files.createDirectory(external);
        Path location = workspace(preparation).resolve(child);
        Files.delete(location);
        createSymbolicLinkOrSkip(location, external);

        assertCode(
                () -> resolver.open(EngineWorkspaceAccessRequest.from(preparation)),
                "WORKSPACE_PATH_ESCAPE_DETECTED");
    }

    @Test
    void nestedSymlinkIsRejected() throws Exception {
        SourcePreparationResult preparation = prepare();
        Path external = temporaryDirectory.resolve("external-nested");
        Files.createDirectory(external);
        Path link = workspace(preparation).resolve("source").resolve("escape");
        createSymbolicLinkOrSkip(link, external);

        assertCode(
                () -> resolver.open(EngineWorkspaceAccessRequest.from(preparation)),
                "WORKSPACE_PATH_ESCAPE_DETECTED");
    }

    @Test
    void separateHandlesRemainIndependentUnderConcurrentUse() throws Exception {
        SourcePreparationResult first = prepare();
        SourcePreparationResult second = prepare();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(
                    () -> resolver.open(EngineWorkspaceAccessRequest.from(first)));
            var secondFuture = executor.submit(
                    () -> resolver.open(EngineWorkspaceAccessRequest.from(second)));
            EngineWorkspaceAccess firstAccess = firstFuture.get(10, TimeUnit.SECONDS);
            EngineWorkspaceAccess secondAccess = secondFuture.get(10, TimeUnit.SECONDS);

            firstAccess.close();
            assertThat(firstAccess.isOpen()).isFalse();
            assertThat(secondAccess.isOpen()).isTrue();
            assertThat(secondAccess.sourceDirectory()).exists();
            secondAccess.close();
        }
    }

    @Test
    void concurrentCloseIsSafeAndIdempotent() throws Exception {
        SourcePreparationResult preparation = prepare();
        EngineWorkspaceAccess access =
                resolver.open(EngineWorkspaceAccessRequest.from(preparation));
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Callable<Void>> closes =
                    java.util.stream.IntStream.range(0, 100)
                            .mapToObj(ignored -> (java.util.concurrent.Callable<Void>) () -> {
                                access.close();
                                return null;
                            })
                            .toList();
            for (var future : executor.invokeAll(closes)) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(access.isOpen()).isFalse();
    }

    private SourcePreparationResult prepare() {
        UUID workspaceId = UUID.randomUUID();
        SourcePreparationResult evidence = evidence(workspaceId);
        WorkspaceDescriptor ready = manager.prepare(
                WorkspaceDescriptor.planned(
                        evidence.workspace().workspaceId(),
                        evidence.executionId(),
                        LocalWorkspaceProvider.PROVIDER_ID),
                evidence.workspace().metadata().sourceReference());
        return new SourcePreparationResult(
                ready,
                evidence.source(),
                SourcePreparationState.PREPARED,
                OffsetDateTime.now(CLOCK));
    }

    private SourcePreparationResult evidence(UUID workspaceUuid) {
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://example.invalid/repository.git",
                REVISION,
                null);
        WorkspaceDescriptor ready = WorkspaceDescriptor.planned(
                        new WorkspaceId(workspaceUuid),
                        UUID.randomUUID(),
                        LocalWorkspaceProvider.PROVIDER_ID)
                .transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(
                        WorkspaceState.READY,
                        new com.automationstudio.api.execution.workspace.WorkspaceMetadata(
                                OffsetDateTime.now(CLOCK), source));
        SourceMaterializationResult materialized = new SourceMaterializationResult(
                ready.workspaceId(),
                SourceType.GIT_HTTPS,
                REVISION,
                SourceMaterializationState.MATERIALIZED,
                OffsetDateTime.now(CLOCK));
        return new SourcePreparationResult(
                ready, materialized, SourcePreparationState.PREPARED, OffsetDateTime.now(CLOCK));
    }

    private Path workspace(SourcePreparationResult preparation) {
        return root.resolve(preparation.workspace().workspaceId().value().toString());
    }

    private void assertApproved(
            Path actual,
            Path workspace,
            String child) throws Exception {
        assertThat(actual).isEqualTo(workspace.resolve(child).toRealPath());
        assertThat(actual.startsWith(workspace.toRealPath())).isTrue();
    }

    private void assertClosed(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertCode(callable, "WORKSPACE_ACCESS_CLOSED");
    }

    private void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            String code) {
        assertThatThrownBy(callable)
                .isInstanceOf(EngineWorkspaceAccessException.class)
                .hasMessageNotContaining(root.toString())
                .satisfies(exception -> assertThat(
                        ((EngineWorkspaceAccessException) exception).code())
                        .isEqualTo(code));
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception failure) {
            Assumptions.abort("Symbolic links are unavailable: " + failure.getMessage());
        }
    }
}
