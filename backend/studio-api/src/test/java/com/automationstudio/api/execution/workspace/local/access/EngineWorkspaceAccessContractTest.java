package com.automationstudio.api.execution.workspace.local.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
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
import tools.jackson.databind.ObjectMapper;

class EngineWorkspaceAccessContractTest {

    @Test
    void trustedRequestIsImmutableAndPathFree() {
        assertThat(java.lang.reflect.Modifier.isFinal(
                EngineWorkspaceAccessRequest.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(EngineWorkspaceAccessRequest.class.getMethods())
                .noneMatch(method -> Path.class.isAssignableFrom(method.getReturnType())))
                .isTrue();
        assertThat(Arrays.stream(EngineWorkspaceAccessRequest.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("repository", "credentials", "path", "preparation");
    }

    @Test
    void nullOrIncompletePreparationFailsWithStableCodes() {
        assertCode(
                () -> EngineWorkspaceAccessRequest.from(null),
                "INVALID_ACCESS_REQUEST");
        SourcePreparationResult incomplete = mock(SourcePreparationResult.class);
        when(incomplete.state()).thenReturn(SourcePreparationState.PREPARED);
        assertCode(
                () -> EngineWorkspaceAccessRequest.from(incomplete),
                "WORKSPACE_NOT_PREPARED");
    }

    @Test
    void serializationCannotExposePrivatePreparationOrRepositoryMetadata() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-30T12:00:00Z");
        String revision = "0123456789012345678901234567890123456789";
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://user:secret@example.invalid/private.git",
                revision,
                null);
        WorkspaceDescriptor ready = WorkspaceDescriptor.planned(
                        new WorkspaceId(UUID.randomUUID()),
                        UUID.randomUUID(),
                        new WorkspaceProviderId("local-filesystem"))
                .transitionTo(WorkspaceState.PREPARING, null)
                .transitionTo(WorkspaceState.READY, new WorkspaceMetadata(now, source));
        SourcePreparationResult preparation = new SourcePreparationResult(
                ready,
                new SourceMaterializationResult(
                        ready.workspaceId(),
                        SourceType.GIT_HTTPS,
                        revision,
                        SourceMaterializationState.MATERIALIZED,
                        now),
                SourcePreparationState.PREPARED,
                now);

        String json = new ObjectMapper().writeValueAsString(
                EngineWorkspaceAccessRequest.from(preparation));

        assertThat(json)
                .doesNotContain("repository", "example.invalid", "secret", "preparation", "Path");
    }

    private void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            String code) {
        assertThatThrownBy(callable)
                .isInstanceOf(EngineWorkspaceAccessException.class)
                .satisfies(exception -> assertThat(
                        ((EngineWorkspaceAccessException) exception).code())
                        .isEqualTo(code));
    }
}
