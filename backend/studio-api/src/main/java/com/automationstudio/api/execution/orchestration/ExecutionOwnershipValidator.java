package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public class ExecutionOwnershipValidator {

    public void validate(
            RunnerExecutionRequest request,
            ExecutionLease lease,
            Execution execution,
            OffsetDateTime databaseTime) {
        if (request == null || lease == null || execution == null || databaseTime == null) {
            throw new ExecutionOwnershipException(
                    "Execution ownership validation requires complete state");
        }
        if (!request.executionId().equals(lease.getExecutionId())
                || !request.executionId().equals(execution.getId())
                || lease.getExecution() == null
                || !request.executionId().equals(lease.getExecution().getId())) {
            throw new ExecutionOwnershipException("Execution lease ownership does not match");
        }
        if (!request.runnerId().equals(lease.getRunnerId())
                || !request.claimToken().equals(lease.getClaimToken())) {
            throw new ExecutionOwnershipException(
                    "Execution lease ownership credentials do not match");
        }
        if (lease.getLeaseGeneration() == null
                || request.leaseGeneration() != lease.getLeaseGeneration()) {
            throw new ExecutionOwnershipException("Execution lease generation does not match");
        }
        if (request.expectedLeaseVersion() != lease.getVersion()) {
            throw new ExecutionOwnershipException("Execution lease version does not match");
        }
        if (request.expectedExecutionVersion() != execution.getVersion()) {
            throw new ExecutionOwnershipException("Execution version does not match");
        }
        if (lease.getLeaseExpiresAt() == null
                || !lease.getLeaseExpiresAt().isAfter(databaseTime)) {
            throw new ExecutionOwnershipException("Execution lease has expired");
        }
    }
}
