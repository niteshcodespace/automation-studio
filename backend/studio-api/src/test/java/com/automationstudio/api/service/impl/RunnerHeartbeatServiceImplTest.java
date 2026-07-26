package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.config.RunnerHealthProperties;
import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.command.RecordRunnerHeartbeatCommand;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RunnerHeartbeatServiceImplTest {

    private static final UUID RUNNER_ID = UUID.fromString(
            "71000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime EVALUATED_AT =
            OffsetDateTime.parse("2026-07-26T12:00:00Z");

    @Mock
    private RunnerRepository runnerRepository;

    @Mock
    private RunnerRuntimeRepository runtimeRepository;

    private RunnerHeartbeatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RunnerHeartbeatServiceImpl(
                runnerRepository,
                runtimeRepository,
                new RunnerHealthProperties(Duration.ofMinutes(1), Duration.ofMinutes(5)));
    }

    @Test
    void activeHeartbeatCanonicalizesKeyAndLocksRunnerBeforeRuntime() {
        Runner runner = runner(RunnerStatus.ACTIVE);
        RunnerRuntime runtime = runtime(EVALUATED_AT.minusSeconds(30), 4, 2);
        when(runnerRepository.findByRunnerKeyForUpdate("runner-01"))
                .thenReturn(Optional.of(runner));
        when(runtimeRepository.findByRunnerIdForUpdate(RUNNER_ID))
                .thenReturn(Optional.of(runtime));
        when(runnerRepository.currentDatabaseTime()).thenReturn(EVALUATED_AT.toInstant());
        when(runtimeRepository.saveAndFlush(runtime)).thenReturn(runtime);

        var result = service.recordHeartbeat(
                new RecordRunnerHeartbeatCommand(" Runner-01 "));

        assertThat(result.runnerId()).isEqualTo(RUNNER_ID);
        assertThat(result.runnerKey()).isEqualTo("runner-01");
        assertThat(result.lastSeenAt()).isEqualTo(EVALUATED_AT);
        assertThat(result.heartbeatCount()).isEqualTo(5);
        InOrder order = inOrder(runnerRepository, runtimeRepository);
        order.verify(runnerRepository).findByRunnerKeyForUpdate("runner-01");
        order.verify(runtimeRepository).findByRunnerIdForUpdate(RUNNER_ID);
        order.verify(runnerRepository).currentDatabaseTime();
        order.verify(runtimeRepository).saveAndFlush(runtime);
    }

    @Test
    void disabledHeartbeatIsAcceptedWithoutChangingLifecycle() {
        Runner runner = runner(RunnerStatus.DISABLED);
        RunnerRuntime runtime = runtime(EVALUATED_AT.minusMinutes(1), 0, 0);
        stubHeartbeat(runner, runtime);

        service.recordHeartbeat(new RecordRunnerHeartbeatCommand("runner-01"));

        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.DISABLED);
        assertThat(runtime.getHeartbeatCount()).isEqualTo(1);
        verify(runnerRepository, never()).saveAndFlush(any());
    }

    @Test
    void deregisteredHeartbeatIsRejectedBeforeRuntimeLock() {
        when(runnerRepository.findByRunnerKeyForUpdate("runner-01"))
                .thenReturn(Optional.of(runner(RunnerStatus.DEREGISTERED)));

        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.recordHeartbeat(
                        new RecordRunnerHeartbeatCommand("runner-01")));

        verifyNoInteractions(runtimeRepository);
        verify(runnerRepository, never()).currentDatabaseTime();
    }

    @Test
    void missingRunnerAndRuntimeAreReported() {
        when(runnerRepository.findByRunnerKeyForUpdate("missing"))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.recordHeartbeat(
                        new RecordRunnerHeartbeatCommand("missing")));

        Runner runner = runner(RunnerStatus.ACTIVE);
        when(runnerRepository.findByRunnerKeyForUpdate("runner-01"))
                .thenReturn(Optional.of(runner));
        when(runtimeRepository.findByRunnerIdForUpdate(RUNNER_ID))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.recordHeartbeat(
                        new RecordRunnerHeartbeatCommand("runner-01")));
    }

    @Test
    void invalidHeartbeatCommandsAreRejectedBeforePersistence() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.recordHeartbeat(null));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.recordHeartbeat(
                        new RecordRunnerHeartbeatCommand(" ")));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.recordHeartbeat(
                        new RecordRunnerHeartbeatCommand("invalid key")));

        verifyNoInteractions(runnerRepository, runtimeRepository);
    }

    @Test
    void healthUsesExactOnlineAndOfflineBoundaries() {
        assertHealth(EVALUATED_AT.minusSeconds(59), RunnerHealth.ONLINE);
        assertHealth(EVALUATED_AT.minusMinutes(1), RunnerHealth.ONLINE);
        assertHealth(EVALUATED_AT.minusMinutes(1).minusNanos(1), RunnerHealth.STALE);
        assertHealth(EVALUATED_AT.minusMinutes(5), RunnerHealth.STALE);
        assertHealth(EVALUATED_AT.minusMinutes(5).minusNanos(1), RunnerHealth.OFFLINE);
    }

    @Test
    void futureLastSeenIsClampedToOnlineAndLifecycleRemainsSeparate() {
        Runner runner = runner(RunnerStatus.DISABLED);
        RunnerRuntime runtime = runtime(EVALUATED_AT.plusSeconds(1), 3, 2);
        when(runnerRepository.findById(RUNNER_ID)).thenReturn(Optional.of(runner));
        when(runtimeRepository.findByRunnerId(RUNNER_ID)).thenReturn(Optional.of(runtime));

        var result = service.evaluateHealth(RUNNER_ID, EVALUATED_AT);

        assertThat(result.health()).isEqualTo(RunnerHealth.ONLINE);
        assertThat(result.lifecycleStatus()).isEqualTo(RunnerStatus.DISABLED);
        assertThat(result.evaluatedAt()).isEqualTo(EVALUATED_AT);
        verify(runtimeRepository, never()).save(any());
    }

    @Test
    void databaseTimedHealthCanBeEvaluatedByCanonicalKey() {
        Runner runner = runner(RunnerStatus.ACTIVE);
        RunnerRuntime runtime = runtime(EVALUATED_AT.minusMinutes(2), 3, 2);
        when(runnerRepository.findByRunnerKey("runner-01"))
                .thenReturn(Optional.of(runner));
        when(runnerRepository.currentDatabaseTime()).thenReturn(EVALUATED_AT.toInstant());
        when(runtimeRepository.findByRunnerId(RUNNER_ID)).thenReturn(Optional.of(runtime));

        var result = service.evaluateHealth(" Runner-01 ");

        assertThat(result.health()).isEqualTo(RunnerHealth.STALE);
    }

    @Test
    void healthRejectsInvalidInputAndMissingState() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.evaluateHealth((UUID) null));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.evaluateHealth(RUNNER_ID, null));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.evaluateHealth(" "));

        when(runnerRepository.findById(RUNNER_ID)).thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.evaluateHealth(RUNNER_ID, EVALUATED_AT));
    }

    @Test
    void runtimeMutationRejectsNullBackwardTimeAndCountOverflow() {
        RunnerRuntime runtime = runtime(EVALUATED_AT, 2, 0);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> runtime.recordHeartbeat(null));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> runtime.recordHeartbeat(EVALUATED_AT.minusNanos(1)));

        ReflectionTestUtils.setField(runtime, "heartbeatCount", Long.MAX_VALUE);
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> runtime.recordHeartbeat(EVALUATED_AT));
    }

    private void assertHealth(OffsetDateTime lastSeenAt, RunnerHealth expected) {
        Runner runner = runner(RunnerStatus.ACTIVE);
        RunnerRuntime runtime = runtime(lastSeenAt, 0, 0);
        when(runnerRepository.findById(RUNNER_ID)).thenReturn(Optional.of(runner));
        when(runtimeRepository.findByRunnerId(RUNNER_ID)).thenReturn(Optional.of(runtime));

        assertThat(service.evaluateHealth(RUNNER_ID, EVALUATED_AT).health())
                .isEqualTo(expected);

        org.mockito.Mockito.reset(runnerRepository, runtimeRepository);
    }

    private void stubHeartbeat(Runner runner, RunnerRuntime runtime) {
        when(runnerRepository.findByRunnerKeyForUpdate("runner-01"))
                .thenReturn(Optional.of(runner));
        when(runtimeRepository.findByRunnerIdForUpdate(RUNNER_ID))
                .thenReturn(Optional.of(runtime));
        when(runnerRepository.currentDatabaseTime()).thenReturn(EVALUATED_AT.toInstant());
        when(runtimeRepository.saveAndFlush(runtime)).thenReturn(runtime);
    }

    private Runner runner(RunnerStatus status) {
        Runner runner = new Runner(
                "runner-01",
                "Runner",
                null,
                "1.0.0",
                "runner.internal",
                "linux",
                "amd64",
                4,
                Map.of(),
                Map.of(),
                status,
                EVALUATED_AT.minusDays(1));
        ReflectionTestUtils.setField(runner, "id", RUNNER_ID);
        return runner;
    }

    private RunnerRuntime runtime(
            OffsetDateTime lastSeenAt,
            long heartbeatCount,
            long version) {
        RunnerRuntime runtime = new RunnerRuntime(RUNNER_ID, lastSeenAt);
        ReflectionTestUtils.setField(runtime, "heartbeatCount", heartbeatCount);
        ReflectionTestUtils.setField(runtime, "version", version);
        return runtime;
    }
}
