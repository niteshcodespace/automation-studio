package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.entity.RunnerRuntime;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.exception.RunnerAlreadyDeregisteredException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerRegistrationService;
import com.automationstudio.api.service.command.RegisterRunnerCommand;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerRegistrationServiceImpl implements RunnerRegistrationService {

    private static final int MAX_RUNNER_KEY_LENGTH = 150;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_AGENT_VERSION_LENGTH = 100;
    private static final int MAX_HOSTNAME_LENGTH = 255;
    private static final int MAX_OPERATING_SYSTEM_LENGTH = 100;
    private static final int MAX_ARCHITECTURE_LENGTH = 50;
    private static final int MAX_CONCURRENCY = 1000;
    private static final Pattern RUNNER_KEY_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{0,149}$");

    private final RunnerRepository runnerRepository;
    private final RunnerRuntimeRepository runtimeRepository;

    public RunnerRegistrationServiceImpl(
            RunnerRepository runnerRepository,
            RunnerRuntimeRepository runtimeRepository) {
        this.runnerRepository = runnerRepository;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    @Transactional
    public Runner register(RegisterRunnerCommand command) {
        ValidatedRegistration registration = validate(command);

        runnerRepository.lockRegistrationKey(registration.runnerKey());
        Runner existing = runnerRepository
                .findByRunnerKeyForUpdate(registration.runnerKey())
                .orElse(null);
        OffsetDateTime registeredAt = runnerRepository.currentDatabaseTime()
                .atOffset(ZoneOffset.UTC);

        if (existing == null) {
            return createRunner(registration, registeredAt);
        }
        return reregister(existing, registration, registeredAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Runner getRunner(UUID runnerId) {
        if (runnerId == null) {
            throw new InvalidRequestException("Runner ID must not be null");
        }
        return runnerRepository.findById(runnerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Runner not found: " + runnerId));
    }

    @Override
    @Transactional(readOnly = true)
    public Runner getRunner(String runnerKey) {
        String canonicalKey = validateRunnerKey(runnerKey);
        return runnerRepository.findByRunnerKey(canonicalKey)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Runner not found: " + canonicalKey));
    }

    private Runner createRunner(
            ValidatedRegistration registration,
            OffsetDateTime registeredAt) {
        Runner runner = new Runner(
                registration.runnerKey(),
                registration.name(),
                registration.description(),
                registration.agentVersion(),
                registration.hostname(),
                registration.operatingSystem(),
                registration.architecture(),
                registration.maxConcurrency(),
                registration.capabilities(),
                registration.labels(),
                RunnerStatus.ACTIVE,
                registeredAt);
        try {
            runnerRepository.saveAndFlush(runner);
            runtimeRepository.saveAndFlush(new RunnerRuntime(runner.getId(), registeredAt));
            return runner;
        } catch (DataAccessException exception) {
            throw new ResourceConflictException(
                    "Runner registration conflicts with persisted state");
        }
    }

    private Runner reregister(
            Runner runner,
            ValidatedRegistration registration,
            OffsetDateTime registeredAt) {
        if (runner.getStatus() == RunnerStatus.DEREGISTERED) {
            throw new RunnerAlreadyDeregisteredException(registration.runnerKey());
        }

        runtimeRepository.findByRunnerIdForUpdate(runner.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Runner runtime is missing for registered runner"));
        runner.updateMetadata(
                registration.name(),
                registration.description(),
                registration.agentVersion(),
                registration.hostname(),
                registration.operatingSystem(),
                registration.architecture(),
                registration.maxConcurrency(),
                registration.capabilities(),
                registration.labels(),
                registeredAt);
        try {
            runnerRepository.saveAndFlush(runner);
            return runner;
        } catch (DataAccessException exception) {
            throw new ResourceConflictException(
                    "Runner re-registration conflicts with persisted state");
        }
    }

    private ValidatedRegistration validate(RegisterRunnerCommand command) {
        if (command == null) {
            throw new InvalidRequestException("Runner registration command must not be null");
        }
        String runnerKey = validateRunnerKey(command.runnerKey());
        String name = required(command.name(), "Runner name", MAX_NAME_LENGTH);
        String description = optional(command.description(), "Runner description",
                MAX_DESCRIPTION_LENGTH);
        String agentVersion = required(
                command.agentVersion(), "Agent version", MAX_AGENT_VERSION_LENGTH);
        String hostname = required(command.hostname(), "Hostname", MAX_HOSTNAME_LENGTH);
        String operatingSystem = required(
                command.operatingSystem(), "Operating system", MAX_OPERATING_SYSTEM_LENGTH);
        String architecture = required(
                command.architecture(), "Architecture", MAX_ARCHITECTURE_LENGTH);
        if (command.maxConcurrency() < 1 || command.maxConcurrency() > MAX_CONCURRENCY) {
            throw new InvalidRequestException(
                    "Maximum concurrency must be between 1 and " + MAX_CONCURRENCY);
        }
        if (command.capabilities() == null) {
            throw new InvalidRequestException("Runner capabilities must not be null");
        }
        if (command.labels() == null) {
            throw new InvalidRequestException("Runner labels must not be null");
        }
        return new ValidatedRegistration(
                runnerKey,
                name,
                description,
                agentVersion,
                hostname,
                operatingSystem,
                architecture,
                command.maxConcurrency(),
                command.capabilities(),
                command.labels());
    }

    private String validateRunnerKey(String runnerKey) {
        String canonicalKey = required(
                runnerKey, "Runner key", MAX_RUNNER_KEY_LENGTH).toLowerCase(Locale.ROOT);
        if (!RUNNER_KEY_PATTERN.matcher(canonicalKey).matches()) {
            throw new InvalidRequestException("Runner key has an invalid format");
        }
        return canonicalKey;
    }

    private String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidRequestException(
                    field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private String optional(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidRequestException(
                    field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private record ValidatedRegistration(
            String runnerKey,
            String name,
            String description,
            String agentVersion,
            String hostname,
            String operatingSystem,
            String architecture,
            int maxConcurrency,
            Map<String, Object> capabilities,
            Map<String, Object> labels) {
    }
}
