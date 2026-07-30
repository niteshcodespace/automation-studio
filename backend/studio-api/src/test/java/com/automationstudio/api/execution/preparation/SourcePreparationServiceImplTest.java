package com.automationstudio.api.execution.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationException;
import com.automationstudio.api.source.materialization.SourceMaterializationRequest;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import com.automationstudio.api.source.materialization.SourceMaterializationState;
import com.automationstudio.api.source.materialization.SourceMaterializer;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SourcePreparationServiceImplTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime PREPARED_AT =
            OffsetDateTime.parse("2026-07-30T11:59:00Z");
    private static final String REVISION = "0123456789012345678901234567890123456789";

    private WorkspaceManager workspaceManager;
    private SourceMaterializer sourceMaterializer;
    private SourcePreparationService service;
    private SourcePreparationRequest request;
    private WorkspaceDescriptor ready;
    private SourceMaterializationResult materialized;

    @BeforeEach
    void setUp() {
        workspaceManager = mock(WorkspaceManager.class);
        sourceMaterializer = mock(SourceMaterializer.class);
        service = new SourcePreparationServiceImpl(workspaceManager, sourceMaterializer, CLOCK);
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS, "https://example.invalid/repository.git", REVISION, null);
        WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                new WorkspaceId(UUID.randomUUID()),
                UUID.randomUUID(),
                new WorkspaceProviderId("local"));
        request = new SourcePreparationRequest(planned, source);
        ready = planned.transitionTo(
                WorkspaceState.PREPARING, null)
                .transitionTo(WorkspaceState.READY, new WorkspaceMetadata(PREPARED_AT, source));
        materialized = new SourceMaterializationResult(
                ready.workspaceId(),
                SourceType.GIT_HTTPS,
                REVISION,
                SourceMaterializationState.MATERIALIZED,
                PREPARED_AT);
    }

    @Test
    void preparesWorkspaceBeforeMaterializingAndReturnsPathFreeEvidence() {
        when(workspaceManager.prepare(request.workspace(), request.sourceReference()))
                .thenReturn(ready);
        when(sourceMaterializer.materialize(any())).thenReturn(materialized);

        SourcePreparationResult result = service.prepare(request);

        assertThat(result.workspace()).isEqualTo(ready);
        assertThat(result.source()).isEqualTo(materialized);
        assertThat(result.state()).isEqualTo(SourcePreparationState.PREPARED);
        assertThat(result.preparedAt().toInstant()).isEqualTo(CLOCK.instant());
        assertThat(result.executionId()).isEqualTo(request.executionId());
        InOrder order = inOrder(workspaceManager, sourceMaterializer);
        order.verify(workspaceManager).prepare(request.workspace(), request.sourceReference());
        order.verify(sourceMaterializer).materialize(
                new SourceMaterializationRequest(ready.workspaceId(), request.sourceReference()));
        verify(workspaceManager, never()).release(any());
    }

    @Test
    void stopsWhenWorkspacePreparationFails() {
        when(workspaceManager.prepare(any(), any()))
                .thenThrow(new IllegalStateException("provider path C:/secret"));

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(SourcePreparationException.class)
                .hasMessage("Workspace preparation failed")
                .hasMessageNotContaining("secret")
                .satisfies(exception -> assertThat(
                        ((SourcePreparationException) exception).code())
                        .isEqualTo("WORKSPACE_PREPARATION_FAILED"));
        verify(sourceMaterializer, never()).materialize(any());
        verify(workspaceManager, never()).release(any());
    }

    @Test
    void compensatesMaterializationFailureAndPreservesCause() {
        SourceMaterializationException failure =
                new SourceMaterializationException("CLONE_FAILED", "sensitive repository");
        when(workspaceManager.prepare(any(), any())).thenReturn(ready);
        when(sourceMaterializer.materialize(any())).thenThrow(failure);
        when(workspaceManager.release(ready)).thenReturn(released());

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(SourcePreparationException.class)
                .hasMessage("Source materialization failed")
                .hasCause(failure)
                .extracting(exception -> ((SourcePreparationException) exception).code())
                .isEqualTo("SOURCE_MATERIALIZATION_FAILED");
        verify(workspaceManager).release(ready);
    }

    @Test
    void cleanupFailureTakesPrecedenceAndRetainsOriginalAsSuppressed() {
        RuntimeException cleanup = new IllegalStateException("private workspace path");
        when(workspaceManager.prepare(any(), any())).thenReturn(ready);
        when(sourceMaterializer.materialize(any()))
                .thenThrow(new IllegalStateException("private repository"));
        when(workspaceManager.release(ready)).thenThrow(cleanup);

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(SourcePreparationException.class)
                .hasMessage("Workspace compensation failed")
                .hasCause(cleanup)
                .satisfies(exception -> {
                    SourcePreparationException preparation =
                            (SourcePreparationException) exception;
                    assertThat(preparation.code()).isEqualTo("WORKSPACE_COMPENSATION_FAILED");
                    assertThat(cleanup.getSuppressed()).hasSize(1);
                    assertThat(cleanup.getSuppressed()[0])
                            .isInstanceOf(SourcePreparationException.class);
                });
    }

    @Test
    void rejectsAndCompensatesMismatchedMaterializationEvidence() {
        SourceMaterializationResult mismatch = new SourceMaterializationResult(
                new WorkspaceId(UUID.randomUUID()),
                SourceType.GIT_HTTPS,
                REVISION,
                SourceMaterializationState.MATERIALIZED,
                PREPARED_AT);
        when(workspaceManager.prepare(any(), any())).thenReturn(ready);
        when(sourceMaterializer.materialize(any())).thenReturn(mismatch);
        when(workspaceManager.release(ready)).thenReturn(released());

        assertFailure("PREPARATION_INVARIANT_VIOLATION",
                "Source preparation returned inconsistent evidence");
        verify(workspaceManager).release(ready);
    }

    @Test
    void rejectsNullWorkspaceEvidenceWithoutCallingDownstream() {
        when(workspaceManager.prepare(any(), any())).thenReturn(null);

        assertFailure("PREPARATION_INVARIANT_VIOLATION",
                "Source preparation returned inconsistent evidence");
        verify(sourceMaterializer, never()).materialize(any());
    }

    private void assertFailure(String code, String message) {
        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(SourcePreparationException.class)
                .hasMessage(message)
                .satisfies(exception -> assertThat(
                        ((SourcePreparationException) exception).code())
                        .isEqualTo(code));
    }

    private WorkspaceDescriptor released() {
        return ready.transitionTo(WorkspaceState.IN_USE, null)
                .transitionTo(WorkspaceState.RELEASING, null)
                .transitionTo(WorkspaceState.RELEASED, null);
    }
}
