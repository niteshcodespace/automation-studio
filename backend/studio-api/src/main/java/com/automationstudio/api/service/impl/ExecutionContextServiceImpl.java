package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionContextBuilder;
import com.automationstudio.api.execution.ExecutionContextSource;
import com.automationstudio.api.execution.InvalidExecutionContextException;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.service.ExecutionContextService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionContextServiceImpl implements ExecutionContextService {

    private final ExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final RunnerRepository runnerRepository;
    private final ExecutionContextBuilder contextBuilder;

    public ExecutionContextServiceImpl(
            ExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            RunnerRepository runnerRepository,
            ExecutionContextBuilder contextBuilder) {
        this.executionRepository = executionRepository;
        this.leaseRepository = leaseRepository;
        this.runnerRepository = runnerRepository;
        this.contextBuilder = contextBuilder;
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionContext createContext(UUID executionId) {
        if (executionId == null) {
            throw new InvalidExecutionContextException("Execution ID must not be null");
        }
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Execution not found: " + executionId));
        ExecutionLease lease = leaseRepository.findById(executionId)
                .orElseThrow(() -> new InvalidExecutionContextException(
                        "Execution lease is missing"));
        Runner runner = runnerRepository.findByRunnerKey(lease.getRunnerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Runner not found for execution context"));

        if (execution.getStatus() != ExecutionStatus.CLAIMED) {
            throw new InvalidExecutionContextException(
                    "Execution must be CLAIMED when its context is created");
        }
        if (execution.getProject() == null || execution.getProject().getId() == null) {
            throw new InvalidExecutionContextException("Execution project is missing");
        }
        if (execution.getProject().getWorkspace() == null
                || execution.getProject().getWorkspace().getId() == null) {
            throw new InvalidExecutionContextException("Execution workspace is missing");
        }
        if (execution.getEnvironment() == null || execution.getEnvironment().getId() == null) {
            throw new InvalidExecutionContextException("Execution environment is missing");
        }
        if (execution.getAutomationSuite() == null
                || execution.getAutomationSuite().getId() == null) {
            throw new InvalidExecutionContextException("Execution suite is missing");
        }
        if (!runner.getRunnerKey().equals(lease.getRunnerId())) {
            throw new InvalidExecutionContextException(
                    "Execution lease runner does not match registered runner");
        }

        return contextBuilder.build(new ExecutionContextSource(
                execution.getId(),
                execution.getProject().getId(),
                execution.getProject().getWorkspace().getId(),
                execution.getEnvironment().getId(),
                execution.getAutomationSuite().getId(),
                execution.getCreatedAt(),
                execution.getEnvironmentSnapshot(),
                execution.getSuiteSnapshot(),
                execution.getRequestSnapshot(),
                runner.getId(),
                runner.getRunnerKey(),
                runner.getAgentVersion(),
                runner.getOperatingSystem(),
                runner.getArchitecture(),
                runner.getCapabilities(),
                runner.getLabels(),
                lease.getClaimedAt(),
                Map.of(),
                Map.of()));
    }
}
