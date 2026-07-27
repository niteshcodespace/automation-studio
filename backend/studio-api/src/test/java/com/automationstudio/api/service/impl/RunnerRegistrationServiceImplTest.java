package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.exception.RunnerAlreadyDeregisteredException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.command.RegisterRunnerCommand;
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
class RunnerRegistrationServiceImplTest {

    private static final UUID RUNNER_ID = UUID.fromString(
            "70000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime DATABASE_TIME =
            OffsetDateTime.parse("2026-07-26T10:00:00Z");

    @Mock
    private RunnerRepository runnerRepository;

    @Mock
    private RunnerRuntimeRepository runtimeRepository;

    private RunnerRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RunnerRegistrationServiceImpl(runnerRepository, runtimeRepository);
    }

    @Test
    void newRegistrationUsesKeyLockAndCreatesRunnerThenRuntime() {
        when(runnerRepository.findByRunnerKeyForUpdate("runner-01"))
                .thenReturn(Optional.empty());
        when(runnerRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME.toInstant());
        when(runnerRepository.saveAndFlush(any(Runner.class)))
                .thenAnswer(invocation -> {
                    Runner runner = invocation.getArgument(0);
                    ReflectionTestUtils.setField(runner, "id", RUNNER_ID);
                    return runner;
                });
        when(runtimeRepository.saveAndFlush(any(RunnerRuntime.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Runner result = service.register(command(" Runner-01 ", "Original"));

        assertThat(result.getRunnerKey()).isEqualTo("runner-01");
        assertThat(result.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(result.getRegisteredAt()).isEqualTo(DATABASE_TIME);
        InOrder order = inOrder(runnerRepository, runtimeRepository);
        order.verify(runnerRepository).lockRegistrationKey("runner-01");
        order.verify(runnerRepository).findByRunnerKeyForUpdate("runner-01");
        order.verify(runnerRepository).currentDatabaseTime();
        order.verify(runnerRepository).saveAndFlush(result);
        order.verify(runtimeRepository).saveAndFlush(any(RunnerRuntime.class));
    }

    @Test
    void activeAndDisabledReregistrationLockRuntimeAndUpdateOnlyMetadata() {
        for (RunnerStatus status : new RunnerStatus[] {
                RunnerStatus.ACTIVE, RunnerStatus.DISABLED
        }) {
            Runner runner = existingRunner(status);
            RunnerRuntime runtime = new RunnerRuntime(RUNNER_ID, DATABASE_TIME.minusMinutes(2));
            when(runnerRepository.findByRunnerKeyForUpdate("runner-01"))
                    .thenReturn(Optional.of(runner));
            when(runnerRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME.toInstant());
            when(runtimeRepository.findByRunnerIdForUpdate(RUNNER_ID))
                    .thenReturn(Optional.of(runtime));
            when(runnerRepository.saveAndFlush(runner)).thenReturn(runner);

            Runner result = service.register(command("runner-01", "Updated"));

            assertThat(result.getName()).isEqualTo("Updated");
            assertThat(result.getStatus()).isEqualTo(status);
            assertThat(result.getRegisteredAt()).isEqualTo(DATABASE_TIME.minusDays(1));
            assertThat(result.getLastRegisteredAt()).isEqualTo(DATABASE_TIME);
            verify(runtimeRepository).findByRunnerIdForUpdate(RUNNER_ID);
            verify(runtimeRepository, never()).save(any());

            org.mockito.Mockito.reset(runnerRepository, runtimeRepository);
        }
    }

    @Test
    void deregisteredRunnerIsRejectedBeforeRuntimeAccess() {
        Runner runner = existingRunner(RunnerStatus.DEREGISTERED);
        when(runnerRepository.findByRunnerKeyForUpdate("runner-01"))
                .thenReturn(Optional.of(runner));
        when(runnerRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME.toInstant());

        assertThatExceptionOfType(RunnerAlreadyDeregisteredException.class)
                .isThrownBy(() -> service.register(command("runner-01", "Rejected")));

        verifyNoInteractions(runtimeRepository);
        verify(runnerRepository, never()).saveAndFlush(any());
    }

    @Test
    void invalidCommandsAreRejectedBeforePersistence() {
        assertInvalid(null);
        assertInvalid(command(" ", "Name"));
        assertInvalid(command("invalid key", "Name"));
        assertInvalid(command("runner-01", " "));
        assertInvalid(new RegisterRunnerCommand(
                "runner-01", "Name", null, "1.0", "host", "linux", "amd64",
                0, Map.of(), Map.of()));
        assertInvalid(new RegisterRunnerCommand(
                "runner-01", "Name", null, "1.0", "host", "linux", "amd64",
                1, null, Map.of()));
        assertInvalid(new RegisterRunnerCommand(
                "runner-01", "Name", null, "1.0", "host", "linux", "amd64",
                1, Map.of(), null));

        verifyNoInteractions(runnerRepository, runtimeRepository);
    }

    @Test
    void getOperationsNormalizeKeysAndReportMissingRunners() {
        Runner runner = existingRunner(RunnerStatus.ACTIVE);
        when(runnerRepository.findById(RUNNER_ID)).thenReturn(Optional.of(runner));
        when(runnerRepository.findByRunnerKey("runner-01")).thenReturn(Optional.of(runner));

        assertThat(service.getRunner(RUNNER_ID)).isSameAs(runner);
        assertThat(service.getRunner(" Runner-01 ")).isSameAs(runner);

        UUID missingId = UUID.randomUUID();
        when(runnerRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getRunner(missingId));
    }

    private void assertInvalid(RegisterRunnerCommand command) {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.register(command));
    }

    private RegisterRunnerCommand command(String runnerKey, String name) {
        return new RegisterRunnerCommand(
                runnerKey,
                name,
                "Description",
                "1.0.0",
                "runner.internal",
                "linux",
                "amd64",
                4,
                Map.of("features", java.util.List.of("docker")),
                Map.of("pool", "build"));
    }

    private Runner existingRunner(RunnerStatus status) {
        OffsetDateTime registeredAt = DATABASE_TIME.minusDays(1);
        Runner runner = new Runner(
                "runner-01",
                "Original",
                null,
                "0.9.0",
                "old.internal",
                "linux",
                "amd64",
                2,
                Map.of(),
                Map.of(),
                status,
                registeredAt);
        ReflectionTestUtils.setField(runner, "id", RUNNER_ID);
        return runner;
    }
}
