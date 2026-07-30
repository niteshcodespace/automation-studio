package com.automationstudio.api.execution.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceDescriptorTest {

    private static final UUID EXECUTION_ID =
            UUID.fromString("129f5e5d-c817-4eb6-b55d-e53dd56199f8");
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId(
            UUID.fromString("4d544b9d-1e92-4709-ac14-901e3daf098c"));
    private static final WorkspaceProviderId PROVIDER_ID =
            new WorkspaceProviderId("LOCAL-ISOLATED");
    private static final ExecutionSourceReference SOURCE =
            new ExecutionSourceReference(
                    SourceType.GIT_HTTPS,
                    "https://example.com/automation.git",
                    "0123456789abcdef0123456789abcdef01234567",
                    "suites/smoke");
    private static final WorkspaceMetadata METADATA = new WorkspaceMetadata(
            OffsetDateTime.parse("2026-07-30T10:00:00Z"), SOURCE);

    @Test
    void followsTheLifecycleWithoutChangingIdentityOwnershipProviderOrMetadata() {
        WorkspaceDescriptor planned =
                WorkspaceDescriptor.planned(WORKSPACE_ID, EXECUTION_ID, PROVIDER_ID);
        WorkspaceDescriptor preparing =
                planned.transitionTo(WorkspaceState.PREPARING, null);
        WorkspaceDescriptor ready =
                preparing.transitionTo(WorkspaceState.READY, METADATA);
        WorkspaceDescriptor inUse =
                ready.transitionTo(WorkspaceState.IN_USE, null);
        WorkspaceDescriptor releasing =
                inUse.transitionTo(WorkspaceState.RELEASING, METADATA);
        WorkspaceDescriptor released =
                releasing.transitionTo(WorkspaceState.RELEASED, null);

        assertThat(released.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(released.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(released.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(released.metadata()).isSameAs(METADATA);
        assertThat(released.state()).isEqualTo(WorkspaceState.RELEASED);
    }

    @Test
    void hasValueEqualityAndDeterministicJsonSerialization() throws Exception {
        WorkspaceDescriptor first = readyWorkspace();
        WorkspaceDescriptor equal = new WorkspaceDescriptor(
                WORKSPACE_ID, EXECUTION_ID, PROVIDER_ID, WorkspaceState.READY, METADATA);

        assertThat(first).isEqualTo(equal).hasSameHashCodeAs(equal);

        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        String json = mapper.writeValueAsString(first);
        WorkspaceDescriptor roundTrip =
                mapper.readValue(json, WorkspaceDescriptor.class);

        assertThat(roundTrip).isEqualTo(first);
        assertThat(json)
                .contains("\"state\":\"READY\"")
                .contains("\"providerId\":{\"value\":\"local-isolated\"}")
                .doesNotContain("path", "directory", "file:");
    }

    @Test
    void rejectsMissingIdentityOwnershipProviderAndState() {
        assertThatThrownBy(() -> new WorkspaceId(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkspaceDescriptor(
                null, EXECUTION_ID, PROVIDER_ID, WorkspaceState.PLANNED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkspaceDescriptor(
                WORKSPACE_ID, null, PROVIDER_ID, WorkspaceState.PLANNED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkspaceDescriptor(
                WORKSPACE_ID, EXECUTION_ID, null, WorkspaceState.PLANNED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkspaceDescriptor(
                WORKSPACE_ID, EXECUTION_ID, PROVIDER_ID, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void validatesProviderIdentityWithoutEmbeddingImplementationDetails() {
        assertThat(PROVIDER_ID.value()).isEqualTo("local-isolated");
        assertThatThrownBy(() -> new WorkspaceProviderId(" "))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> new WorkspaceProviderId(" local"))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> new WorkspaceProviderId("file://workspace"))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> new WorkspaceProviderId("x".repeat(65)))
                .isInstanceOf(WorkspaceContractException.class);
    }

    @Test
    void enforcesMetadataAvailabilityAndImmutability() {
        assertThatThrownBy(() -> new WorkspaceDescriptor(
                WORKSPACE_ID, EXECUTION_ID, PROVIDER_ID,
                WorkspaceState.PLANNED, METADATA))
                .isInstanceOf(WorkspaceContractException.class);
        assertThatThrownBy(() -> new WorkspaceDescriptor(
                WORKSPACE_ID, EXECUTION_ID, PROVIDER_ID,
                WorkspaceState.READY, null))
                .isInstanceOf(WorkspaceContractException.class);

        WorkspaceMetadata replacement = new WorkspaceMetadata(
                OffsetDateTime.parse("2026-07-30T10:01:00Z"), SOURCE);
        assertThatThrownBy(() -> readyWorkspace()
                .transitionTo(WorkspaceState.IN_USE, replacement))
                .isInstanceOf(WorkspaceContractException.class)
                .hasMessageContaining("must not change");
    }

    @Test
    void allowsSourceIndependentWorkspaceMetadata() {
        WorkspaceMetadata sourceIndependent = new WorkspaceMetadata(
                OffsetDateTime.parse("2026-07-30T10:00:00Z"), null);

        assertThat(sourceIndependent.sourceReference()).isNull();
    }

    private WorkspaceDescriptor readyWorkspace() {
        return WorkspaceDescriptor.planned(WORKSPACE_ID, EXECUTION_ID, PROVIDER_ID)
                .transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(WorkspaceState.READY, METADATA);
    }
}
