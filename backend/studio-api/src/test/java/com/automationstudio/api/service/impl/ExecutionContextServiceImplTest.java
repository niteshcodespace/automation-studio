package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.Workspace;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionContextBuilder;
import com.automationstudio.api.execution.InvalidExecutionContextException;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionContextServiceImplTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();

    @Mock private ExecutionRepository executionRepository;
    @Mock private ExecutionLeaseRepository leaseRepository;
    @Mock private RunnerRepository runnerRepository;
    @Mock private Execution execution;
    @Mock private ExecutionLease lease;
    @Mock private Runner runner;
    @Mock private Project project;
    @Mock private Workspace workspace;
    @Mock private Environment environment;
    @Mock private AutomationSuite suite;

    private ExecutionContextServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExecutionContextServiceImpl(
                executionRepository,
                leaseRepository,
                runnerRepository,
                new ExecutionContextBuilder(new SensitiveKeyDetector()));
    }

    @Test
    void loadsEntitiesAndReturnsOnlyImmutableSnapshotValues() {
        UUID projectId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID runnerId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-27T10:00:00Z");
        OffsetDateTime claimedAt = createdAt.plusMinutes(1);

        when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(leaseRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(lease));
        when(lease.getRunnerId()).thenReturn("runner-1");
        when(runnerRepository.findByRunnerKey("runner-1")).thenReturn(Optional.of(runner));
        when(execution.getId()).thenReturn(EXECUTION_ID);
        when(execution.getStatus()).thenReturn(ExecutionStatus.CLAIMED);
        when(execution.getProject()).thenReturn(project);
        when(project.getId()).thenReturn(projectId);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.getId()).thenReturn(workspaceId);
        when(execution.getEnvironment()).thenReturn(environment);
        when(environment.getId()).thenReturn(environmentId);
        when(execution.getAutomationSuite()).thenReturn(suite);
        when(suite.getId()).thenReturn(suiteId);
        when(execution.getCreatedAt()).thenReturn(createdAt);
        when(execution.getEnvironmentSnapshot()).thenReturn(environmentSnapshot(environmentId));
        when(execution.getSuiteSnapshot()).thenReturn(suiteSnapshot(suiteId));
        when(execution.getRequestSnapshot()).thenReturn(Map.of());
        when(runner.getId()).thenReturn(runnerId);
        when(runner.getRunnerKey()).thenReturn("runner-1");
        when(runner.getAgentVersion()).thenReturn("1.0");
        when(runner.getOperatingSystem()).thenReturn("linux");
        when(runner.getArchitecture()).thenReturn("amd64");
        when(runner.getCapabilities()).thenReturn(
                Map.of("engines", Map.of("playwright-java", "1.52")));
        when(runner.getLabels()).thenReturn(Map.of("region", "eu"));
        when(lease.getClaimedAt()).thenReturn(claimedAt);

        ExecutionContext context = service.createContext(EXECUTION_ID);

        assertThat(context.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(context.runner().runnerId()).isEqualTo(runnerId);
        assertThat(context.metadata().claimedAt()).isEqualTo(claimedAt);
        assertThat(context.getClass().getRecordComponents())
                .noneMatch(component -> component.getType().getPackageName()
                        .equals("com.automationstudio.api.entity"));
    }

    @Test
    void distinguishesMissingExecutionAndMissingEnvironment() {
        when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createContext(EXECUTION_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Execution not found");

        when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(leaseRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(lease));
        when(lease.getRunnerId()).thenReturn("runner-1");
        when(runnerRepository.findByRunnerKey("runner-1")).thenReturn(Optional.of(runner));
        when(execution.getStatus()).thenReturn(ExecutionStatus.CLAIMED);
        when(execution.getProject()).thenReturn(project);
        when(project.getId()).thenReturn(UUID.randomUUID());
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.getId()).thenReturn(UUID.randomUUID());
        when(execution.getEnvironment()).thenReturn(null);
        assertThatThrownBy(() -> service.createContext(EXECUTION_ID))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessage("Execution environment is missing");
    }

    private Map<String, Object> environmentSnapshot(UUID environmentId) {
        return Map.of(
                "id", environmentId.toString(),
                "name", "QA",
                "type", "TEST",
                "baseUrl", "https://example.test",
                "configuration", Map.of(),
                "secretReferences", Map.of());
    }

    private Map<String, Object> suiteSnapshot(UUID suiteId) {
        return Map.of(
                "id", suiteId.toString(),
                "name", "Smoke",
                "engineType", "PLAYWRIGHT",
                "engineId", "playwright-java",
                "suiteReference", "tests/smoke",
                "configuration", Map.of());
    }
}
