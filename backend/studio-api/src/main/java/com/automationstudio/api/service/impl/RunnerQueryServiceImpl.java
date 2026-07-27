package com.automationstudio.api.service.impl;

import com.automationstudio.api.config.RunnerHealthProperties;
import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.RunnerDiscoveryRepository;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.RunnerQueryService;
import com.automationstudio.api.service.query.RunnerQueryFilter;
import com.automationstudio.api.service.result.RunnerDetailsResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerQueryServiceImpl implements RunnerQueryService {

    private static final Set<String> SUPPORTED_SORTS = Set.of(
            "name", "runnerKey", "registeredAt", "lastRegisteredAt", "lastSeenAt",
            "heartbeatCount", "status", "health", "id");

    private final RunnerRepository runnerRepository;
    private final RunnerRuntimeRepository runtimeRepository;
    private final RunnerDiscoveryRepository discoveryRepository;
    private final RunnerHeartbeatService heartbeatService;
    private final RunnerHealthProperties healthProperties;

    public RunnerQueryServiceImpl(
            RunnerRepository runnerRepository,
            RunnerRuntimeRepository runtimeRepository,
            RunnerDiscoveryRepository discoveryRepository,
            RunnerHeartbeatService heartbeatService,
            RunnerHealthProperties healthProperties) {
        this.runnerRepository = runnerRepository;
        this.runtimeRepository = runtimeRepository;
        this.discoveryRepository = discoveryRepository;
        this.heartbeatService = heartbeatService;
        this.healthProperties = healthProperties;
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
    public Page<RunnerDetailsResult> list(
            RunnerQueryFilter filter,
            Pageable pageable) {
        return list(filter, pageable, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RunnerDetailsResult> list(
            RunnerQueryFilter filter,
            Pageable pageable,
            String direction) {
        return list(filter, pageable, direction, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RunnerDetailsResult> list(
            RunnerQueryFilter filter,
            Pageable pageable,
            String direction,
            Integer requestedPage,
            Integer requestedSize) {
        RunnerQueryFilter validatedFilter = validateFilter(filter);
        Pageable validatedPageable = validatePageable(
                pageable, direction, requestedPage, requestedSize);
        OffsetDateTime evaluatedAt = databaseTime();
        return discoveryRepository.findRunnerIds(
                        validatedFilter,
                        evaluatedAt,
                        healthProperties.onlineThreshold(),
                        healthProperties.offlineThreshold(),
                        validatedPageable)
                .map(runnerId -> details(findRunner(runnerId), evaluatedAt));
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

    private Pageable validatePageable(
            Pageable pageable,
            String direction,
            Integer requestedPage,
            Integer requestedSize) {
        if (requestedPage != null && requestedPage < 0) {
            throw new InvalidRequestException("Runner page index must not be negative");
        }
        if (requestedSize != null && (requestedSize < 1 || requestedSize > 100)) {
            throw new InvalidRequestException("Runner page size must be between 1 and 100");
        }
        if (pageable == null || pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new InvalidRequestException("Runner page size must be between 1 and 100");
        }
        pageable.getSort().forEach(order -> {
            if (!SUPPORTED_SORTS.contains(order.getProperty())) {
                throw new InvalidRequestException(
                        "Unsupported runner sort field: " + order.getProperty());
            }
        });
        if (direction == null) {
            return pageable;
        }
        Sort.Direction parsedDirection;
        try {
            parsedDirection = Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(
                    "Runner sort direction must be asc or desc");
        }
        Sort overriddenSort = Sort.by(pageable.getSort().stream()
                .map(order -> new Sort.Order(parsedDirection, order.getProperty()))
                .toList());
        return PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), overriddenSort);
    }

    private RunnerQueryFilter validateFilter(RunnerQueryFilter filter) {
        if (filter == null) {
            return new RunnerQueryFilter(null, null, null, null, null);
        }
        String capability = optional(filter.capability(), "Capability", 150);
        String label = optional(filter.label(), "Label", 250);
        return new RunnerQueryFilter(
                filter.status(),
                filter.health(),
                filter.available(),
                capability,
                label);
    }

    private String optional(String value, String field, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new InvalidRequestException(field + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new InvalidRequestException(
                    field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }
}
