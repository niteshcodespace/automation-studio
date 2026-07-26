package com.automationstudio.api.service.impl;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.ExecutionClaimRepository;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.ExecutionClaimService;
import com.automationstudio.api.service.command.ClaimExecutionCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionClaimServiceImpl implements ExecutionClaimService {

    static final Duration MAX_LEASE_DURATION = Duration.ofHours(24);
    private static final int MAX_RUNNER_ID_LENGTH = 150;
    private static final long INITIAL_LEASE_GENERATION = 1L;

    private final ExecutionClaimRepository claimRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final ClaimTokenGenerator tokenGenerator;

    public ExecutionClaimServiceImpl(
            ExecutionClaimRepository claimRepository,
            ExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            ClaimTokenGenerator tokenGenerator) {
        this.claimRepository = claimRepository;
        this.executionRepository = executionRepository;
        this.leaseRepository = leaseRepository;
        this.tokenGenerator = tokenGenerator;
    }

    @Override
    @Transactional
    public Optional<ClaimedExecution> claimNext(ClaimExecutionCommand command) {
        ValidatedClaim claim = validate(command);
        Optional<UUID> candidateId = claimRepository.lockNextPendingExecutionId();
        if (candidateId.isEmpty()) {
            return Optional.empty();
        }

        Execution execution = executionRepository.findById(candidateId.orElseThrow())
                .orElseThrow(() -> new IllegalStateException(
                        "Locked execution disappeared before claim"));
        execution.claim();
        executionRepository.saveAndFlush(execution);

        OffsetDateTime claimedAt = claimRepository.currentDatabaseTime();
        UUID claimToken = tokenGenerator.nextToken();
        ExecutionLease lease = new ExecutionLease();
        lease.setExecution(execution);
        lease.setRunnerId(claim.runnerId());
        lease.setClaimToken(claimToken);
        lease.setLeaseGeneration(INITIAL_LEASE_GENERATION);
        lease.setClaimedAt(claimedAt);
        lease.setLastHeartbeatAt(claimedAt);
        lease.setLeaseExpiresAt(claimedAt.plus(claim.leaseDuration()));
        leaseRepository.saveAndFlush(lease);

        return Optional.of(toResult(execution, lease));
    }

    private ValidatedClaim validate(ClaimExecutionCommand command) {
        if (command == null) {
            throw new InvalidRequestException("Claim command must not be null");
        }
        if (command.runnerId() == null || command.runnerId().isBlank()) {
            throw new InvalidRequestException("Runner ID must not be blank");
        }
        String runnerId = command.runnerId().trim();
        if (runnerId.length() > MAX_RUNNER_ID_LENGTH) {
            throw new InvalidRequestException(
                    "Runner ID must not exceed " + MAX_RUNNER_ID_LENGTH + " characters");
        }
        Duration duration = command.leaseDuration();
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new InvalidRequestException("Lease duration must be positive");
        }
        if (duration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new InvalidRequestException(
                    "Lease duration must not exceed " + MAX_LEASE_DURATION);
        }
        return new ValidatedClaim(runnerId, duration);
    }

    private ClaimedExecution toResult(Execution execution, ExecutionLease lease) {
        return new ClaimedExecution(
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
                lease.getLeaseExpiresAt(),
                execution.getEnvironmentSnapshot(),
                execution.getSuiteSnapshot(),
                execution.getRequestSnapshot());
    }

    private record ValidatedClaim(String runnerId, Duration leaseDuration) {
    }
}
