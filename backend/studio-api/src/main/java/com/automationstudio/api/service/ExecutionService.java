package com.automationstudio.api.service;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.ProjectStatus;
import com.automationstudio.api.domain.TestSuiteStatus;
import com.automationstudio.api.dto.CreateExecutionRequest;
import com.automationstudio.api.dto.ExecutionResponse;
import com.automationstudio.api.dto.ExecutionSummaryResponse;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.TestSuite;
import com.automationstudio.api.exception.InvalidExecutionRequestException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.mapper.ExecutionMapper;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.repository.TestSuiteRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionService {

    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final TestSuiteRepository testSuiteRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionMapper executionMapper;
    private final Clock clock;

    public ExecutionService(
            ProjectRepository projectRepository,
            EnvironmentRepository environmentRepository,
            TestSuiteRepository testSuiteRepository,
            ExecutionRepository executionRepository,
            ExecutionMapper executionMapper,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.testSuiteRepository = testSuiteRepository;
        this.executionRepository = executionRepository;
        this.executionMapper = executionMapper;
        this.clock = clock;
    }

    @Transactional
    public ExecutionResponse create(CreateExecutionRequest request) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));
        Environment environment = environmentRepository.findById(request.environmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Environment", request.environmentId()));
        TestSuite testSuite = testSuiteRepository.findById(request.testSuiteId())
                .orElseThrow(() -> new ResourceNotFoundException("Test suite", request.testSuiteId()));

        verifyConfiguration(project, environment, testSuite);

        Execution execution = new Execution();
        execution.setProject(project);
        execution.setEnvironment(environment);
        execution.setTestSuite(testSuite);
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setRequestedBy(request.requestedBy());
        execution.setRequestedAt(OffsetDateTime.now(clock));

        return executionMapper.toResponse(executionRepository.save(execution));
    }

    @Transactional(readOnly = true)
    public ExecutionResponse get(UUID executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution", executionId));
        return executionMapper.toResponse(execution);
    }

    @Transactional(readOnly = true)
    public Page<ExecutionSummaryResponse> list(Pageable pageable) {
        return executionRepository.findAll(pageable).map(executionMapper::toSummaryResponse);
    }

    private void verifyConfiguration(Project project, Environment environment, TestSuite testSuite) {
        if (!project.getId().equals(environment.getProject().getId())) {
            throw new InvalidExecutionRequestException("Environment does not belong to the supplied project");
        }
        if (!project.getId().equals(testSuite.getProject().getId())) {
            throw new InvalidExecutionRequestException("Test suite does not belong to the supplied project");
        }
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new InvalidExecutionRequestException("Project must be active");
        }
        if (environment.getStatus() != EnvironmentStatus.ACTIVE) {
            throw new InvalidExecutionRequestException("Environment must be active");
        }
        if (testSuite.getStatus() != TestSuiteStatus.ACTIVE) {
            throw new InvalidExecutionRequestException("Test suite must be active");
        }
    }
}
