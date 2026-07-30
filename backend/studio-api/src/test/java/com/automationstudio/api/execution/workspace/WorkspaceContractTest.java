package com.automationstudio.api.execution.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceContractTest {

    private static final WorkspaceId WORKSPACE_ID =
            new WorkspaceId(UUID.fromString("4d544b9d-1e92-4709-ac14-901e3daf098c"));
    private static final UUID EXECUTION_ID =
            UUID.fromString("129f5e5d-c817-4eb6-b55d-e53dd56199f8");
    private static final WorkspaceProviderId PROVIDER_ID =
            new WorkspaceProviderId("test-provider");
    private static final WorkspaceMetadata METADATA =
            new WorkspaceMetadata(OffsetDateTime.parse("2026-07-30T10:00:00Z"), null);

    @Test
    void permitsOnlyTheApprovedLifecycle() {
        assertThat(WorkspaceState.PLANNED.canTransitionTo(WorkspaceState.PREPARING)).isTrue();
        assertThat(WorkspaceState.PREPARING.canTransitionTo(WorkspaceState.READY)).isTrue();
        assertThat(WorkspaceState.READY.canTransitionTo(WorkspaceState.IN_USE)).isTrue();
        assertThat(WorkspaceState.IN_USE.canTransitionTo(WorkspaceState.RELEASING)).isTrue();
        assertThat(WorkspaceState.RELEASING.canTransitionTo(WorkspaceState.RELEASED)).isTrue();
        assertThat(WorkspaceState.RELEASED.canTransitionTo(WorkspaceState.PLANNED)).isFalse();
        assertThat(WorkspaceState.READY.canTransitionTo(WorkspaceState.RELEASED)).isFalse();
        assertThat(WorkspaceState.PLANNED.canTransitionTo(null)).isFalse();

        assertThatThrownBy(() -> planned().transitionTo(WorkspaceState.READY, METADATA))
                .isInstanceOf(WorkspaceContractException.class);
    }

    @Test
    void validatesPreparationAndReleaseBoundaries() {
        WorkspaceDescriptor preparing =
                planned().transitionTo(WorkspaceState.PREPARING, null);
        WorkspacePreparationRequest preparation =
                new WorkspacePreparationRequest(preparing, null);
        WorkspaceDescriptor ready =
                preparing.transitionTo(WorkspaceState.READY, METADATA);

        assertThat(new WorkspacePreparationResult(preparation, ready).workspace())
                .isEqualTo(ready);
        assertThat(preparation.sourceReference()).isNull();

        WorkspaceDescriptor inUse = ready.transitionTo(WorkspaceState.IN_USE, null);
        WorkspaceDescriptor releasing =
                inUse.transitionTo(WorkspaceState.RELEASING, null);
        WorkspaceReleaseRequest release = new WorkspaceReleaseRequest(releasing);
        WorkspaceDescriptor released =
                releasing.transitionTo(WorkspaceState.RELEASED, null);

        assertThat(release.workspace()).isEqualTo(releasing);
        assertThat(new WorkspaceReleaseResult(release, released).workspace())
                .isEqualTo(released);

        assertThatThrownBy(() -> new WorkspacePreparationRequest(planned(), null))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> new WorkspacePreparationResult(preparation, preparing))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> new WorkspaceReleaseRequest(inUse))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> new WorkspaceReleaseResult(release, releasing))
                .isInstanceOf(WorkspaceContractException.class);
    }

    @Test
    void rejectsProviderResultsForAnotherWorkspaceOrExecution() {
        WorkspaceDescriptor preparing =
                planned().transitionTo(WorkspaceState.PREPARING, null);
        WorkspacePreparationRequest request =
                new WorkspacePreparationRequest(preparing, null);
        WorkspaceDescriptor foreignReady = WorkspaceDescriptor.planned(
                        new WorkspaceId(UUID.randomUUID()), EXECUTION_ID, PROVIDER_ID)
                .transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(WorkspaceState.READY, METADATA);

        assertThatThrownBy(() -> new WorkspacePreparationResult(request, foreignReady))
                .isInstanceOf(WorkspaceContractException.class)
                .hasMessageContaining("identity and ownership");
    }

    @Test
    void providerPortHasNoImplementationOrFilesystemTypeLeakage() {
        assertThat(WorkspaceProvider.class.isInterface()).isTrue();
        assertThat(Modifier.isPublic(WorkspaceProvider.class.getModifiers())).isTrue();

        List<Class<?>> contractTypes = List.of(
                WorkspaceId.class,
                WorkspaceProviderId.class,
                WorkspaceState.class,
                WorkspaceMetadata.class,
                WorkspaceDescriptor.class,
                WorkspacePreparationRequest.class,
                WorkspacePreparationResult.class,
                WorkspaceReleaseRequest.class,
                WorkspaceReleaseResult.class,
                WorkspaceProvider.class);

        assertThat(contractTypes)
                .allSatisfy(type -> assertThat(type.getDeclaredFields())
                        .noneSatisfy(field -> assertThat(field.getType())
                                .isIn(Path.class, java.io.File.class)));
        assertThat(contractTypes.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(Method::getReturnType))
                .doesNotContain(Path.class, java.io.File.class);
        assertThat(contractTypes.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .doesNotContain(Path.class, java.io.File.class);
    }

    private WorkspaceDescriptor planned() {
        return WorkspaceDescriptor.planned(WORKSPACE_ID, EXECUTION_ID, PROVIDER_ID);
    }
}
