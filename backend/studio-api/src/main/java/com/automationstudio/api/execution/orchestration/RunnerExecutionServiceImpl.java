package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.ExecutionEngineSupport;
import com.automationstudio.api.repository.ExecutionHeartbeatRepository;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ExecutionContextService;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerExecutionServiceImpl implements RunnerExecutionService {

    private final ExecutionLeaseRepository leaseRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionHeartbeatRepository databaseTimeRepository;
    private final ExecutionContextService contextService;
    private final ExecutionEngineRegistry engineRegistry;
    private final ExecutionOwnershipValidator ownershipValidator;
    private final ExecutionStateValidator stateValidator;

    public RunnerExecutionServiceImpl(
            ExecutionLeaseRepository leaseRepository,
            ExecutionRepository executionRepository,
            ExecutionHeartbeatRepository databaseTimeRepository,
            ExecutionContextService contextService,
            ExecutionEngineRegistry engineRegistry,
            ExecutionOwnershipValidator ownershipValidator,
            ExecutionStateValidator stateValidator) {
        this.leaseRepository = leaseRepository;
        this.executionRepository = executionRepository;
        this.databaseTimeRepository = databaseTimeRepository;
        this.contextService = contextService;
        this.engineRegistry = engineRegistry;
        this.ownershipValidator = ownershipValidator;
        this.stateValidator = stateValidator;
    }

    @Override
    @Transactional
    public ExecutionStartResult start(RunnerExecutionRequest request) {
        LockedOwnership ownership = lockAndValidate(request);
        stateValidator.validateStart(ownership.execution());

        ExecutionContext context = contextService.createContext(request.executionId());
        ExecutionEngineSupport engine = engineRegistry.validateCompatibility(context);

        ownership.execution().start(ownership.databaseTime());
        Execution started = executionRepository.saveAndFlush(ownership.execution());
        return new ExecutionStartResult(
                started.getId(),
                started.getStatus(),
                started.getVersion(),
                ownership.lease().getLeaseGeneration(),
                ownership.lease().getVersion(),
                started.getStartedAt(),
                context,
                engine.descriptor());
    }

    @Override
    @Transactional
    public ExecutionCompletionResult prepareCompletion(RunnerExecutionRequest request) {
        LockedOwnership ownership = lockAndValidate(request);
        stateValidator.validateCompletionPreparation(ownership.execution());
        return new ExecutionCompletionResult(
                ownership.execution().getId(),
                ownership.execution().getStatus(),
                ownership.execution().getVersion(),
                ownership.lease().getLeaseGeneration(),
                ownership.lease().getVersion(),
                ownership.databaseTime());
    }

    @Override
    @Transactional
    public ExecutionCompletionResult complete(
            RunnerExecutionRequest request, ExecutionStatus terminalStatus) {
        LockedOwnership ownership = lockAndValidate(request);
        stateValidator.validateCompletion(ownership.execution(), terminalStatus);
        if (terminalStatus == ExecutionStatus.PASSED) {
            ownership.execution().markPassed(ownership.databaseTime());
        } else {
            ownership.execution().markFailed(ownership.databaseTime());
        }
        Execution completed = executionRepository.saveAndFlush(ownership.execution());
        return new ExecutionCompletionResult(
                completed.getId(),
                completed.getStatus(),
                completed.getVersion(),
                ownership.lease().getLeaseGeneration(),
                ownership.lease().getVersion(),
                ownership.databaseTime());
    }

    private LockedOwnership lockAndValidate(RunnerExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Runner execution request must not be null");
        }
        ExecutionLease lease = leaseRepository
                .findByExecutionIdForUpdate(request.executionId())
                .orElseThrow(() -> new ExecutionOwnershipException(
                        "Execution lease was not found"));
        Execution execution = executionRepository
                .findByIdForUpdate(request.executionId())
                .orElseThrow(() -> new RunnerExecutionException(
                        "Execution was not found"));
        OffsetDateTime databaseTime = databaseTimeRepository.currentDatabaseTime();
        ownershipValidator.validate(request, lease, execution, databaseTime);
        return new LockedOwnership(lease, execution, databaseTime);
    }

    private record LockedOwnership(
            ExecutionLease lease,
            Execution execution,
            OffsetDateTime databaseTime) {
    }
}
