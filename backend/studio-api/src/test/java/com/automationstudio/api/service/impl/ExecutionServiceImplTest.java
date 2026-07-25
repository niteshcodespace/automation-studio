package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.exception.ResourceConflictException;
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
import com.automationstudio.api.service.command.CancelExecutionCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
                executionRepository, executionTestCaseRepository, snapshotFactory,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
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

    @Test
    void cancelsPendingImmediatelyUsingFixedClockAndTrimmedReason() {
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Execution execution = execution(executionId);
        stubCancellation(projectId, executionId, execution);

        Execution result = service.cancel(
                projectId, executionId, 0, "operator",
                new CancelExecutionCommand("  stop now  "));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(result.getCancelRequestedAt())
                .isEqualTo(java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThat(result.getCancelledAt()).isEqualTo(result.getCancelRequestedAt());
        assertThat(result.getFinishedAt()).isEqualTo(result.getCancelRequestedAt());
        assertThat(result.getCancelledBy()).isEqualTo("operator");
        assertThat(result.getCancellationReason()).isEqualTo("stop now");
    }

    @Test
    void requestsCooperativeCancellationForClaimedAndRunning() {
        for (boolean running : List.of(false, true)) {
            UUID projectId = UUID.randomUUID();
            UUID executionId = UUID.randomUUID();
            Execution execution = execution(executionId);
            execution.claim();
            if (running) {
                execution.start(java.time.OffsetDateTime.parse("2025-12-31T23:59:00Z"));
            }
            stubCancellation(projectId, executionId, execution);

            service.cancel(projectId, executionId, 0, "operator",
                    new CancelExecutionCommand(" "));

            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCEL_REQUESTED);
            assertThat(execution.getCancellationReason()).isNull();
            assertThat(execution.getCancelledAt()).isNull();
            assertThat(execution.getFinishedAt()).isNull();
        }
    }

    @Test
    void idempotentCancellationPreservesMetadata() {
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Execution execution = execution(executionId);
        execution.requestCancellation(
                java.time.OffsetDateTime.parse("2025-12-31T20:00:00Z"), "first", "original");
        stubCancellation(projectId, executionId, execution);

        service.cancel(projectId, executionId, 0, "second",
                new CancelExecutionCommand("replacement"));

        assertThat(execution.getCancelledBy()).isEqualTo("first");
        assertThat(execution.getCancellationReason()).isEqualTo("original");
        assertThat(execution.getCancelRequestedAt())
                .isEqualTo(java.time.OffsetDateTime.parse("2025-12-31T20:00:00Z"));
    }

    @Test
    void rejectsStaleVersionAndTerminalStates() {
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Execution stale = execution(executionId);
        ReflectionTestUtils.setField(stale, "version", 2L);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project()));
        when(executionRepository.findByProjectIdAndId(projectId, executionId))
                .thenReturn(Optional.of(stale));
        assertThatThrownBy(() -> service.cancel(
                projectId, executionId, 1, "operator", new CancelExecutionCommand(null)))
                .isInstanceOf(ResourceConflictException.class);

        for (ExecutionStatus status : List.of(
                ExecutionStatus.PASSED, ExecutionStatus.FAILED, ExecutionStatus.ERROR)) {
            Execution terminal = terminalExecution(executionId, status);
            when(executionRepository.findByProjectIdAndId(projectId, executionId))
                    .thenReturn(Optional.of(terminal));
            assertThatThrownBy(() -> service.cancel(
                    projectId, executionId, 0, "operator", new CancelExecutionCommand(null)))
                    .isInstanceOf(ResourceConflictException.class);
        }
    }

    @Test
    void cancellationDoesNotDiscloseCrossProjectExecution() {
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project()));
        when(executionRepository.findByProjectIdAndId(projectId, executionId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(
                projectId, executionId, 0, "operator", new CancelExecutionCommand(null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void validatesCancellationActorAndReason() {
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Execution execution = execution(executionId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project()));
        when(executionRepository.findByProjectIdAndId(projectId, executionId))
                .thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.cancel(
                projectId, executionId, 0, " ", new CancelExecutionCommand(null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.cancel(
                projectId, executionId, 0, "operator",
                new CancelExecutionCommand("x".repeat(1001))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void translatesOptimisticLockFailureToConflict() {
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Execution execution = execution(executionId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project()));
        when(executionRepository.findByProjectIdAndId(projectId, executionId))
                .thenReturn(Optional.of(execution));
        when(executionRepository.saveAndFlush(execution))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        Execution.class, executionId));

        assertThatThrownBy(() -> service.cancel(
                projectId, executionId, 0, "operator", new CancelExecutionCommand(null)))
                .isInstanceOf(ResourceConflictException.class);
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

    private void stubCancellation(UUID projectId, UUID executionId, Execution execution) {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project()));
        when(executionRepository.findByProjectIdAndId(projectId, executionId))
                .thenReturn(Optional.of(execution));
        when(executionRepository.saveAndFlush(execution)).thenReturn(execution);
    }

    private Execution execution(UUID executionId) {
        Execution execution = new Execution();
        ReflectionTestUtils.setField(execution, "id", executionId);
        return execution;
    }

    private Execution terminalExecution(UUID executionId, ExecutionStatus status) {
        Execution execution = execution(executionId);
        execution.claim();
        execution.start(java.time.OffsetDateTime.parse("2025-12-31T22:00:00Z"));
        switch (status) {
            case PASSED -> execution.markPassed(
                    java.time.OffsetDateTime.parse("2025-12-31T23:00:00Z"));
            case FAILED -> execution.markFailed(
                    java.time.OffsetDateTime.parse("2025-12-31T23:00:00Z"));
            case ERROR -> execution.markError(
                    java.time.OffsetDateTime.parse("2025-12-31T23:00:00Z"));
            default -> throw new IllegalArgumentException("Unsupported terminal status");
        }
        return execution;
    }
}
