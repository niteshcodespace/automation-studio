package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionReclaimRepository;
import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.ExecutionReclaimException;
import com.automationstudio.api.service.ReclaimFailure;
import com.automationstudio.api.service.command.ReclaimExecutionLeaseCommand;
import java.time.Duration;
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
class ExecutionReclaimServiceImplTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID OLD_TOKEN = UUID.randomUUID();
    private static final UUID NEW_TOKEN = UUID.randomUUID();
    private static final OffsetDateTime DB_TIME =
            OffsetDateTime.parse("2026-07-26T10:00:00Z");

    @Mock
    private ExecutionReclaimRepository reclaimRepository;
    @Mock
    private ExecutionLeaseRepository leaseRepository;
    @Mock
    private ClaimTokenGenerator tokenGenerator;

    private ExecutionReclaimServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExecutionReclaimServiceImpl(
                reclaimRepository, leaseRepository, tokenGenerator);
    }

    @Test
    void reclaimsExpiredLeaseAndMapsImmutableDispatchResult() {
        ExecutionLease lease = lease();
        when(reclaimRepository.lockNextExpiredClaimedExecutionId())
                .thenReturn(Optional.of(EXECUTION_ID));
        when(leaseRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(lease));
        lenient().when(reclaimRepository.currentDatabaseTime()).thenReturn(DB_TIME);
        when(tokenGenerator.nextToken()).thenReturn(NEW_TOKEN);
        when(leaseRepository.saveAndFlush(lease)).thenAnswer(invocation -> {
            lease.setVersion(4);
            return lease;
        });

        var result = service.reclaimNext(command(" new-runner ")).orElseThrow();

        assertThat(result.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(result.runnerId()).isEqualTo("new-runner");
        assertThat(result.claimToken()).isEqualTo(NEW_TOKEN);
        assertThat(result.leaseGeneration()).isEqualTo(2);
        assertThat(result.leaseVersion()).isEqualTo(4);
        assertThat(result.executionVersion()).isEqualTo(7);
        assertThat(result.claimedAt()).isEqualTo(DB_TIME);
        assertThat(result.lastHeartbeatAt()).isEqualTo(DB_TIME);
        assertThat(result.leaseExpiresAt()).isEqualTo(DB_TIME.plusMinutes(2));
        assertThatThrownBy(() -> result.requestSnapshot().put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesRequestBeforeRepositoryAccess() {
        assertInvalid(null);
        assertInvalid(new ReclaimExecutionLeaseCommand(null, Duration.ofMinutes(1)));
        assertInvalid(new ReclaimExecutionLeaseCommand(" ", Duration.ofMinutes(1)));
        assertInvalid(new ReclaimExecutionLeaseCommand(
                "x".repeat(151), Duration.ofMinutes(1)));
        assertInvalid(new ReclaimExecutionLeaseCommand("runner", null));
        assertInvalid(new ReclaimExecutionLeaseCommand("runner", Duration.ZERO));
        assertInvalid(new ReclaimExecutionLeaseCommand("runner", Duration.ofSeconds(-1)));
        assertInvalid(new ReclaimExecutionLeaseCommand(
                "runner", Duration.ofHours(24).plusNanos(1)));
        verifyNoInteractions(reclaimRepository, leaseRepository, tokenGenerator);
    }

    @Test
    void returnsEmptyWhenNoExpiredClaimedLeaseIsAvailable() {
        when(reclaimRepository.lockNextExpiredClaimedExecutionId())
                .thenReturn(Optional.empty());

        assertThat(service.reclaimNext(command("runner"))).isEmpty();

        verifyNoInteractions(leaseRepository, tokenGenerator);
    }

    @Test
    void rejectsActiveAndIneligibleLeaseWithoutGeneratingToken() {
        ExecutionLease lease = lease();
        arrange(lease);
        lease.setLeaseExpiresAt(DB_TIME.plusNanos(1));
        assertFailure(ReclaimFailure.LEASE_STILL_ACTIVE);

        lease = lease();
        lease.getExecution().requestCancellation(DB_TIME.minusMinutes(2), "actor", null);
        arrange(lease);
        assertFailure(ReclaimFailure.EXECUTION_STATE_INELIGIBLE);

        verify(tokenGenerator, never()).nextToken();
    }

    @Test
    void rejectsGenerationOverflowWithoutMutation() {
        ExecutionLease lease = lease();
        lease.setLeaseGeneration(Long.MAX_VALUE);
        arrange(lease);

        assertFailure(ReclaimFailure.GENERATION_OVERFLOW);

        assertThat(lease.getRunnerId()).isEqualTo("old-runner");
        assertThat(lease.getClaimToken()).isEqualTo(OLD_TOKEN);
        assertThat(lease.getLeaseGeneration()).isEqualTo(Long.MAX_VALUE);
        verify(tokenGenerator, never()).nextToken();
    }

    @Test
    void mapsTokenFailureWithoutCredentialDisclosureOrMutation() {
        ExecutionLease lease = lease();
        arrange(lease);
        when(tokenGenerator.nextToken()).thenThrow(new IllegalStateException("generator failed"));

        ExecutionReclaimException error = assertFailure(
                ReclaimFailure.TOKEN_GENERATION_FAILED);

        assertThat(error.getMessage()).doesNotContain(OLD_TOKEN.toString());
        assertThat(lease.getRunnerId()).isEqualTo("old-runner");
        assertThat(lease.getClaimToken()).isEqualTo(OLD_TOKEN);
        assertThat(lease.getLeaseGeneration()).isEqualTo(1);
        verify(leaseRepository, never()).saveAndFlush(lease);
    }

    private void arrange(ExecutionLease lease) {
        when(reclaimRepository.lockNextExpiredClaimedExecutionId())
                .thenReturn(Optional.of(EXECUTION_ID));
        when(leaseRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(lease));
        lenient().when(reclaimRepository.currentDatabaseTime()).thenReturn(DB_TIME);
    }

    private ExecutionReclaimException assertFailure(ReclaimFailure failure) {
        ExecutionReclaimException exception = catchThrowableOfType(
                ExecutionReclaimException.class,
                () -> service.reclaimNext(command("new-runner")));
        assertThat(exception.getFailure()).isEqualTo(failure);
        return exception;
    }

    private void assertInvalid(ReclaimExecutionLeaseCommand command) {
        assertThatThrownBy(() -> service.reclaimNext(command))
                .isInstanceOf(InvalidRequestException.class);
    }

    private ReclaimExecutionLeaseCommand command(String runner) {
        return new ReclaimExecutionLeaseCommand(runner, Duration.ofMinutes(2));
    }

    private ExecutionLease lease() {
        Execution execution = new Execution();
        execution.setId(EXECUTION_ID);
        execution.claim();
        execution.setVersion(7);
        execution.setEnvironmentSnapshot(Map.of("region", "eu"));
        execution.setSuiteSnapshot(Map.of("engine", "PLAYWRIGHT"));
        execution.setRequestSnapshot(Map.of("selectionMode", "SUITE"));
        Project project = new Project();
        project.setId(UUID.randomUUID());
        execution.setProject(project);
        Environment environment = mock(Environment.class);
        lenient().when(environment.getId()).thenReturn(UUID.randomUUID());
        execution.setEnvironment(environment);
        AutomationSuite suite = new AutomationSuite();
        suite.setId(UUID.randomUUID());
        execution.setAutomationSuite(suite);

        ExecutionLease lease = new ExecutionLease();
        lease.setExecutionId(EXECUTION_ID);
        lease.setExecution(execution);
        lease.setRunnerId("old-runner");
        lease.setClaimToken(OLD_TOKEN);
        lease.setLeaseGeneration(1L);
        lease.setVersion(3);
        lease.setClaimedAt(DB_TIME.minusMinutes(5));
        lease.setLastHeartbeatAt(DB_TIME.minusMinutes(3));
        lease.setLeaseExpiresAt(DB_TIME);
        return lease;
    }
}
