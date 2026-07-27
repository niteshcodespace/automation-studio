package com.automationstudio.api.service.impl;

import com.automationstudio.api.config.RunnerHealthProperties;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.RunnerEligibilityEvaluator;
import com.automationstudio.api.domain.RunnerSchedulingEligibility;
import com.automationstudio.api.domain.RunnerSchedulingState;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.repository.ExecutionLeaseRepository;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerSchedulingEvaluationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerSchedulingEvaluationServiceImpl
        implements RunnerSchedulingEvaluationService {

    private static final Pattern RUNNER_KEY_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{0,149}$");
    private static final Set<ExecutionStatus> CAPACITY_STATUSES = Set.of(
            ExecutionStatus.CLAIMED,
            ExecutionStatus.RUNNING,
            ExecutionStatus.CANCEL_REQUESTED);

    private final RunnerRepository runnerRepository;
    private final RunnerRuntimeRepository runtimeRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final RunnerEligibilityEvaluator evaluator;

    public RunnerSchedulingEvaluationServiceImpl(
            RunnerRepository runnerRepository,
            RunnerRuntimeRepository runtimeRepository,
            ExecutionLeaseRepository leaseRepository,
            RunnerHealthProperties healthProperties) {
        this.runnerRepository = runnerRepository;
        this.runtimeRepository = runtimeRepository;
        this.leaseRepository = leaseRepository;
        this.evaluator = new RunnerEligibilityEvaluator(healthProperties);
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerSchedulingEligibility evaluate(String runnerKey) {
        String canonicalKey = canonicalRunnerKey(runnerKey);
        OffsetDateTime evaluatedAt =
                runnerRepository.currentDatabaseTime().atOffset(ZoneOffset.UTC);
        Runner runner = runnerRepository.findByRunnerKey(canonicalKey).orElse(null);
        if (runner == null) {
            return evaluator.runnerNotFound(canonicalKey, evaluatedAt);
        }
        RunnerRuntime runtime = runtimeRepository.findByRunnerId(runner.getId()).orElse(null);
        long activeLeaseCount = leaseRepository.countCapacityConsumingLeases(
                runner.getRunnerKey(), evaluatedAt, CAPACITY_STATUSES);
        return evaluator.evaluate(new RunnerSchedulingState(
                runner.getId(),
                runner.getRunnerKey(),
                runner.getStatus(),
                runner.getMaxConcurrency(),
                runner.getCapabilities(),
                runner.getLabels(),
                runtime == null ? null : runtime.getRunnerId(),
                runtime == null ? null : runtime.getLastSeenAt(),
                evaluatedAt,
                activeLeaseCount));
    }

    private String canonicalRunnerKey(String runnerKey) {
        if (runnerKey == null || runnerKey.isBlank()) {
            throw new IllegalArgumentException("Runner key must not be blank");
        }
        String canonicalKey = runnerKey.trim().toLowerCase(Locale.ROOT);
        if (!RUNNER_KEY_PATTERN.matcher(canonicalKey).matches()) {
            throw new IllegalArgumentException("Runner key has an invalid format");
        }
        return canonicalKey;
    }
}
