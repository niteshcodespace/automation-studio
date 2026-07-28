package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.ExecutionHeartbeatRepository;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ExecutionHeartbeatException;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.HeartbeatFailure;
import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import com.automationstudio.api.service.result.RenewedExecutionLease;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionHeartbeatServiceImpl implements ExecutionHeartbeatService {

    static final Duration MAX_LEASE_DURATION = Duration.ofHours(24);
    private static final int MAX_RUNNER_ID_LENGTH = 150;

    private final ExecutionLeaseRepository leaseRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionHeartbeatRepository heartbeatRepository;

    public ExecutionHeartbeatServiceImpl(
            ExecutionLeaseRepository leaseRepository,
            ExecutionRepository executionRepository,
            ExecutionHeartbeatRepository heartbeatRepository) {
        this.leaseRepository = leaseRepository;
        this.executionRepository = executionRepository;
        this.heartbeatRepository = heartbeatRepository;
    }

    @Override
    @Transactional
    public RenewedExecutionLease renew(RenewExecutionLeaseCommand command) {
        ValidatedHeartbeat heartbeat = validate(command);
        ExecutionLease lease = leaseRepository
                .findByExecutionIdForUpdate(heartbeat.executionId())
                .orElseThrow(() -> failure(
                        HeartbeatFailure.LEASE_NOT_FOUND, "Execution lease was not found"));
        executionRepository.findByIdForUpdate(heartbeat.executionId())
                .orElseThrow(() -> failure(
                        HeartbeatFailure.LEASE_NOT_FOUND, "Execution lease was not found"));

        if (!lease.getRunnerId().equals(heartbeat.runnerId())
                || !lease.getClaimToken().equals(heartbeat.claimToken())) {
            throw failure(
                    HeartbeatFailure.OWNERSHIP_MISMATCH,
                    "Execution lease ownership credentials do not match");
        }
        if (lease.getLeaseGeneration() != heartbeat.leaseGeneration()) {
            throw failure(
                    HeartbeatFailure.STALE_GENERATION,
                    "Execution lease generation does not match");
        }
        if (lease.getVersion() != heartbeat.expectedLeaseVersion()) {
            throw failure(
                    HeartbeatFailure.OPTIMISTIC_LOCK_CONFLICT,
                    "Execution lease version does not match");
        }
        if (lease.getExecution().getStatus() != ExecutionStatus.CLAIMED
                && lease.getExecution().getStatus() != ExecutionStatus.RUNNING) {
            throw failure(
                    HeartbeatFailure.EXECUTION_STATE_INELIGIBLE,
                    "Execution state is not eligible for heartbeat renewal");
        }

        OffsetDateTime databaseTime = heartbeatRepository.currentDatabaseTime();
        if (!lease.getLeaseExpiresAt().isAfter(databaseTime)) {
            throw failure(
                    HeartbeatFailure.EXPIRED_LEASE,
                    "Execution lease has expired");
        }

        lease.setLastHeartbeatAt(databaseTime);
        lease.setLeaseExpiresAt(databaseTime.plus(heartbeat.leaseDuration()));
        ExecutionLease renewed = leaseRepository.saveAndFlush(lease);

        return new RenewedExecutionLease(
                renewed.getExecutionId(),
                renewed.getRunnerId(),
                renewed.getLeaseGeneration(),
                renewed.getVersion(),
                renewed.getLastHeartbeatAt(),
                renewed.getLeaseExpiresAt());
    }

    private ValidatedHeartbeat validate(RenewExecutionLeaseCommand command) {
        if (command == null) {
            throw new InvalidRequestException("Heartbeat command must not be null");
        }
        if (command.executionId() == null) {
            throw new InvalidRequestException("Execution ID must not be null");
        }
        if (command.runnerId() == null || command.runnerId().isBlank()) {
            throw new InvalidRequestException("Runner ID must not be blank");
        }
        String runnerId = command.runnerId().trim();
        if (runnerId.length() > MAX_RUNNER_ID_LENGTH) {
            throw new InvalidRequestException(
                    "Runner ID must not exceed " + MAX_RUNNER_ID_LENGTH + " characters");
        }
        if (command.claimToken() == null) {
            throw new InvalidRequestException("Claim token must not be null");
        }
        if (command.leaseGeneration() <= 0) {
            throw new InvalidRequestException("Lease generation must be positive");
        }
        if (command.expectedLeaseVersion() < 0) {
            throw new InvalidRequestException("Expected lease version must not be negative");
        }
        Duration duration = command.leaseDuration();
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new InvalidRequestException("Lease duration must be positive");
        }
        if (duration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new InvalidRequestException(
                    "Lease duration must not exceed " + MAX_LEASE_DURATION);
        }
        return new ValidatedHeartbeat(
                command.executionId(),
                runnerId,
                command.claimToken(),
                command.leaseGeneration(),
                command.expectedLeaseVersion(),
                duration);
    }

    private ExecutionHeartbeatException failure(HeartbeatFailure failure, String message) {
        return new ExecutionHeartbeatException(failure, message);
    }

    private record ValidatedHeartbeat(
            java.util.UUID executionId,
            String runnerId,
            java.util.UUID claimToken,
            long leaseGeneration,
            long expectedLeaseVersion,
            Duration leaseDuration) {
    }
}
