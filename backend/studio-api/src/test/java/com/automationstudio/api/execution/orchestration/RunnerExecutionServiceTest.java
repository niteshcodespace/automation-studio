package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.ExecutionEngineNotFoundException;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.ExecutionEngineSupport;
import com.automationstudio.api.repository.ExecutionHeartbeatRepository;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ExecutionContextService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunnerExecutionServiceTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID CLAIM_TOKEN = UUID.randomUUID();
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-07-29T10:00:00Z");

    @Mock private ExecutionLeaseRepository leaseRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private ExecutionHeartbeatRepository databaseTimeRepository;
    @Mock private ExecutionContextService contextService;
    @Mock private ExecutionEngineRegistry engineRegistry;
    @Mock private ExecutionContext context;
    @Mock private ExecutionEngine engine;

    private RunnerExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RunnerExecutionServiceImpl(
                leaseRepository,
                executionRepository,
                databaseTimeRepository,
                contextService,
                engineRegistry,
                new ExecutionOwnershipValidator(),
                new ExecutionStateValidator());
    }

    @Test
    void performsFencedStartOnlyAfterContextAndEngineValidation() {
        Execution execution = execution(ExecutionStatus.CLAIMED);
        ExecutionLease lease = lease(execution);
        stubLocks(execution, lease);
        when(contextService.createContext(EXECUTION_ID)).thenReturn(context);
        ExecutionEngineDescriptor descriptor = descriptor();
        when(engineRegistry.validateCompatibility(context))
                .thenReturn(new ExecutionEngineSupport(engine, descriptor));
        when(executionRepository.saveAndFlush(execution)).thenAnswer(invocation -> {
            execution.setVersion(6);
            return execution;
        });

        ExecutionStartResult result = service.start(request(5));

        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(result.startedAt()).isEqualTo(NOW);
        assertThat(result.executionVersion()).isEqualTo(6);
        assertThat(result.leaseGeneration()).isEqualTo(3);
        assertThat(result.leaseVersion()).isEqualTo(4);
        assertThat(result.context()).isSameAs(context);
        assertThat(result.engineDescriptor()).isEqualTo(descriptor);
    }

    @Test
    void rejectsDuplicateStartBeforeContextOrEngineResolution() {
        Execution execution = execution(ExecutionStatus.RUNNING);
        ExecutionLease lease = lease(execution);
        stubLocks(execution, lease);

        assertThatThrownBy(() -> service.start(request(5)))
                .isInstanceOf(RunnerExecutionException.class);

        verify(contextService, never()).createContext(EXECUTION_ID);
        verify(engineRegistry, never()).validateCompatibility(context);
        verify(executionRepository, never()).saveAndFlush(execution);
    }

    @Test
    void leavesClaimedExecutionUnchangedWhenEngineResolutionFails() {
        Execution execution = execution(ExecutionStatus.CLAIMED);
        ExecutionLease lease = lease(execution);
        stubLocks(execution, lease);
        when(contextService.createContext(EXECUTION_ID)).thenReturn(context);
        when(engineRegistry.validateCompatibility(context))
                .thenThrow(new ExecutionEngineNotFoundException("missing"));

        assertThatThrownBy(() -> service.start(request(5)))
                .isInstanceOf(ExecutionEngineNotFoundException.class);

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CLAIMED);
        verify(executionRepository, never()).saveAndFlush(execution);
    }

    @Test
    void preparesCompletionWithoutChangingRunningExecution() {
        Execution execution = execution(ExecutionStatus.RUNNING);
        ExecutionLease lease = lease(execution);
        stubLocks(execution, lease);

        ExecutionCompletionResult result = service.prepareCompletion(request(5));

        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(result.preparedAt()).isEqualTo(NOW);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        verify(executionRepository, never()).saveAndFlush(execution);
    }

    @Test
    void performsFencedSuccessfulCompletion() {
        Execution execution = execution(ExecutionStatus.RUNNING);
        ExecutionLease lease = lease(execution);
        stubLocks(execution, lease);
        when(executionRepository.saveAndFlush(execution)).thenAnswer(invocation -> {
            execution.setVersion(6);
            return execution;
        });

        ExecutionCompletionResult result =
                service.complete(request(5), ExecutionStatus.PASSED);

        assertThat(result.status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(execution.getFinishedAt()).isEqualTo(NOW);
        assertThat(result.executionVersion()).isEqualTo(6);
    }

    @Test
    void performsFencedInfrastructureErrorCompletion() {
        Execution execution = execution(ExecutionStatus.RUNNING);
        ExecutionLease lease = lease(execution);
        stubLocks(execution, lease);
        when(executionRepository.saveAndFlush(execution)).thenAnswer(invocation -> {
            execution.setVersion(6);
            return execution;
        });

        ExecutionCompletionResult result = service.complete(request(5), ExecutionStatus.ERROR);

        assertThat(result.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(execution.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsOwnershipMismatchBeforeLifecycleWork() {
        Execution execution = execution(ExecutionStatus.CLAIMED);
        ExecutionLease lease = lease(execution);
        stubLocks(execution, lease);

        RunnerExecutionRequest wrongRunner = new RunnerExecutionRequest(
                EXECUTION_ID, "other", CLAIM_TOKEN, 3, 4, 5);
        assertThatThrownBy(() -> service.start(wrongRunner))
                .isInstanceOf(ExecutionOwnershipException.class);

        verify(contextService, never()).createContext(EXECUTION_ID);
    }

    private void stubLocks(Execution execution, ExecutionLease lease) {
        when(leaseRepository.findByExecutionIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(lease));
        when(executionRepository.findByIdForUpdate(EXECUTION_ID))
                .thenReturn(Optional.of(execution));
        when(databaseTimeRepository.currentDatabaseTime()).thenReturn(NOW);
    }

    private RunnerExecutionRequest request(long executionVersion) {
        return new RunnerExecutionRequest(
                EXECUTION_ID, "runner", CLAIM_TOKEN, 3, 4, executionVersion);
    }

    private Execution execution(ExecutionStatus status) {
        Execution execution = new Execution();
        execution.setId(EXECUTION_ID);
        execution.setVersion(5);
        if (status == ExecutionStatus.CLAIMED || status == ExecutionStatus.RUNNING) {
            execution.claim();
        }
        if (status == ExecutionStatus.RUNNING) {
            execution.start(NOW.minusMinutes(1));
        }
        return execution;
    }

    private ExecutionLease lease(Execution execution) {
        ExecutionLease lease = new ExecutionLease();
        lease.setExecutionId(EXECUTION_ID);
        lease.setExecution(execution);
        lease.setRunnerId("runner");
        lease.setClaimToken(CLAIM_TOKEN);
        lease.setLeaseGeneration(3L);
        lease.setVersion(4);
        lease.setLeaseExpiresAt(NOW.plusMinutes(1));
        return lease;
    }

    private ExecutionEngineDescriptor descriptor() {
        return new ExecutionEngineDescriptor(
                "playwright", "1", "Playwright", Set.of(), Set.of());
    }
}
