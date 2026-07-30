package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
