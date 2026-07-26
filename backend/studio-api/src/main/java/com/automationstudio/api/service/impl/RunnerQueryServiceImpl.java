package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.RunnerQueryService;
import com.automationstudio.api.service.result.RunnerDetailsResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerQueryServiceImpl implements RunnerQueryService {

    private static final Set<String> SUPPORTED_SORTS = Set.of(
            "name", "runnerKey", "status", "registeredAt", "lastRegisteredAt", "id");

    private final RunnerRepository runnerRepository;
    private final RunnerRuntimeRepository runtimeRepository;
    private final RunnerHeartbeatService heartbeatService;

    public RunnerQueryServiceImpl(
            RunnerRepository runnerRepository,
            RunnerRuntimeRepository runtimeRepository,
            RunnerHeartbeatService heartbeatService) {
        this.runnerRepository = runnerRepository;
        this.runtimeRepository = runtimeRepository;
        this.heartbeatService = heartbeatService;
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerDetailsResult get(UUID runnerId) {
        if (runnerId == null) {
            throw new InvalidRequestException("Runner ID must not be null");
        }
        OffsetDateTime evaluatedAt = databaseTime();
        Runner runner = findRunner(runnerId);
        return details(runner, evaluatedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RunnerDetailsResult> list(RunnerStatus status, Pageable pageable) {
        validatePageable(pageable);
        OffsetDateTime evaluatedAt = databaseTime();
        Page<Runner> runners = status == null
                ? runnerRepository.findAll(pageable)
                : runnerRepository.findByStatus(status, pageable);
        return runners.map(runner -> details(runner, evaluatedAt));
    }

    private RunnerDetailsResult details(Runner runner, OffsetDateTime evaluatedAt) {
        RunnerRuntime runtime = runtimeRepository.findByRunnerId(runner.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Runner runtime is missing for registered runner"));
        RunnerHealth health = heartbeatService
                .evaluateHealth(runner.getId(), evaluatedAt)
                .health();
        return new RunnerDetailsResult(
                runner.getId(),
                runner.getRunnerKey(),
                runner.getName(),
                runner.getDescription(),
                runner.getAgentVersion(),
                runner.getHostname(),
                runner.getOperatingSystem(),
                runner.getArchitecture(),
                runner.getMaxConcurrency(),
                runner.getCapabilities(),
                runner.getLabels(),
                runner.getStatus(),
                health,
                runner.getStatus() == RunnerStatus.ACTIVE
                        && health == RunnerHealth.ONLINE
                        && runner.getMaxConcurrency() > 0,
                runner.getRegisteredAt(),
                runner.getLastRegisteredAt(),
                runtime.getLastSeenAt(),
                runner.getVersion(),
                runtime.getVersion(),
                runtime.getHeartbeatCount(),
                runner.getCreatedAt(),
                runner.getUpdatedAt());
    }

    private Runner findRunner(UUID runnerId) {
        return runnerRepository.findById(runnerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Runner not found: " + runnerId));
    }

    private OffsetDateTime databaseTime() {
        return runnerRepository.currentDatabaseTime().atOffset(ZoneOffset.UTC);
    }

    private void validatePageable(Pageable pageable) {
        if (pageable == null || pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new InvalidRequestException("Runner page size must be between 1 and 100");
        }
        pageable.getSort().forEach(order -> {
            if (!SUPPORTED_SORTS.contains(order.getProperty())) {
                throw new InvalidRequestException(
                        "Unsupported runner sort field: " + order.getProperty());
            }
        });
    }
}
