package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.ExecutionHeartbeatRepository;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ExecutionHeartbeatException;
import com.automationstudio.api.service.HeartbeatFailure;
import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionHeartbeatServiceImplTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID TOKEN = UUID.randomUUID();
    private static final OffsetDateTime DB_TIME =
            OffsetDateTime.parse("2026-07-25T10:00:00Z");

    @Mock
    private ExecutionLeaseRepository leaseRepository;

    @Mock
    private ExecutionHeartbeatRepository heartbeatRepository;

    @Mock
    private ExecutionRepository executionRepository;

    private ExecutionHeartbeatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExecutionHeartbeatServiceImpl(
                leaseRepository, executionRepository, heartbeatRepository);
    }

    @Test
    void renewsCurrentOwnerAndMapsResultWithoutReturningToken() {
        ExecutionLease lease = lease(ExecutionStatus.CLAIMED);
        when(leaseRepository.findByExecutionIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease));
        when(executionRepository.findByIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease.getExecution()));
        when(heartbeatRepository.currentDatabaseTime()).thenReturn(DB_TIME);
        when(leaseRepository.saveAndFlush(lease)).thenAnswer(invocation -> {
            ExecutionLease saved = invocation.getArgument(0);
            saved.setVersion(4);
            return saved;
        });

        var result = service.renew(command(" runner-1 ", TOKEN, 1, 3));

        assertThat(result.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(result.runnerId()).isEqualTo("runner-1");
        assertThat(result.leaseGeneration()).isEqualTo(1);
        assertThat(result.leaseVersion()).isEqualTo(4);
        assertThat(result.lastHeartbeatAt()).isEqualTo(DB_TIME);
        assertThat(result.leaseExpiresAt()).isEqualTo(DB_TIME.plusMinutes(2));
        assertThat(result.getClass().getRecordComponents())
                .noneMatch(component -> component.getName().equals("claimToken"));
    }

    @Test
    void renewsDuringClaimedStartupAndRunningExecution() {
        for (ExecutionStatus status : java.util.List.of(
                ExecutionStatus.CLAIMED, ExecutionStatus.RUNNING)) {
            ExecutionLease lease = lease(status);
            when(leaseRepository.findByExecutionIdForUpdate(EXECUTION_ID))
                    .thenReturn(Optional.of(lease));
            when(executionRepository.findByIdForUpdate(EXECUTION_ID))
                    .thenReturn(Optional.of(lease.getExecution()));
            when(heartbeatRepository.currentDatabaseTime()).thenReturn(DB_TIME);
            when(leaseRepository.saveAndFlush(lease)).thenReturn(lease);

            assertThat(service.renew(command("runner-1", TOKEN, 1, 3)).leaseExpiresAt())
                    .isEqualTo(DB_TIME.plusMinutes(2));
        }
    }

    @Test
    void rejectsInvalidCommandsBeforeRepositoryAccess() {
        assertInvalid(null);
        assertInvalid(new RenewExecutionLeaseCommand(
                null, "runner", TOKEN, 1, 0, Duration.ofMinutes(1)));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, " ", TOKEN, 1, 0, Duration.ofMinutes(1)));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "x".repeat(151), TOKEN, 1, 0, Duration.ofMinutes(1)));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", null, 1, 0, Duration.ofMinutes(1)));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 0, 0, Duration.ofMinutes(1)));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 1, -1, Duration.ofMinutes(1)));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 1, 0, null));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 1, 0, Duration.ZERO));
        assertInvalid(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 1, 0, Duration.ofHours(24).plusNanos(1)));

        verifyNoInteractions(leaseRepository, executionRepository, heartbeatRepository);
    }

    @Test
    void mapsMissingLeaseAndOwnershipFailureWithoutCredentialDisclosure() {
        when(leaseRepository.findByExecutionIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.empty());
        assertFailure(command("runner-1", TOKEN, 1, 3), HeartbeatFailure.LEASE_NOT_FOUND);

        ExecutionLease lease = lease(ExecutionStatus.CLAIMED);
        when(leaseRepository.findByExecutionIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease));
        when(executionRepository.findByIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease.getExecution()));
        ExecutionHeartbeatException failure = assertFailure(
                command("wrong-runner", UUID.randomUUID(), 1, 3),
                HeartbeatFailure.OWNERSHIP_MISMATCH);
        assertThat(failure.getMessage()).doesNotContain(TOKEN.toString());
        assertThat(failure.getMessage()).doesNotContain(lease.getClaimToken().toString());
        verify(heartbeatRepository, never()).currentDatabaseTime();
    }

    @Test
    void mapsGenerationLifecycleAndVersionFailures() {
        ExecutionLease lease = lease(ExecutionStatus.CLAIMED);
        when(leaseRepository.findByExecutionIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease));
        when(executionRepository.findByIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease.getExecution()));

        assertFailure(command("runner-1", TOKEN, 2, 3), HeartbeatFailure.STALE_GENERATION);
        assertFailure(command("runner-1", TOKEN, 1, 2),
                HeartbeatFailure.OPTIMISTIC_LOCK_CONFLICT);
        lease.getExecution().requestCancellation(DB_TIME, "actor", null);
        assertFailure(command("runner-1", TOKEN, 1, 3),
                HeartbeatFailure.EXECUTION_STATE_INELIGIBLE);

        verify(heartbeatRepository, never()).currentDatabaseTime();
    }

    @Test
    void rejectsLeaseAtExactExpiryBoundary() {
        ExecutionLease lease = lease(ExecutionStatus.CLAIMED);
        lease.setLeaseExpiresAt(DB_TIME);
        when(leaseRepository.findByExecutionIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease));
        when(executionRepository.findByIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease.getExecution()));
        when(heartbeatRepository.currentDatabaseTime()).thenReturn(DB_TIME);

        assertFailure(command("runner-1", TOKEN, 1, 3), HeartbeatFailure.EXPIRED_LEASE);

        verify(leaseRepository, never()).saveAndFlush(lease);
    }

    private void assertInvalid(RenewExecutionLeaseCommand command) {
        assertThatThrownBy(() -> service.renew(command))
                .isInstanceOf(InvalidRequestException.class);
    }

    private ExecutionHeartbeatException assertFailure(
            RenewExecutionLeaseCommand command, HeartbeatFailure expected) {
        ExecutionHeartbeatException exception = catchThrowableOfType(
                ExecutionHeartbeatException.class, () -> service.renew(command));
        assertThat(exception.getFailure()).isEqualTo(expected);
        return exception;
    }

    private RenewExecutionLeaseCommand command(
            String runnerId, UUID token, long generation, long version) {
        return new RenewExecutionLeaseCommand(
                EXECUTION_ID, runnerId, token, generation, version, Duration.ofMinutes(2));
    }

    private ExecutionLease lease(ExecutionStatus status) {
        Execution execution = new Execution();
        if (status == ExecutionStatus.CLAIMED) {
            execution.claim();
        } else if (status == ExecutionStatus.RUNNING) {
            execution.claim();
            execution.start(DB_TIME.minusSeconds(30));
        } else if (status == ExecutionStatus.CANCEL_REQUESTED) {
            execution.requestCancellation(DB_TIME, "actor", null);
        }
        ExecutionLease lease = new ExecutionLease();
        lease.setExecutionId(EXECUTION_ID);
        lease.setExecution(execution);
        lease.setRunnerId("runner-1");
        lease.setClaimToken(TOKEN);
        lease.setLeaseGeneration(1L);
        lease.setVersion(3);
        lease.setClaimedAt(DB_TIME.minusMinutes(1));
        lease.setLastHeartbeatAt(DB_TIME.minusMinutes(1));
        lease.setLeaseExpiresAt(DB_TIME.plusMinutes(1));
        return lease;
    }
}
