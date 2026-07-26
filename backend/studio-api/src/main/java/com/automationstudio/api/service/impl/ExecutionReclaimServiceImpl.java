package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionReclaimRepository;
import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.ExecutionReclaimException;
import com.automationstudio.api.service.ExecutionReclaimService;
import com.automationstudio.api.service.ReclaimFailure;
import com.automationstudio.api.service.command.ReclaimExecutionLeaseCommand;
import com.automationstudio.api.service.result.ReclaimedExecutionLease;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionReclaimServiceImpl implements ExecutionReclaimService {

    static final Duration MAX_LEASE_DURATION = Duration.ofHours(24);
    private static final int MAX_RUNNER_ID_LENGTH = 150;

    private final ExecutionReclaimRepository reclaimRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final ClaimTokenGenerator tokenGenerator;

    public ExecutionReclaimServiceImpl(
            ExecutionReclaimRepository reclaimRepository,
            ExecutionLeaseRepository leaseRepository,
            ClaimTokenGenerator tokenGenerator) {
        this.reclaimRepository = reclaimRepository;
        this.leaseRepository = leaseRepository;
        this.tokenGenerator = tokenGenerator;
    }

    @Override
    @Transactional
    public Optional<ReclaimedExecutionLease> reclaimNext(
            ReclaimExecutionLeaseCommand command) {
        ValidatedReclaim reclaim = validate(command);
        Optional<UUID> candidateId = reclaimRepository.lockNextExpiredClaimedExecutionId();
        if (candidateId.isEmpty()) {
            return Optional.empty();
        }

        ExecutionLease lease = leaseRepository.findById(candidateId.orElseThrow())
                .orElseThrow(() -> failure(
                        ReclaimFailure.LEASE_NOT_FOUND,
                        "Locked execution lease was not found"));
        Execution execution = lease.getExecution();
        if (execution.getStatus() != ExecutionStatus.CLAIMED) {
            throw failure(
                    ReclaimFailure.EXECUTION_STATE_INELIGIBLE,
                    "Execution state is not eligible for lease reclaim");
        }

        OffsetDateTime databaseTime = reclaimRepository.currentDatabaseTime();
        if (lease.getLeaseExpiresAt().isAfter(databaseTime)) {
            throw failure(
                    ReclaimFailure.LEASE_STILL_ACTIVE,
                    "Execution lease is still active");
        }
        if (Long.valueOf(Long.MAX_VALUE).equals(lease.getLeaseGeneration())) {
            throw failure(
                    ReclaimFailure.GENERATION_OVERFLOW,
                    "Execution lease generation cannot be incremented");
        }

        UUID newToken;
        try {
            newToken = tokenGenerator.nextToken();
            if (newToken == null) {
                throw new IllegalStateException("Claim token generator returned no token");
            }
        } catch (RuntimeException exception) {
            throw new ExecutionReclaimException(
                    ReclaimFailure.TOKEN_GENERATION_FAILED,
                    "A new claim token could not be generated",
                    exception);
        }

        lease.reclaim(
                reclaim.runnerId(),
                newToken,
                databaseTime,
                databaseTime.plus(reclaim.leaseDuration()));
        try {
            ExecutionLease reclaimed = leaseRepository.saveAndFlush(lease);
            return Optional.of(toResult(execution, reclaimed));
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ExecutionReclaimException(
                    ReclaimFailure.CONCURRENT_RECLAIM_CONFLICT,
                    "Execution lease was concurrently reclaimed",
                    exception);
        } catch (DataAccessException exception) {
            throw new ExecutionReclaimException(
                    ReclaimFailure.PERSISTENCE_FAILED,
                    "Execution lease reclaim could not be persisted",
                    exception);
        }
    }

    private ValidatedReclaim validate(ReclaimExecutionLeaseCommand command) {
        if (command == null) {
            throw new InvalidRequestException("Reclaim command must not be null");
        }
        if (command.newRunnerId() == null || command.newRunnerId().isBlank()) {
            throw new InvalidRequestException("New runner ID must not be blank");
        }
        String runnerId = command.newRunnerId().trim();
        if (runnerId.length() > MAX_RUNNER_ID_LENGTH) {
            throw new InvalidRequestException(
                    "New runner ID must not exceed " + MAX_RUNNER_ID_LENGTH + " characters");
        }
        Duration duration = command.leaseDuration();
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new InvalidRequestException("Lease duration must be positive");
        }
        if (duration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new InvalidRequestException(
                    "Lease duration must not exceed " + MAX_LEASE_DURATION);
        }
        return new ValidatedReclaim(runnerId, duration);
    }

    private ReclaimedExecutionLease toResult(Execution execution, ExecutionLease lease) {
        return new ReclaimedExecutionLease(
                execution.getId(),
                execution.getProject().getId(),
                execution.getEnvironment().getId(),
                execution.getAutomationSuite().getId(),
                execution.getSelectionMode(),
                execution.getStatus(),
                execution.getVersion(),
                lease.getRunnerId(),
                lease.getClaimToken(),
                lease.getLeaseGeneration(),
                lease.getVersion(),
                lease.getClaimedAt(),
                lease.getLastHeartbeatAt(),
                lease.getLeaseExpiresAt(),
                execution.getEnvironmentSnapshot(),
                execution.getSuiteSnapshot(),
                execution.getRequestSnapshot());
    }

    private ExecutionReclaimException failure(ReclaimFailure failure, String message) {
        return new ExecutionReclaimException(failure, message);
    }

    private record ValidatedReclaim(String runnerId, Duration leaseDuration) {
    }
}
