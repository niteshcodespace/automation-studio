package com.automationstudio.api.execution.lifecycle;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceCollector;
import com.automationstudio.api.execution.evidence.ExecutionEvidenceException;
import com.automationstudio.api.execution.orchestration.ExecutionStartResult;
import com.automationstudio.api.execution.orchestration.RunnerExecutionRequest;
import com.automationstudio.api.execution.orchestration.RunnerExecutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExecutionLifecycleServiceImpl implements ExecutionLifecycleService {

    private final RunnerExecutionService runnerExecutionService;
    private final ExecutionEngineRegistry engineRegistry;
    private final ExecutionEngineInvoker engineInvoker;
    private final ExecutionLifecycleValidator lifecycleValidator;
    private final ExecutionEvidenceCollector evidenceCollector;
    private final Clock clock;

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
    }

    @Override
    public ExecutionResult execute(RunnerExecutionRequest request) {
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
