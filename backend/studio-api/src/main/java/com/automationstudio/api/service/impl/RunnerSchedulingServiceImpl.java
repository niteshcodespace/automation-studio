package com.automationstudio.api.service.impl;

import com.automationstudio.api.config.RunnerHealthProperties;
import com.automationstudio.api.domain.CompatibilityResult;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.RunnerCapabilities;
import com.automationstudio.api.domain.RunnerCompatibilityEvaluator;
import com.automationstudio.api.domain.RunnerEligibilityEvaluator;
import com.automationstudio.api.domain.RunnerEligibilityFailure;
import com.automationstudio.api.domain.RunnerSchedulingEligibility;
import com.automationstudio.api.domain.RunnerSchedulingState;
import com.automationstudio.api.domain.SchedulingCandidate;
import com.automationstudio.api.domain.SchedulingOutcome;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.ExecutionLease;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.SchedulingOperationException;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.repository.SchedulingCandidateRepository;
import com.automationstudio.api.service.ClaimTokenGenerator;
import com.automationstudio.api.service.RunnerSchedulingService;
import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import com.automationstudio.api.service.result.SchedulingResult;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerSchedulingServiceImpl implements RunnerSchedulingService {

    static final Duration MAX_LEASE_DURATION = Duration.ofHours(24);
    private static final long INITIAL_LEASE_GENERATION = 1L;
    private static final Pattern RUNNER_KEY_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{0,149}$");
    private static final Set<ExecutionStatus> CAPACITY_STATUSES = Set.of(
            ExecutionStatus.CLAIMED,
            ExecutionStatus.RUNNING,
            ExecutionStatus.CANCEL_REQUESTED);

    private final RunnerRepository runnerRepository;
    private final RunnerRuntimeRepository runtimeRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final SchedulingCandidateRepository candidateRepository;
    private final ExecutionRepository executionRepository;
    private final ClaimTokenGenerator tokenGenerator;
    private final RunnerEligibilityEvaluator eligibilityEvaluator;
    private final RunnerCompatibilityEvaluator compatibilityEvaluator =
            new RunnerCompatibilityEvaluator();

    public RunnerSchedulingServiceImpl(
            RunnerRepository runnerRepository,
            RunnerRuntimeRepository runtimeRepository,
            ExecutionLeaseRepository leaseRepository,
            SchedulingCandidateRepository candidateRepository,
            ExecutionRepository executionRepository,
            ClaimTokenGenerator tokenGenerator,
            RunnerHealthProperties healthProperties) {
        this.runnerRepository = runnerRepository;
        this.runtimeRepository = runtimeRepository;
        this.leaseRepository = leaseRepository;
        this.candidateRepository = candidateRepository;
        this.executionRepository = executionRepository;
        this.tokenGenerator = tokenGenerator;
        this.eligibilityEvaluator = new RunnerEligibilityEvaluator(healthProperties);
    }

    @Override
    @Transactional
    public SchedulingResult scheduleNext(ScheduleExecutionCommand command) {
        ValidatedCommand validated = validate(command);

        // Authoritative lock order: runner -> runner_runtime -> execution -> execution_lease.
        Runner runner = runnerRepository.findByRunnerKeyForUpdate(validated.runnerKey())
                .orElse(null);
        if (runner == null) {
            OffsetDateTime evaluatedAt = databaseTime();
            return result(
                    SchedulingOutcome.RUNNER_NOT_FOUND,
                    eligibilityEvaluator.runnerNotFound(validated.runnerKey(), evaluatedAt));
        }
        RunnerRuntime runtime =
                runtimeRepository.findByRunnerIdForUpdate(runner.getId()).orElse(null);
        OffsetDateTime evaluatedAt = databaseTime();
        long activeLeaseCount = leaseRepository.countCapacityConsumingLeases(
                runner.getRunnerKey(), evaluatedAt, CAPACITY_STATUSES);
        RunnerSchedulingEligibility eligibility = eligibilityEvaluator.evaluate(
                schedulingState(runner, runtime, evaluatedAt, activeLeaseCount));
        if (!eligibility.eligible()) {
            SchedulingOutcome outcome = eligibility.failures().equals(
                    Set.of(RunnerEligibilityFailure.CAPACITY_EXHAUSTED))
                    ? SchedulingOutcome.CAPACITY_EXHAUSTED
                    : SchedulingOutcome.RUNNER_INELIGIBLE;
            return result(outcome, eligibility);
        }

        RunnerCapabilities runnerCapabilities =
                new RunnerCapabilities(runner.getCapabilities(), stringLabels(runner));
        SchedulingCandidate candidate =
                candidateRepository.lockNextCompatible(runnerCapabilities).orElse(null);
        if (candidate == null) {
            return result(SchedulingOutcome.NO_COMPATIBLE_EXECUTION, eligibility);
        }

        Execution execution = executionRepository.findById(candidate.executionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Locked scheduling candidate disappeared"));
        if (execution.getStatus() != ExecutionStatus.PENDING
                || leaseRepository.existsById(execution.getId())
                || compatibilityEvaluator.evaluate(
                                candidate.requirements(), runnerCapabilities)
                        != CompatibilityResult.COMPATIBLE) {
            return result(SchedulingOutcome.NO_COMPATIBLE_EXECUTION, eligibility);
        }

        try {
            execution.claim();
            executionRepository.saveAndFlush(execution);
            ExecutionLease lease = newLease(
                    execution,
                    runner.getRunnerKey(),
                    evaluatedAt,
                    validated.leaseDuration());
            leaseRepository.saveAndFlush(lease);
            return new SchedulingResult(
                    SchedulingOutcome.SCHEDULED,
                    eligibility,
                    claimedExecution(execution, lease));
        } catch (RuntimeException exception) {
            throw new SchedulingOperationException(
                    "Atomic execution scheduling failed", exception);
        }
    }

    private OffsetDateTime databaseTime() {
        return runnerRepository.currentDatabaseTime().atOffset(ZoneOffset.UTC);
    }

    private RunnerSchedulingState schedulingState(
            Runner runner,
            RunnerRuntime runtime,
            OffsetDateTime evaluatedAt,
            long activeLeaseCount) {
        return new RunnerSchedulingState(
                runner.getId(),
                runner.getRunnerKey(),
                runner.getStatus(),
                runner.getMaxConcurrency(),
                runner.getCapabilities(),
                runner.getLabels(),
                runtime == null ? null : runtime.getRunnerId(),
                runtime == null ? null : runtime.getLastSeenAt(),
                evaluatedAt,
                activeLeaseCount);
    }

    private java.util.Map<String, String> stringLabels(Runner runner) {
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        runner.getLabels().forEach((key, value) -> labels.put(key, (String) value));
        return labels;
    }

    private ExecutionLease newLease(
            Execution execution,
            String runnerKey,
            OffsetDateTime claimedAt,
            Duration leaseDuration) {
        ExecutionLease lease = new ExecutionLease();
        lease.setExecution(execution);
        lease.setRunnerId(runnerKey);
        lease.setClaimToken(tokenGenerator.nextToken());
        lease.setLeaseGeneration(INITIAL_LEASE_GENERATION);
        lease.setClaimedAt(claimedAt);
        lease.setLastHeartbeatAt(claimedAt);
        lease.setLeaseExpiresAt(claimedAt.plus(leaseDuration));
        return lease;
    }

    private ClaimedExecution claimedExecution(Execution execution, ExecutionLease lease) {
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

    private SchedulingResult result(
            SchedulingOutcome outcome, RunnerSchedulingEligibility eligibility) {
        return new SchedulingResult(outcome, eligibility, null);
    }

    private ValidatedCommand validate(ScheduleExecutionCommand command) {
        if (command == null || command.runnerKey() == null
                || command.runnerKey().isBlank()) {
            throw new InvalidRequestException("Runner key must not be blank");
        }
        String runnerKey = command.runnerKey().trim().toLowerCase(Locale.ROOT);
        if (!RUNNER_KEY_PATTERN.matcher(runnerKey).matches()) {
            throw new InvalidRequestException("Runner key has an invalid format");
        }
        Duration leaseDuration = command.leaseDuration();
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new InvalidRequestException(
                    "Lease duration must be positive and at most "
                            + MAX_LEASE_DURATION);
        }
        return new ValidatedCommand(runnerKey, leaseDuration);
    }

    private record ValidatedCommand(String runnerKey, Duration leaseDuration) {
    }
}
