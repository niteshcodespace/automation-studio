package com.automationstudio.api.execution.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourcePreparationContractTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-07-30T12:00:00Z");
    private static final String REVISION = "0123456789012345678901234567890123456789";

    @Test
    void contractsAreImmutableAndContainNoFilesystemPaths() {
        assertThat(SourcePreparationRequest.class.isRecord()).isTrue();
        assertThat(SourcePreparationResult.class.isRecord()).isTrue();
        assertThat(Arrays.stream(SourcePreparationRequest.class.getRecordComponents())
                .noneMatch(component -> Path.class.isAssignableFrom(component.getType())))
                .isTrue();
        assertThat(Arrays.stream(SourcePreparationResult.class.getRecordComponents())
                .noneMatch(component -> Path.class.isAssignableFrom(component.getType())))
                .isTrue();
    }

    @Test
    void resultRejectsIncoherentWorkspaceAndSourceEvidence() {
        ExecutionSourceReference source = source();
        WorkspaceDescriptor planned = planned();
        WorkspaceDescriptor ready = planned.transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(
                        WorkspaceState.READY,
                        new WorkspaceMetadata(NOW, source));
        SourceMaterializationResult mismatched = new SourceMaterializationResult(
                new WorkspaceId(UUID.randomUUID()),
                SourceType.GIT_HTTPS,
                REVISION,
                SourceMaterializationState.MATERIALIZED,
                NOW);

        assertThatThrownBy(() -> new SourcePreparationResult(
                ready, mismatched, SourcePreparationState.PREPARED, NOW))
                .isInstanceOf(SourcePreparationException.class)
                .satisfies(exception -> assertThat(
                        ((SourcePreparationException) exception).code())
                        .isEqualTo("PREPARATION_INVARIANT_VIOLATION"));
    }

    @Test
    void requestRequiresAPlannedWorkspace() {
        WorkspaceDescriptor preparing =
                planned().transitionTo(WorkspaceState.PREPARING, null);

        assertThatThrownBy(() -> new SourcePreparationRequest(preparing, source()))
                .isInstanceOf(RuntimeException.class);
    }

    private WorkspaceDescriptor planned() {
        return WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()),
                UUID.randomUUID(),
                new WorkspaceProviderId("contract-test"));
    }

    private ExecutionSourceReference source() {
        return new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://example.invalid/repository.git",
                REVISION,
                null);
    }
}
