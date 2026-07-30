package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionOwnershipValidatorTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID CLAIM_TOKEN = UUID.randomUUID();
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-07-29T10:00:00Z");
    private final ExecutionOwnershipValidator validator = new ExecutionOwnershipValidator();

    @Test
    void acceptsTheCompleteCurrentOwnershipFence() {
        Execution execution = execution();
        ExecutionLease lease = lease(execution);

        validator.validate(request(), lease, execution, NOW);
    }

    @Test
    void rejectsRunnerTokenGenerationAndVersionMismatches() {
        Execution execution = execution();
        ExecutionLease lease = lease(execution);

        assertOwnershipFailure(new RunnerExecutionRequest(
                EXECUTION_ID, "other", CLAIM_TOKEN, 3, 4, 5), lease, execution);
        assertOwnershipFailure(new RunnerExecutionRequest(
                EXECUTION_ID, "runner", UUID.randomUUID(), 3, 4, 5), lease, execution);
        assertOwnershipFailure(new RunnerExecutionRequest(
                EXECUTION_ID, "runner", CLAIM_TOKEN, 2, 4, 5), lease, execution);
        assertOwnershipFailure(new RunnerExecutionRequest(
                EXECUTION_ID, "runner", CLAIM_TOKEN, 3, 3, 5), lease, execution);
        assertOwnershipFailure(new RunnerExecutionRequest(
                EXECUTION_ID, "runner", CLAIM_TOKEN, 3, 4, 4), lease, execution);
    }

    @Test
    void appliesTheStrictLeaseExpiryBoundary() {
        Execution execution = execution();
        ExecutionLease lease = lease(execution);
        lease.setLeaseExpiresAt(NOW);

        assertOwnershipFailure(request(), lease, execution);
    }

    private void assertOwnershipFailure(
            RunnerExecutionRequest request, ExecutionLease lease, Execution execution) {
        assertThatThrownBy(() -> validator.validate(request, lease, execution, NOW))
                .isInstanceOf(ExecutionOwnershipException.class)
                .hasMessageNotContaining(CLAIM_TOKEN.toString());
    }

    private RunnerExecutionRequest request() {
        return new RunnerExecutionRequest(
                EXECUTION_ID, "runner", CLAIM_TOKEN, 3, 4, 5);
    }

    private Execution execution() {
        Execution execution = new Execution();
        execution.setId(EXECUTION_ID);
        execution.setVersion(5);
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
}
