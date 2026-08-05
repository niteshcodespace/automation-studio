package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EngineExecutionContractTest {

    private static final OffsetDateTime START =
            OffsetDateTime.parse("2026-07-30T12:00:00Z");

    @Test
    void publicEngineContractsAreImmutableAndPathFree() {
        assertThat(EngineExecutionRequest.class.isRecord()).isTrue();
        assertThat(EngineExecutionResult.class.isRecord()).isTrue();
        assertThat(Arrays.stream(EngineExecutionRequest.class.getRecordComponents())
                .noneMatch(component -> Path.class.isAssignableFrom(component.getType())))
                .isTrue();
        assertThat(Arrays.stream(EngineExecutionResult.class.getRecordComponents())
                .noneMatch(component -> Path.class.isAssignableFrom(component.getType())))
                .isTrue();
        assertThat(Arrays.stream(EngineExecutionResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("stdout", "stderr", "credentials", "environment", "repository");
    }

    @Test
    void validatesRequiredFieldsTimestampsAndDuration() {
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        assertThatThrownBy(() -> new EngineExecutionResult(
                null, "dummy", "1", workspaceId, "revision",
                EngineExecutionState.SUCCEEDED, START, START, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineExecutionResult(
                UUID.randomUUID(), "dummy", "1", workspaceId, "revision",
                null, START, START, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineExecutionResult(
                UUID.randomUUID(), "dummy", "1", workspaceId, "revision",
                EngineExecutionState.SUCCEEDED, START, START.minusSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineExecutionResult(
                UUID.randomUUID(), "dummy", "1", workspaceId, "revision",
                EngineExecutionState.SUCCEEDED, START, START, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineExecutionResult(
                UUID.randomUUID(), "dummy", "1", workspaceId, "revision",
                EngineExecutionState.SUCCEEDED, START, START.plusSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("deprecation")
    @Test
    void usesCanonicalIdentityAndRetainsReadOnlyCompatibilityAliases() {
        EngineExecutionResult result = result();

        assertThat(result.engineId()).isEqualTo("dummy");
        assertThat(result.implementationVersion()).isEqualTo("1");
        assertThat(result.engineName()).isEqualTo(result.engineId());
        assertThat(result.engineVersion()).isEqualTo(result.implementationVersion());
        assertThat(Arrays.stream(EngineExecutionResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("engineId", "implementationVersion")
                .doesNotContain("engineName", "engineVersion");
    }

    @Test
    void validatesExactCorrelationToCanonicalRequestAndDescriptor() {
        EngineExecutionResult result = result();
        EngineExecutionRequest request = mock(EngineExecutionRequest.class);
        SourcePreparationResult preparation = mock(SourcePreparationResult.class);
        com.automationstudio.api.execution.workspace.WorkspaceDescriptor workspace =
                mock(com.automationstudio.api.execution.workspace.WorkspaceDescriptor.class);
        com.automationstudio.api.source.materialization.SourceMaterializationResult source =
                mock(com.automationstudio.api.source.materialization.SourceMaterializationResult.class);
        when(request.executionId()).thenReturn(result.executionId());
        when(request.preparation()).thenReturn(preparation);
        when(preparation.workspace()).thenReturn(workspace);
        when(preparation.source()).thenReturn(source);
        when(workspace.workspaceId()).thenReturn(result.workspaceId());
        when(source.resolvedRevision()).thenReturn(result.resolvedRevision());
        ExecutionEngineDescriptor descriptor = new ExecutionEngineDescriptor(
                result.engineId(), result.implementationVersion(), "Dummy",
                java.util.Set.of(), java.util.Set.of());

        assertThat(result.validateFor(request, descriptor)).isSameAs(result);
        when(request.executionId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> result.validateFor(request, descriptor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Engine result does not match its execution request");
    }

    @Test
    void rendersOnlyBoundedSanitizedIdentity() {
        EngineExecutionResult result = result();

        assertThat(result.toString())
                .contains("executionId=", "engineId=dummy", "state=SUCCEEDED")
                .doesNotContain("resolvedRevision", "revision-canary", "startedAt", "finishedAt");
    }

    private EngineExecutionResult result() {
        return new EngineExecutionResult(
                UUID.randomUUID(), "dummy", "1", new WorkspaceId(UUID.randomUUID()),
                "revision-canary", EngineExecutionState.SUCCEEDED,
                START, START, Duration.ZERO);
    }
}
