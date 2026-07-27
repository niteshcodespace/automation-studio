package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.config.RunnerHealthProperties;
import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.domain.SchedulingCandidate;
import com.automationstudio.api.domain.SchedulingOutcome;
import com.automationstudio.api.domain.SchedulingRequirements;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.repository.SchedulingCandidateRepository;
import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.service.result.SchedulingResult;
import java.time.Duration;
import java.time.Instant;
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
class RunnerSchedulingServiceImplTest {

    private static final UUID RUNNER_ID = UUID.randomUUID();
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    @Mock private RunnerRepository runnerRepository;
    @Mock private RunnerRuntimeRepository runtimeRepository;
    @Mock private ExecutionLeaseRepository leaseRepository;
    @Mock private SchedulingCandidateRepository candidateRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private ClaimTokenGenerator tokenGenerator;
    @Mock private Runner runner;
    @Mock private RunnerRuntime runtime;
    @Mock private Project project;
    @Mock private Environment environment;
    @Mock private AutomationSuite suite;

    private RunnerSchedulingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RunnerSchedulingServiceImpl(
                runnerRepository,
                runtimeRepository,
                leaseRepository,
                candidateRepository,
                executionRepository,
                tokenGenerator,
                new RunnerHealthProperties(Duration.ofMinutes(1), Duration.ofMinutes(5)));
    }

    @Test
    void returnsRunnerNotFoundBeforeRuntimeOrCapacityAccess() {
        when(runnerRepository.findByRunnerKeyForUpdate("runner-1"))
                .thenReturn(Optional.empty());
        when(runnerRepository.currentDatabaseTime()).thenReturn(NOW);

        SchedulingResult result = service.scheduleNext(command());

        assertThat(result.outcome()).isEqualTo(SchedulingOutcome.RUNNER_NOT_FOUND);
        assertThat(result.scheduledExecution()).isEmpty();
        verifyNoInteractions(runtimeRepository, leaseRepository, candidateRepository);
    }

    @Test
    void returnsIneligibleForStaleRunnerWithoutLockingCandidate() {
        arrangeRunner(2, NOW.minusSeconds(61));

        SchedulingResult result = service.scheduleNext(command());

        assertThat(result.outcome()).isEqualTo(SchedulingOutcome.RUNNER_INELIGIBLE);
        assertThat(result.runnerEligibility().health().name()).isEqualTo("STALE");
        verifyNoInteractions(candidateRepository);
    }

    @Test
    void returnsCapacityExhaustedAtExactMaximum() {
        arrangeRunner(1, NOW);
        when(leaseRepository.countCapacityConsumingLeases(
                any(), any(), anySet())).thenReturn(1L);

        SchedulingResult result = service.scheduleNext(command());

        assertThat(result.outcome()).isEqualTo(SchedulingOutcome.CAPACITY_EXHAUSTED);
        assertThat(result.runnerEligibility().capacity().availableCapacity()).isZero();
        verifyNoInteractions(candidateRepository);
    }

    @Test
    void returnsNoWorkWithoutExecutionOrLeaseMutation() {
        arrangeRunner(2, NOW);
        when(candidateRepository.lockNextCompatible(any())).thenReturn(Optional.empty());

        SchedulingResult result = service.scheduleNext(command());

        assertThat(result.outcome())
                .isEqualTo(SchedulingOutcome.NO_COMPATIBLE_EXECUTION);
        verifyNoInteractions(executionRepository, tokenGenerator);
        verify(leaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void atomicallyClaimsCompatibleCandidateAndCreatesAs019Lease() {
        arrangeRunner(2, NOW);
        SchedulingCandidate candidate = new SchedulingCandidate(
                EXECUTION_ID,
                NOW.atOffset(java.time.ZoneOffset.UTC).minusMinutes(1),
                new SchedulingRequirements("playwright-java", Map.of(), Map.of()));
        Execution execution = execution();
        UUID token = UUID.randomUUID();
        when(candidateRepository.lockNextCompatible(any()))
                .thenReturn(Optional.of(candidate));
        when(executionRepository.findById(EXECUTION_ID))
                .thenReturn(Optional.of(execution));
        when(leaseRepository.existsById(EXECUTION_ID)).thenReturn(false);
        when(tokenGenerator.nextToken()).thenReturn(token);
        when(executionRepository.saveAndFlush(execution)).thenReturn(execution);
        when(leaseRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SchedulingResult result = service.scheduleNext(command());

        assertThat(result.outcome()).isEqualTo(SchedulingOutcome.SCHEDULED);
        assertThat(result.scheduledExecution()).hasValueSatisfying(claimed -> {
            assertThat(claimed.executionId()).isEqualTo(EXECUTION_ID);
            assertThat(claimed.runnerId()).isEqualTo("runner-1");
            assertThat(claimed.claimToken()).isEqualTo(token);
            assertThat(claimed.claimedAt()).isEqualTo(NOW.atOffset(java.time.ZoneOffset.UTC));
            assertThat(claimed.leaseExpiresAt())
                    .isEqualTo(NOW.atOffset(java.time.ZoneOffset.UTC).plusMinutes(2));
        });
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CLAIMED);
    }

    @Test
    void validatesCommandAndResultInvariants() {
        for (ScheduleExecutionCommand invalid : new ScheduleExecutionCommand[] {
                null,
                new ScheduleExecutionCommand(" ", Duration.ofMinutes(1)),
                new ScheduleExecutionCommand("bad key", Duration.ofMinutes(1)),
                new ScheduleExecutionCommand("runner", Duration.ZERO),
                new ScheduleExecutionCommand("runner", Duration.ofHours(25))
        }) {
            assertThatThrownBy(() -> service.scheduleNext(invalid))
                    .isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> new SchedulingResult(
                SchedulingOutcome.SCHEDULED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(
                runnerRepository,
                runtimeRepository,
                leaseRepository,
                candidateRepository,
                executionRepository);
    }

    private void arrangeRunner(int maxConcurrency, Instant lastSeen) {
        when(runnerRepository.findByRunnerKeyForUpdate("runner-1"))
                .thenReturn(Optional.of(runner));
        when(runtimeRepository.findByRunnerIdForUpdate(RUNNER_ID))
                .thenReturn(Optional.of(runtime));
        when(runnerRepository.currentDatabaseTime()).thenReturn(NOW);
        when(runner.getId()).thenReturn(RUNNER_ID);
        when(runner.getRunnerKey()).thenReturn("runner-1");
        when(runner.getStatus()).thenReturn(RunnerStatus.ACTIVE);
        when(runner.getMaxConcurrency()).thenReturn(maxConcurrency);
        when(runner.getCapabilities()).thenReturn(
                Map.of("engines", Map.of("playwright-java", "1.52.0")));
        when(runner.getLabels()).thenReturn(Map.of("region", "eu"));
        when(runtime.getRunnerId()).thenReturn(RUNNER_ID);
        when(runtime.getLastSeenAt()).thenReturn(
                lastSeen.atOffset(java.time.ZoneOffset.UTC));
        when(leaseRepository.countCapacityConsumingLeases(
                any(), any(), anySet())).thenReturn(0L);
    }

    private Execution execution() {
        when(project.getId()).thenReturn(UUID.randomUUID());
        when(environment.getId()).thenReturn(UUID.randomUUID());
        when(suite.getId()).thenReturn(UUID.randomUUID());
        Execution execution = new Execution();
        execution.setId(EXECUTION_ID);
        execution.setProject(project);
        execution.setEnvironment(environment);
        execution.setAutomationSuite(suite);
        execution.setSelectionMode(ExecutionSelectionMode.SUITE);
        execution.setRequestedBy("requester");
        execution.setEnvironmentSnapshot(Map.of("id", environment.getId().toString()));
        execution.setSuiteSnapshot(Map.of("engineId", "playwright-java"));
        execution.setRequestSnapshot(Map.of("selectionMode", "SUITE"));
        return execution;
    }

    private ScheduleExecutionCommand command() {
        return new ScheduleExecutionCommand(" RUNNER-1 ", Duration.ofMinutes(2));
    }
}
