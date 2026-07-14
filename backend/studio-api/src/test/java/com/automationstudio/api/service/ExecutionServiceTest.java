package com.automationstudio.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.ProjectStatus;
import com.automationstudio.api.domain.TestSuiteStatus;
import com.automationstudio.api.dto.CreateExecutionRequest;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T10:15:30Z");

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private TestSuiteRepository testSuiteRepository;
    @Mock
    private ExecutionRepository executionRepository;

    private ExecutionService service;
    private Project project;
    private Environment environment;
    private TestSuite testSuite;
    private CreateExecutionRequest request;

    @BeforeEach
    void setUp() {
        project = project(UUID.randomUUID(), ProjectStatus.ACTIVE);
        environment = environment(UUID.randomUUID(), project, EnvironmentStatus.ACTIVE);
        testSuite = testSuite(UUID.randomUUID(), project, TestSuiteStatus.ACTIVE);
        request = new CreateExecutionRequest(project.getId(), environment.getId(), testSuite.getId(), "qa.user");
        service = new ExecutionService(
                projectRepository,
                environmentRepository,
                testSuiteRepository,
                executionRepository,
                new ExecutionMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsPendingExecution() {
        stubConfiguration();
        when(executionRepository.save(any(Execution.class))).thenAnswer(invocation -> {
            Execution execution = invocation.getArgument(0);
            execution.setId(UUID.randomUUID());
            return execution;
        });

        var response = service.create(request);

        ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);
        verify(executionRepository).save(captor.capture());
        Execution saved = captor.getValue();
        assertEquals(ExecutionStatus.PENDING, response.status());
        assertEquals("qa.user", response.requestedBy());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), saved.getRequestedAt());
        assertEquals(project, saved.getProject());
        assertEquals(environment, saved.getEnvironment());
        assertEquals(testSuite, saved.getTestSuite());
    }

    @Test
    void rejectsMissingProject() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.create(request));
    }

    @Test
    void rejectsEnvironmentBelongingToAnotherProject() {
        environment.setProject(project(UUID.randomUUID(), ProjectStatus.ACTIVE));
        stubConfiguration();

        InvalidExecutionRequestException exception =
                assertThrows(InvalidExecutionRequestException.class, () -> service.create(request));
        assertTrue(exception.getMessage().contains("Environment"));
    }

    @Test
    void rejectsTestSuiteBelongingToAnotherProject() {
        testSuite.setProject(project(UUID.randomUUID(), ProjectStatus.ACTIVE));
        stubConfiguration();

        InvalidExecutionRequestException exception =
                assertThrows(InvalidExecutionRequestException.class, () -> service.create(request));
        assertTrue(exception.getMessage().contains("Test suite"));
    }

    @Test
    void rejectsInactiveProject() {
        project.setStatus(ProjectStatus.INACTIVE);
        stubConfiguration();

        assertThrows(InvalidExecutionRequestException.class, () -> service.create(request));
    }

    @Test
    void rejectsInactiveEnvironment() {
        environment.setStatus(EnvironmentStatus.INACTIVE);
        stubConfiguration();

        assertThrows(InvalidExecutionRequestException.class, () -> service.create(request));
    }

    @Test
    void rejectsInactiveTestSuite() {
        testSuite.setStatus(TestSuiteStatus.INACTIVE);
        stubConfiguration();

        assertThrows(InvalidExecutionRequestException.class, () -> service.create(request));
    }

    @Test
    void retrievesExistingExecution() {
        Execution execution = execution(UUID.randomUUID());
        when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        var response = service.get(execution.getId());

        assertEquals(execution.getId(), response.id());
        assertEquals(project.getId(), response.projectId());
    }

    @Test
    void rejectsMissingExecution() {
        UUID executionId = UUID.randomUUID();
        when(executionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(executionId));
    }

    private void stubConfiguration() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(environmentRepository.findById(environment.getId())).thenReturn(Optional.of(environment));
        when(testSuiteRepository.findById(testSuite.getId())).thenReturn(Optional.of(testSuite));
    }

    private Execution execution(UUID id) {
        Execution execution = new Execution();
        execution.setId(id);
        execution.setProject(project);
        execution.setEnvironment(environment);
        execution.setTestSuite(testSuite);
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setRequestedBy("qa.user");
        execution.setRequestedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return execution;
    }

    private Project project(UUID id, ProjectStatus status) {
        Project value = new Project();
        value.setId(id);
        value.setStatus(status);
        return value;
    }

    private Environment environment(UUID id, Project owner, EnvironmentStatus status) {
        Environment value = new Environment();
        value.setId(id);
        value.setProject(owner);
        value.setStatus(status);
        return value;
    }

    private TestSuite testSuite(UUID id, Project owner, TestSuiteStatus status) {
        TestSuite value = new TestSuite();
        value.setId(id);
        value.setProject(owner);
        value.setStatus(status);
        return value;
    }
}
