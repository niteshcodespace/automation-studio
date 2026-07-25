package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ExecutionTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.ExecutionSnapshotFactory;
import com.automationstudio.api.service.command.CreateExecutionCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private AutomationSuiteRepository suiteRepository;
    @Mock
    private AutomationTestCaseRepository testCaseRepository;
    @Mock
    private ExecutionRepository executionRepository;
    @Mock
    private ExecutionTestCaseRepository executionTestCaseRepository;
    @Mock
    private ExecutionSnapshotFactory snapshotFactory;

    private ExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExecutionServiceImpl(
                projectRepository, environmentRepository, suiteRepository, testCaseRepository,
                executionRepository, executionTestCaseRepository, snapshotFactory);
    }

    @Test
    void rejectsMissingProjectWithoutDisclosingScopedResources() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                projectId, "operator", command(ExecutionSelectionMode.SUITE, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(environmentRepository, suiteRepository, testCaseRepository);
    }

    @Test
    void rejectsEmptyAndDuplicateSelectedCases() {
        UUID projectId = UUID.randomUUID();
        UUID duplicate = UUID.randomUUID();
        stubCatalog(projectId);

        assertThatThrownBy(() -> service.create(
                projectId, "operator", command(ExecutionSelectionMode.TEST_CASES, List.of())))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(
                projectId, "operator",
                command(ExecutionSelectionMode.TEST_CASES, List.of(duplicate, duplicate))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsExplicitCasesForSuiteSelectionAndInvalidRequester() {
        UUID projectId = UUID.randomUUID();
        stubCatalog(projectId);

        assertThatThrownBy(() -> service.create(
                projectId, "operator",
                command(ExecutionSelectionMode.SUITE, List.of(UUID.randomUUID()))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsInvalidRequester() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project()));

        assertThatThrownBy(() -> service.create(
                projectId, " ", command(ExecutionSelectionMode.SUITE, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    private void stubCatalog(UUID projectId) {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project()));
        when(environmentRepository.findByProjectIdAndId(
                eq(projectId), any())).thenReturn(
                Optional.of(new Environment()));
        when(suiteRepository.findByProjectIdAndId(
                eq(projectId), any())).thenReturn(
                Optional.of(new AutomationSuite()));
    }

    private CreateExecutionCommand command(
            ExecutionSelectionMode mode, List<UUID> testCaseIds) {
        return new CreateExecutionCommand(
                UUID.randomUUID(), UUID.randomUUID(), mode, testCaseIds);
    }
}
