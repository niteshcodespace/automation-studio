package com.automationstudio.api.execution.lifecycle;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceCollector;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceException;
import com.automationstudio.api.execution.orchestration.ExecutionStartResult;
import com.automationstudio.api.execution.orchestration.RunnerPipelineCoordinator;
import com.automationstudio.api.execution.orchestration.RunnerPipelineResult;
import com.automationstudio.api.execution.orchestration.RunnerExecutionRequest;
import com.automationstudio.api.execution.orchestration.RunnerExecutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ExecutionLifecycleServiceImpl implements ExecutionLifecycleService {

    private final RunnerExecutionService runnerExecutionService;
    private final ExecutionEngineRegistry engineRegistry;
    private final ExecutionEngineInvoker engineInvoker;
    private final ExecutionLifecycleValidator lifecycleValidator;
    private final ExecutionEvidenceCollector evidenceCollector;
    private final Clock clock;
    private final RunnerPipelineCoordinator pipelineCoordinator;

    @Autowired
    public ExecutionLifecycleServiceImpl(
            RunnerExecutionService runnerExecutionService,
            ExecutionEngineRegistry engineRegistry,
            ExecutionEngineInvoker engineInvoker,
            ExecutionLifecycleValidator lifecycleValidator,
            ExecutionEvidenceCollector evidenceCollector,
            Clock clock,
            ObjectProvider<RunnerPipelineCoordinator> pipelineCoordinator) {
        this.runnerExecutionService = runnerExecutionService;
        this.engineRegistry = engineRegistry;
        this.engineInvoker = engineInvoker;
        this.lifecycleValidator = lifecycleValidator;
        this.evidenceCollector = evidenceCollector;
        this.clock = clock;
        this.pipelineCoordinator = pipelineCoordinator.getIfAvailable();
    }

    public ExecutionLifecycleServiceImpl(
            RunnerExecutionService runnerExecutionService,
            ExecutionEngineRegistry engineRegistry,
            ExecutionEngineInvoker engineInvoker,
            ExecutionLifecycleValidator lifecycleValidator,
            ExecutionEvidenceCollector evidenceCollector,
            Clock clock) {
        this.runnerExecutionService = runnerExecutionService;
        this.engineRegistry = engineRegistry;
        this.engineInvoker = engineInvoker;
        this.lifecycleValidator = lifecycleValidator;
        this.evidenceCollector = evidenceCollector;
        this.clock = clock;
        this.pipelineCoordinator = null;
    }

    @Override
    public ExecutionResult execute(RunnerExecutionRequest request) {
        if (pipelineCoordinator != null) {
            return controlledResult(pipelineCoordinator.execute(request));
        }
        ExecutionStartResult start = runnerExecutionService.start(request);
        ExecutionContext context = start.context();
        ExecutionEngine engine = engineRegistry
                .resolve(
                        start.engineDescriptor().engineName(),
                        start.engineDescriptor().engineVersion())
                .engine();

        ExecutionResult result;
        try {
            result = engineInvoker.invoke(engine, context);
            lifecycleValidator.validateEngineResult(context, result);
            result = result.withEvidence(evidenceCollector.collect(context, result));
        } catch (RuntimeException exception) {
            result = failedResult(context, start.startedAt(), exception);
        }

        RunnerExecutionRequest completionRequest = new RunnerExecutionRequest(
                request.executionId(),
                request.runnerId(),
                request.claimToken(),
                start.leaseGeneration(),
                start.leaseVersion(),
                start.executionVersion());
        com.automationstudio.api.domain.ExecutionStatus terminalStatus =
                result.status() == ExecutionStatus.SUCCEEDED
                        ? com.automationstudio.api.domain.ExecutionStatus.PASSED
                        : com.automationstudio.api.domain.ExecutionStatus.FAILED;
        runnerExecutionService.complete(completionRequest, terminalStatus);
        return result;
    }

    private static ExecutionResult controlledResult(RunnerPipelineResult pipeline) {
        var completion = pipeline.completion();
        OffsetDateTime finishedAt = completion.preparedAt();
        Duration duration = Duration.between(pipeline.startedAt(), finishedAt);
        boolean passed = completion.status()
                == com.automationstudio.api.domain.ExecutionStatus.PASSED;
        return new ExecutionResult(
                completion.executionId(),
                pipeline.context().runner().runnerId(),
                passed ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                pipeline.startedAt(),
                finishedAt,
                duration,
                passed ? ExecutionTerminationReason.COMPLETED
                        : ExecutionTerminationReason.ENGINE_FAILURE,
                passed ? ExecutionFailureReason.NONE : ExecutionFailureReason.ENGINE_EXCEPTION,
                Map.of());
    }

    private ExecutionResult failedResult(
            ExecutionContext context,
            OffsetDateTime startedAt,
            RuntimeException exception) {
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        if (finishedAt.isBefore(startedAt)) {
            finishedAt = startedAt;
        }
        ExecutionFailureReason reason = exception instanceof ExecutionLifecycleException
                        || exception instanceof ExecutionEvidenceException
                ? ExecutionFailureReason.INVALID_ENGINE_RESULT
                : ExecutionFailureReason.ENGINE_EXCEPTION;
        return new ExecutionResult(
                context.executionId(),
                context.runner().runnerId(),
                ExecutionStatus.FAILED,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt),
                ExecutionTerminationReason.ENGINE_FAILURE,
                reason,
                Map.of());
    }
}
