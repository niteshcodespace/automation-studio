package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.api.execution.preparation.SourcePreparationRequest;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceProviderId;
import java.util.Objects;

public final class RunnerPipelineCoordinatorImpl implements RunnerPipelineCoordinator {

    private final RunnerExecutionService runnerExecutionService;
    private final ExecutionOrchestrator executionOrchestrator;
    private final ExecutionOrchestratorImpl platformExecutionOrchestrator;
    private final AdmittedSourceSnapshotMapper sourceSnapshotMapper;
    private final WorkspaceProviderId workspaceProviderId;

    public RunnerPipelineCoordinatorImpl(
            RunnerExecutionService runnerExecutionService,
            ExecutionOrchestrator executionOrchestrator,
            AdmittedSourceSnapshotMapper sourceSnapshotMapper,
            WorkspaceProviderId workspaceProviderId) {
        this.runnerExecutionService = Objects.requireNonNull(
                runnerExecutionService, "Runner execution service must not be null");
        this.executionOrchestrator = Objects.requireNonNull(
                executionOrchestrator, "Execution orchestrator must not be null");
        this.platformExecutionOrchestrator = null;
        this.sourceSnapshotMapper = Objects.requireNonNull(
                sourceSnapshotMapper, "Admitted source snapshot mapper must not be null");
        this.workspaceProviderId = Objects.requireNonNull(
                workspaceProviderId, "Workspace provider ID must not be null");
    }

    RunnerPipelineCoordinatorImpl(
            RunnerExecutionService runnerExecutionService,
            ExecutionOrchestratorImpl executionOrchestrator,
            AdmittedSourceSnapshotMapper sourceSnapshotMapper,
            WorkspaceProviderId workspaceProviderId) {
        this.runnerExecutionService = Objects.requireNonNull(
                runnerExecutionService, "Runner execution service must not be null");
        this.executionOrchestrator = Objects.requireNonNull(
                executionOrchestrator, "Execution orchestrator must not be null");
        this.platformExecutionOrchestrator = executionOrchestrator;
        this.sourceSnapshotMapper = Objects.requireNonNull(
                sourceSnapshotMapper, "Admitted source snapshot mapper must not be null");
        this.workspaceProviderId = Objects.requireNonNull(
                workspaceProviderId, "Workspace provider ID must not be null");
    }

    @Override
    public RunnerPipelineResult execute(RunnerExecutionRequest request) {
        Objects.requireNonNull(request, "Runner execution request must not be null");
        ExecutionStartResult start = null;
        try {
            start = runnerExecutionService.start(request);
            var source = sourceSnapshotMapper.map(start.sourceSnapshot());
            WorkspaceDescriptor planned = WorkspaceDescriptor.planned(
                    new WorkspaceId(start.executionId()),
                    start.executionId(),
                    workspaceProviderId);
            ExecutionOrchestrationRequest orchestrationRequest = new ExecutionOrchestrationRequest(
                    start.context(), new SourcePreparationRequest(planned, source));
            PlatformExecutionOrchestrationResult platformResult =
                    platformExecutionOrchestrator == null
                            ? PlatformExecutionOrchestrationResult.ordinary(
                                    executionOrchestrator.execute(orchestrationRequest))
                            : platformExecutionOrchestrator.executePlatform(orchestrationRequest);
            ExecutionOrchestrationResult result = platformResult.result();
            ExecutionStatus terminalStatus = terminalStatus(platformResult);
            return result(
                    runnerExecutionService.complete(
                            completionRequest(request, start, platformResult), terminalStatus),
                    start);
        } catch (ExecutionOwnershipException ownershipFailure) {
            throw ownershipFailure;
        } catch (RunnerPipelineException pipelineFailure) {
            if ("CANCELLATION_REQUIRES_LIFECYCLE".equals(pipelineFailure.code())) {
                throw pipelineFailure;
            }
            return result(runnerExecutionService.complete(
                    completionRequest(request, start), ExecutionStatus.ERROR), start);
        } catch (ExecutionOrchestrationException orchestrationFailure) {
            return result(runnerExecutionService.complete(
                    completionRequest(
                            request, start, orchestrationFailure.observedCancellationVersion()),
                    ExecutionStatus.ERROR), start);
        } catch (RuntimeException infrastructureFailure) {
            return result(runnerExecutionService.complete(
                    completionRequest(request, start), ExecutionStatus.ERROR), start);
        }
    }

    private static RunnerPipelineResult result(
            ExecutionCompletionResult completion, ExecutionStartResult start) {
        if (start == null) {
            throw new RunnerPipelineException(
                    "EXECUTION_START_FAILED",
                    "Execution could not be started");
        }
        return new RunnerPipelineResult(completion, start.context(), start.startedAt());
    }

    private static ExecutionStatus terminalStatus(
            PlatformExecutionOrchestrationResult platformResult) {
        ExecutionOrchestrationResult result = platformResult.result();
        EngineExecutionState state = result.engineResult().state();
        return switch (state) {
            case SUCCEEDED -> ExecutionStatus.PASSED;
            case FAILED -> ExecutionStatus.FAILED;
            case CANCELLED -> {
                if (platformResult.observedCancellationVersion() == null) {
                    throw new RunnerPipelineException(
                            "CANCELLATION_REQUIRES_LIFECYCLE",
                            "Cancelled execution requires an observed persistent cancellation");
                }
                yield ExecutionStatus.CANCELLED;
            }
        };
    }

    private static RunnerExecutionRequest completionRequest(
            RunnerExecutionRequest request, ExecutionStartResult start) {
        return completionRequest(request, start, (Long) null);
    }

    private static RunnerExecutionRequest completionRequest(
            RunnerExecutionRequest request, ExecutionStartResult start,
            PlatformExecutionOrchestrationResult orchestrationResult) {
        return completionRequest(request, start,
                orchestrationResult == null
                        ? null
                        : orchestrationResult.observedCancellationVersion());
    }

    private static RunnerExecutionRequest completionRequest(
            RunnerExecutionRequest request, ExecutionStartResult start,
            Long observedCancellationVersion) {
        if (start == null) {
            return request;
        }
        return new RunnerExecutionRequest(
                request.executionId(),
                request.runnerId(),
                request.claimToken(),
                start.leaseGeneration(),
                start.leaseVersion(),
                observedCancellationVersion != null
                        ? observedCancellationVersion
                        : start.executionVersion());
    }
}
