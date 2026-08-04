package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.ExecutionSelection;
import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.InvalidExecutionTransitionException;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionTestCase;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ExecutionTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.ExecutionService;
import com.automationstudio.api.service.ExecutionSnapshotFactory;
import com.automationstudio.api.service.command.CreateExecutionCommand;
import com.automationstudio.api.service.command.CancelExecutionCommand;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.OptimisticLockingFailureException;

@Service
@Transactional
public class ExecutionServiceImpl implements ExecutionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final AutomationSuiteRepository suiteRepository;
    private final AutomationTestCaseRepository testCaseRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionTestCaseRepository executionTestCaseRepository;
    private final ExecutionSnapshotFactory snapshotFactory;
    private final Clock clock;

    public ExecutionServiceImpl(
            ProjectRepository projectRepository,
            EnvironmentRepository environmentRepository,
            AutomationSuiteRepository suiteRepository,
            AutomationTestCaseRepository testCaseRepository,
            ExecutionRepository executionRepository,
            ExecutionTestCaseRepository executionTestCaseRepository,
            ExecutionSnapshotFactory snapshotFactory,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.suiteRepository = suiteRepository;
        this.testCaseRepository = testCaseRepository;
        this.executionRepository = executionRepository;
        this.executionTestCaseRepository = executionTestCaseRepository;
        this.snapshotFactory = snapshotFactory;
        this.clock = clock;
    }

    @Override
    public Execution create(
            UUID projectId, String requester, CreateExecutionCommand command) {
        validateRequester(requester);
        if (command == null) {
            throw new InvalidRequestException("Execution command must not be null");
        }
        Project project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found with id: " + projectId));

        Environment environment = environmentRepository.findByProjectIdAndId(
                        projectId, command.environmentId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Environment not found with id: " + command.environmentId()
                                + " in project: " + projectId));
        AutomationSuite suite = suiteRepository.findByProjectIdAndIdForUpdate(
                        projectId, command.automationSuiteId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Automation suite not found with id: " + command.automationSuiteId()
                                + " in project: " + projectId));
        requireActive(environment);
        requireActive(suite);

        ExecutionSelection selection = validateSelection(
                command.selectionMode(), command.testCaseIds());
        List<AutomationTestCase> selectedCases =
                loadAndValidateSelectedCases(projectId, suite, selection);
        OffsetDateTime requestedAt = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        Execution execution = new Execution();
        execution.setProject(project);
        execution.setEnvironment(environment);
        execution.setAutomationSuite(suite);
        execution.setSelectionMode(selection.getMode());
        execution.setRequestedBy(requester);
        execution.setRequestedAt(requestedAt);
        execution.setEnvironmentSnapshot(snapshotFactory.environment(environment));
        execution.setSuiteSnapshot(snapshotFactory.suite(suite));
        execution.setRequestSnapshot(snapshotFactory.request(selection, requester, requestedAt));
        execution.setSourceSnapshot(snapshotFactory.source(project, suite));
        execution = executionRepository.saveAndFlush(execution);

        if (selection.getMode() == ExecutionSelectionMode.TEST_CASES) {
            List<ExecutionTestCase> rows = new ArrayList<>(selectedCases.size());
            for (int sequence = 0; sequence < selectedCases.size(); sequence++) {
                AutomationTestCase testCase = selectedCases.get(sequence);
                ExecutionTestCase selected = new ExecutionTestCase();
                selected.setExecution(execution);
                selected.setAutomationTestCase(testCase);
                selected.setSequenceNumber(sequence);
                selected.setTestCaseSnapshot(snapshotFactory.testCase(testCase));
                rows.add(selected);
            }
            executionTestCaseRepository.saveAll(rows);
        }
        return execution;
    }

    @Override
    @Transactional(readOnly = true)
    public Execution get(UUID projectId, UUID executionId) {
        findProject(projectId);
        return executionRepository.findByProjectIdAndId(projectId, executionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Execution not found with id: " + executionId
                                + " in project: " + projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Execution> list(
            UUID projectId, ExecutionStatus status, Pageable pageable) {
        findProject(projectId);
        validatePageable(pageable);
        if (status == null) {
            return executionRepository.findByProjectIdOrderByRequestedAtDescIdDesc(
                    projectId, pageable);
        }
        return executionRepository.findByProjectIdAndStatusOrderByRequestedAtDescIdDesc(
                projectId, status, pageable);
    }

    @Override
    public Execution cancel(
            UUID projectId,
            UUID executionId,
            long expectedVersion,
            String actor,
            CancelExecutionCommand command) {
        findProject(projectId);
        Execution execution = executionRepository.findByProjectIdAndId(projectId, executionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Execution not found with id: " + executionId
                                + " in project: " + projectId));
        if (execution.getVersion() != expectedVersion) {
            throw new ResourceConflictException(
                    "Execution version conflict for id: " + executionId);
        }
        validateRequester(actor);
        String reason = normalizeCancellationReason(command);
        try {
            execution.requestCancellation(OffsetDateTime.now(clock), actor, reason);
            return executionRepository.saveAndFlush(execution);
        } catch (InvalidExecutionTransitionException exception) {
            throw new ResourceConflictException(
                    "Execution cannot be cancelled from status: "
                            + exception.getCurrentStatus());
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new ResourceConflictException(
                    "Execution version conflict for id: " + executionId);
        }
    }

    private Project findProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found with id: " + projectId));
    }

    private ExecutionSelection validateSelection(
            ExecutionSelectionMode mode, List<UUID> testCaseIds) {
        try {
            if (mode == ExecutionSelectionMode.SUITE) {
                return testCaseIds == null
                        ? ExecutionSelection.suite()
                        : selection(mode, testCaseIds);
            }
            return selection(mode, testCaseIds);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(exception.getMessage());
        }
    }

    private ExecutionSelection selection(
            ExecutionSelectionMode mode, List<UUID> testCaseIds) {
        if (mode == ExecutionSelectionMode.SUITE) {
            if (testCaseIds == null || testCaseIds.isEmpty()) {
                return ExecutionSelection.suite();
            }
            throw new IllegalArgumentException(
                    "SUITE selection must not contain explicit test cases");
        }
        if (mode == ExecutionSelectionMode.TEST_CASES) {
            return ExecutionSelection.testCases(testCaseIds);
        }
        throw new IllegalArgumentException("Selection mode must not be null");
    }

    private List<AutomationTestCase> loadAndValidateSelectedCases(
            UUID projectId, AutomationSuite suite, ExecutionSelection selection) {
        if (selection.getMode() == ExecutionSelectionMode.SUITE) {
            return List.of();
        }
        List<AutomationTestCase> found = testCaseRepository.findByAutomationSuiteIdAndIdIn(
                suite.getId(), selection.getTestCaseIds());
        Map<UUID, AutomationTestCase> byId = new HashMap<>();
        found.forEach(testCase -> byId.put(testCase.getId(), testCase));

        List<AutomationTestCase> ordered = new ArrayList<>();
        for (UUID testCaseId : selection.getTestCaseIds()) {
            AutomationTestCase testCase = byId.get(testCaseId);
            if (testCase == null) {
                throw new ResourceNotFoundException(
                        "Automation test case not found with id: " + testCaseId
                                + " in suite: " + suite.getId()
                                + " and project: " + projectId);
            }
            if (testCase.getStatus() != AutomationTestCaseStatus.ACTIVE) {
                throw new ResourceConflictException(
                        "Automation test case is not active: " + testCaseId);
            }
            ordered.add(testCase);
        }
        return ordered;
    }

    private void requireActive(Environment environment) {
        if (environment.getStatus() != EnvironmentStatus.ACTIVE) {
            throw new ResourceConflictException(
                    "Environment is not active: " + environment.getId());
        }
    }

    private void requireActive(AutomationSuite suite) {
        if (suite.getStatus() != AutomationSuiteStatus.ACTIVE) {
            throw new ResourceConflictException(
                    "Automation suite is not active: " + suite.getId());
        }
    }

    private void validateRequester(String requester) {
        if (requester == null || requester.isBlank()) {
            throw new InvalidRequestException("Requester must not be blank");
        }
        if (requester.length() > 150) {
            throw new InvalidRequestException("Requester must not exceed 150 characters");
        }
    }

    private String normalizeCancellationReason(CancelExecutionCommand command) {
        if (command == null || command.reason() == null) {
            return null;
        }
        String reason = command.reason().trim();
        if (reason.isEmpty()) {
            return null;
        }
        if (reason.length() > 1000) {
            throw new InvalidRequestException(
                    "Cancellation reason must not exceed 1000 characters");
        }
        return reason;
    }

    private void validatePageable(Pageable pageable) {
        if (pageable == null) {
            throw new InvalidRequestException("Pageable must not be null");
        }
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Page size must not exceed " + MAX_PAGE_SIZE);
        }
        if (pageable.getSort().isSorted()) {
            throw new InvalidRequestException(
                    "Execution listing order is fixed to requestedAt DESC, id DESC");
        }
    }
}
