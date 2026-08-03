package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.EngineExecutionState;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import com.automationstudio.api.source.ExecutionSourceReference;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunnerPipelineCoordinatorTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID CLAIM_TOKEN = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-03T10:00:00Z");
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Mock private RunnerExecutionService runnerExecutionService;
    @Mock private ExecutionOrchestrator orchestrator;
    @Mock private AdmittedSourceSnapshotMapper mapper;
    @Mock private ExecutionContext context;

    private RunnerPipelineCoordinator coordinator;
    private RunnerExecutionRequest request;

    @BeforeEach
    void setUp() {
        coordinator = new RunnerPipelineCoordinatorImpl(
                runnerExecutionService,
                orchestrator,
                mapper,
                new WorkspaceProviderId("controlled"));
        request = new RunnerExecutionRequest(EXECUTION_ID, "runner", CLAIM_TOKEN, 3, 4, 5);
    }

    @Test
    void mapsAdmittedSnapshotAndPersistsPassedThroughUpdatedFence() {
        Map<String, Object> snapshot = Map.of("revision", REVISION);
        when(context.executionId()).thenReturn(EXECUTION_ID);
        when(runnerExecutionService.start(request)).thenReturn(start(snapshot));
        ExecutionSourceReference source = source();
        when(mapper.map(snapshot)).thenReturn(source);
        when(orchestrator.execute(any())).thenReturn(orchestration(EngineExecutionState.SUCCEEDED));
        when(runnerExecutionService.complete(any(), eq(ExecutionStatus.PASSED)))
                .thenReturn(completion(ExecutionStatus.PASSED));

        RunnerPipelineResult result = coordinator.execute(request);

        assertThat(result.completion().status()).isEqualTo(ExecutionStatus.PASSED);
        ArgumentCaptor<ExecutionOrchestrationRequest> orchestrationRequest =
                ArgumentCaptor.forClass(ExecutionOrchestrationRequest.class);
        verify(orchestrator).execute(orchestrationRequest.capture());
        assertThat(orchestrationRequest.getValue().preparationRequest().sourceReference())
                .isSameAs(source);
        ArgumentCaptor<RunnerExecutionRequest> completionRequest =
                ArgumentCaptor.forClass(RunnerExecutionRequest.class);
        verify(runnerExecutionService).complete(completionRequest.capture(),
                org.mockito.ArgumentMatchers.eq(ExecutionStatus.PASSED));
        assertThat(completionRequest.getValue().expectedExecutionVersion()).isEqualTo(6);
        assertThat(completionRequest.getValue().expectedLeaseVersion()).isEqualTo(7);
    }

    @Test
    void mapsAssertionFailureToFailedAndInfrastructureFailureToError() {
        when(context.executionId()).thenReturn(EXECUTION_ID);
        when(runnerExecutionService.start(request)).thenReturn(start(Map.of()));
        when(mapper.map(Map.of())).thenReturn(source());
        when(orchestrator.execute(any())).thenReturn(orchestration(EngineExecutionState.FAILED));
        when(runnerExecutionService.complete(any(), eq(ExecutionStatus.FAILED)))
                .thenReturn(completion(ExecutionStatus.FAILED));

        assertThat(coordinator.execute(request).completion().status())
                .isEqualTo(ExecutionStatus.FAILED);
        verify(runnerExecutionService).complete(any(),
                org.mockito.ArgumentMatchers.eq(ExecutionStatus.FAILED));

        when(orchestrator.execute(any())).thenThrow(new IllegalStateException("private-value"));
        when(runnerExecutionService.complete(any(), eq(ExecutionStatus.ERROR)))
                .thenReturn(completion(ExecutionStatus.ERROR));
        assertThat(coordinator.execute(request).completion().status())
                .isEqualTo(ExecutionStatus.ERROR);
    }

    @Test
    void lostOwnershipNeverAttemptsASecondTerminalWrite() {
        when(runnerExecutionService.start(request)).thenThrow(
                new ExecutionOwnershipException("ownership lost"));

        assertThatThrownBy(() -> coordinator.execute(request))
                .isInstanceOf(ExecutionOwnershipException.class);

        verify(runnerExecutionService, never()).complete(any(), any());
        verify(orchestrator, never()).execute(any());
    }

    @Test
    void cancellationIsNotReinterpretedAsInfrastructureError() {
        when(context.executionId()).thenReturn(EXECUTION_ID);
        when(runnerExecutionService.start(request)).thenReturn(start(Map.of()));
        when(mapper.map(Map.of())).thenReturn(source());
        when(orchestrator.execute(any())).thenReturn(orchestration(EngineExecutionState.CANCELLED));

        assertThatThrownBy(() -> coordinator.execute(request))
                .isInstanceOfSatisfying(RunnerPipelineException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CANCELLATION_REQUIRES_LIFECYCLE"));
        verify(runnerExecutionService, never()).complete(any(), any());
    }

    private ExecutionStartResult start(Map<String, Object> snapshot) {
        return new ExecutionStartResult(EXECUTION_ID, ExecutionStatus.RUNNING, 6, 3, 7,
                NOW, context, new ExecutionEngineDescriptor(
                        "playwright", "1", "Playwright", Set.of(), Set.of()), snapshot);
    }

    private ExecutionCompletionResult completion(ExecutionStatus status) {
        return new ExecutionCompletionResult(EXECUTION_ID, status, 7, 3, 8, NOW);
    }

    private ExecutionOrchestrationResult orchestration(EngineExecutionState state) {
        EngineExecutionResult result = new EngineExecutionResult(
                EXECUTION_ID, "playwright", "1", new WorkspaceId(EXECUTION_ID),
                REVISION, state, NOW, NOW, Duration.ZERO);
        return new ExecutionOrchestrationResult(result, NOW);
    }

    private ExecutionSourceReference source() {
        return new ExecutionSourceReference(
                com.automationstudio.api.source.SourceType.GIT_HTTPS,
                "https://example.test/repository.git", REVISION, "demo/source");
    }
}
