package com.automationstudio.api.service.impl;

import com.automationstudio.api.config.RunnerHealthProperties;
import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.command.RecordRunnerHeartbeatCommand;
import com.automationstudio.api.service.result.RunnerHealthResult;
import com.automationstudio.api.service.result.RunnerHeartbeatResult;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerHeartbeatServiceImpl implements RunnerHeartbeatService {

    private static final int MAX_RUNNER_KEY_LENGTH = 150;
    private static final Pattern RUNNER_KEY_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{0,149}$");

    private final RunnerRepository runnerRepository;
    private final RunnerRuntimeRepository runtimeRepository;
    private final RunnerHealthProperties healthProperties;

    public RunnerHeartbeatServiceImpl(
            RunnerRepository runnerRepository,
            RunnerRuntimeRepository runtimeRepository,
            RunnerHealthProperties healthProperties) {
        this.runnerRepository = runnerRepository;
        this.runtimeRepository = runtimeRepository;
        this.healthProperties = healthProperties;
    }

    @Override
    @Transactional
    public RunnerHeartbeatResult recordHeartbeat(RecordRunnerHeartbeatCommand command) {
        String runnerKey = validate(command);
        Runner runner = runnerRepository.findByRunnerKeyForUpdate(runnerKey)
                .orElseThrow(() -> runnerNotFound(runnerKey));
        if (runner.getStatus() == RunnerStatus.DEREGISTERED) {
            throw new ResourceConflictException(
                    "Deregistered runner cannot record a heartbeat");
        }

        RunnerRuntime runtime = runtimeRepository.findByRunnerIdForUpdate(runner.getId())
                .orElseThrow(() -> missingRuntime(runner.getId()));
        OffsetDateTime heartbeatTime = currentDatabaseTime();
        runtime.recordHeartbeat(heartbeatTime);
        try {
            RunnerRuntime saved = runtimeRepository.saveAndFlush(runtime);
            return new RunnerHeartbeatResult(
                    runner.getId(),
                    runner.getRunnerKey(),
                    saved.getLastSeenAt(),
                    saved.getHeartbeatCount(),
                    saved.getVersion());
        } catch (DataAccessException exception) {
            throw new ResourceConflictException(
                    "Runner heartbeat conflicts with persisted state");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerHealthResult evaluateHealth(UUID runnerId) {
        if (runnerId == null) {
            throw new InvalidRequestException("Runner ID must not be null");
        }
        return evaluate(runnerById(runnerId), currentDatabaseTime());
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerHealthResult evaluateHealth(String runnerKey) {
        String canonicalKey = validateRunnerKey(runnerKey);
        Runner runner = runnerRepository.findByRunnerKey(canonicalKey)
                .orElseThrow(() -> runnerNotFound(canonicalKey));
        return evaluate(runner, currentDatabaseTime());
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerHealthResult evaluateHealth(UUID runnerId, OffsetDateTime evaluatedAt) {
        if (runnerId == null) {
            throw new InvalidRequestException("Runner ID must not be null");
        }
        if (evaluatedAt == null) {
            throw new InvalidRequestException("Health evaluation time must not be null");
        }
        return evaluate(runnerById(runnerId), evaluatedAt);
    }

    private RunnerHealthResult evaluate(Runner runner, OffsetDateTime evaluatedAt) {
        RunnerRuntime runtime = runtimeRepository.findByRunnerId(runner.getId())
                .orElseThrow(() -> missingRuntime(runner.getId()));
        Duration age = runtime.getLastSeenAt().isAfter(evaluatedAt)
                ? Duration.ZERO
                : Duration.between(runtime.getLastSeenAt(), evaluatedAt);
        RunnerHealth health;
        if (age.compareTo(healthProperties.onlineThreshold()) <= 0) {
            health = RunnerHealth.ONLINE;
        } else if (age.compareTo(healthProperties.offlineThreshold()) <= 0) {
            health = RunnerHealth.STALE;
        } else {
            health = RunnerHealth.OFFLINE;
        }
        return new RunnerHealthResult(
                runner.getId(),
                runner.getRunnerKey(),
                runner.getStatus(),
                health,
                runtime.getLastSeenAt(),
                evaluatedAt);
    }

    private Runner runnerById(UUID runnerId) {
        return runnerRepository.findById(runnerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Runner not found: " + runnerId));
    }

    private String validate(RecordRunnerHeartbeatCommand command) {
        if (command == null) {
            throw new InvalidRequestException("Runner heartbeat command must not be null");
        }
        return validateRunnerKey(command.runnerKey());
    }

    private String validateRunnerKey(String runnerKey) {
        if (runnerKey == null || runnerKey.isBlank()) {
            throw new InvalidRequestException("Runner key must not be blank");
        }
        String canonicalKey = runnerKey.trim().toLowerCase(Locale.ROOT);
        if (canonicalKey.length() > MAX_RUNNER_KEY_LENGTH
                || !RUNNER_KEY_PATTERN.matcher(canonicalKey).matches()) {
            throw new InvalidRequestException("Runner key has an invalid format");
        }
        return canonicalKey;
    }

    private OffsetDateTime currentDatabaseTime() {
        return runnerRepository.currentDatabaseTime().atOffset(ZoneOffset.UTC);
    }

    private ResourceNotFoundException runnerNotFound(String runnerKey) {
        return new ResourceNotFoundException("Runner not found: " + runnerKey);
    }

    private IllegalStateException missingRuntime(UUID runnerId) {
        return new IllegalStateException(
                "Runner runtime is missing for registered runner: " + runnerId);
    }
}
