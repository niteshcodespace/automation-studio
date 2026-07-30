package com.automationstudio.api.execution.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceManagerTest {

    private static final WorkspaceProviderId PROVIDER_ID =
            new WorkspaceProviderId("manager-test");

    @Test
    void coordinatesLifecycleUsingOnlyTheProviderPort() {
        RecordingProvider provider = new RecordingProvider(false);
        WorkspaceManager manager = new WorkspaceManager(provider);
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()), UUID.randomUUID(), PROVIDER_ID);

        WorkspaceDescriptor ready = manager.prepare(planned, null);
        WorkspaceDescriptor released = manager.release(ready);

        assertThat(ready.state()).isEqualTo(WorkspaceState.READY);
        assertThat(released.state()).isEqualTo(WorkspaceState.RELEASED);
        assertThat(provider.prepareCalls).isEqualTo(1);
        assertThat(provider.releaseCalls).isEqualTo(1);
        assertThat(manager.release(released)).isSameAs(released);
        assertThat(provider.releaseCalls).isEqualTo(1);
    }

    @Test
    void rejectsWrongProviderAndInvalidManagerStates() {
        WorkspaceManager manager =
                new WorkspaceManager(new RecordingProvider(false));
        WorkspaceDescriptor foreign = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()),
                UUID.randomUUID(),
                new WorkspaceProviderId("foreign"));
        WorkspaceDescriptor owned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()), UUID.randomUUID(), PROVIDER_ID);

        assertThatThrownBy(() -> manager.prepare(foreign, null))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> manager.release(owned))
                .isInstanceOf(WorkspaceContractException.class);
    }

    @Test
    void translatesProviderFailuresWithoutExposingImplementationDetails() {
        WorkspaceManager manager =
                new WorkspaceManager(new RecordingProvider(true));
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()), UUID.randomUUID(), PROVIDER_ID);

        assertThatThrownBy(() -> manager.prepare(planned, null))
                .isInstanceOf(WorkspaceManagementException.class)
                .hasMessage("Workspace preparation failed")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private static final class RecordingProvider implements WorkspaceProvider {

        private final boolean fail;
        private int prepareCalls;
        private int releaseCalls;

        private RecordingProvider(boolean fail) {
            this.fail = fail;
        }

        @Override
        public WorkspaceProviderId providerId() {
            return PROVIDER_ID;
        }

        @Override
        public WorkspacePreparationResult prepare(
                WorkspacePreparationRequest request) {
            prepareCalls++;
            if (fail) {
                throw new IllegalStateException("implementation detail");
            }
            WorkspaceMetadata metadata = new WorkspaceMetadata(
                    OffsetDateTime.parse("2026-07-30T10:00:00Z"),
                    request.sourceReference());
            return new WorkspacePreparationResult(
                    request,
                    request.workspace().transitionTo(WorkspaceState.READY, metadata));
        }

        @Override
        public WorkspaceReleaseResult release(WorkspaceReleaseRequest request) {
            releaseCalls++;
            return new WorkspaceReleaseResult(
                    request,
                    request.workspace().transitionTo(WorkspaceState.RELEASED, null));
        }
    }
}
