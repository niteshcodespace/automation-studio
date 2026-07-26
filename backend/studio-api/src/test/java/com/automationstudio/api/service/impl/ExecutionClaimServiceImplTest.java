package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.ExecutionClaimRepository;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.command.ClaimExecutionCommand;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionClaimServiceImplTest {

    @Mock
    private ExecutionClaimRepository claimRepository;

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private ExecutionLeaseRepository leaseRepository;

    @Mock
    private ClaimTokenGenerator tokenGenerator;

    @Mock
    private Project project;

    @Mock
    private Environment environment;

    @Mock
    private AutomationSuite suite;

    private ExecutionClaimServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExecutionClaimServiceImpl(
                claimRepository, executionRepository, leaseRepository, tokenGenerator);
    }

    @Test
    void returnsEmptyWithoutGeneratingOwnershipWhenQueueIsEmpty() {
        when(claimRepository.lockNextPendingExecutionId()).thenReturn(Optional.empty());

        assertThat(service.claimNext(
                new ClaimExecutionCommand("runner-1", Duration.ofMinutes(1)))).isEmpty();

        verifyNoInteractions(executionRepository, leaseRepository, tokenGenerator);
        verify(claimRepository, never()).currentDatabaseTime();
    }

    @Test
    void claimsManagedExecutionAndBuildsMinimalInternalResult() {
        UUID executionId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        OffsetDateTime databaseTime = OffsetDateTime.parse("2026-07-25T10:00:00Z");
        Execution execution = execution(executionId);
        when(claimRepository.lockNextPendingExecutionId())
                .thenReturn(Optional.of(executionId));
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(claimRepository.currentDatabaseTime()).thenReturn(databaseTime);
        when(tokenGenerator.nextToken()).thenReturn(token);
        when(executionRepository.saveAndFlush(execution)).thenReturn(execution);
        when(leaseRepository.saveAndFlush(any(ExecutionLease.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.claimNext(
                new ClaimExecutionCommand("  runner-1  ", Duration.ofMinutes(2)))
                .orElseThrow();

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CLAIMED);
        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.status()).isEqualTo(ExecutionStatus.CLAIMED);
        assertThat(result.runnerId()).isEqualTo("runner-1");
        assertThat(result.claimToken()).isEqualTo(token);
        assertThat(result.leaseGeneration()).isEqualTo(1);
        assertThat(result.claimedAt()).isEqualTo(databaseTime);
        assertThat(result.leaseExpiresAt()).isEqualTo(databaseTime.plusMinutes(2));
        assertThat(result.environmentSnapshot()).containsEntry("region", "eu");

        ArgumentCaptor<ExecutionLease> leaseCaptor =
                ArgumentCaptor.forClass(ExecutionLease.class);
        verify(leaseRepository).saveAndFlush(leaseCaptor.capture());
        assertThat(leaseCaptor.getValue().getLastHeartbeatAt()).isEqualTo(databaseTime);
        assertThat(leaseCaptor.getValue().getExecution()).isSameAs(execution);
    }

    @Test
    void rejectsInvalidCommandsBeforeLockingQueue() {
        assertInvalid(null);
        assertInvalid(new ClaimExecutionCommand(null, Duration.ofMinutes(1)));
        assertInvalid(new ClaimExecutionCommand("   ", Duration.ofMinutes(1)));
        assertInvalid(new ClaimExecutionCommand("x".repeat(151), Duration.ofMinutes(1)));
        assertInvalid(new ClaimExecutionCommand("runner", null));
        assertInvalid(new ClaimExecutionCommand("runner", Duration.ZERO));
        assertInvalid(new ClaimExecutionCommand("runner", Duration.ofSeconds(-1)));
        assertInvalid(new ClaimExecutionCommand("runner", Duration.ofHours(24).plusSeconds(1)));

        verifyNoInteractions(
                claimRepository, executionRepository, leaseRepository, tokenGenerator);
    }

    private void assertInvalid(ClaimExecutionCommand command) {
        assertThatThrownBy(() -> service.claimNext(command))
                .isInstanceOf(InvalidRequestException.class);
    }

    private Execution execution(UUID executionId) {
        when(project.getId()).thenReturn(UUID.randomUUID());
        when(environment.getId()).thenReturn(UUID.randomUUID());
        when(suite.getId()).thenReturn(UUID.randomUUID());

        Execution execution = new Execution();
        execution.setId(executionId);
        execution.setProject(project);
        execution.setEnvironment(environment);
        execution.setAutomationSuite(suite);
        execution.setSelectionMode(ExecutionSelectionMode.SUITE);
        execution.setRequestedBy("requester");
        execution.setEnvironmentSnapshot(Map.of("region", "eu"));
        execution.setSuiteSnapshot(Map.of("engine", "PLAYWRIGHT"));
        execution.setRequestSnapshot(Map.of("selectionMode", "SUITE"));
        return execution;
    }
}
