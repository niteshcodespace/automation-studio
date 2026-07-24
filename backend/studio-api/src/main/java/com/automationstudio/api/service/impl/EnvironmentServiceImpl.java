package com.automationstudio.api.service.impl;

import static com.automationstudio.api.repository.EnvironmentSpecifications.withFilters;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.EnvironmentService;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class EnvironmentServiceImpl implements EnvironmentService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_BASE_URL_LENGTH = 500;
    private static final int MAX_JSON_BYTES = 65_536;
    private static final Set<String> PROHIBITED_CONFIGURATION_KEYS = Set.of(
            "password",
            "passwd",
            "secret",
            "clientsecret",
            "apikey",
            "accesstoken",
            "refreshtoken",
            "privatekey");
    private static final Pattern REFERENCE_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*$");

    private final EnvironmentRepository environmentRepository;
    private final ProjectRepository projectRepository;
    private final ExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;

    public EnvironmentServiceImpl(
            EnvironmentRepository environmentRepository,
            ProjectRepository projectRepository,
            ExecutionRepository executionRepository,
            ObjectMapper objectMapper) {
        this.environmentRepository = environmentRepository;
        this.projectRepository = projectRepository;
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Environment create(UUID projectId, CreateEnvironmentCommand command) {
        if (command == null) {
            throw new InvalidRequestException("Environment create command must not be null");
        }
        NormalizedEnvironment normalized = normalize(
                command.name(),
                command.description(),
                command.baseUrl(),
                command.type(),
                command.configuration(),
                command.secretReferences());
        EnvironmentStatus status = command.status() == null
                ? EnvironmentStatus.ACTIVE
                : command.status();
        boolean isDefault = Boolean.TRUE.equals(command.isDefault());
        if (isDefault && status != EnvironmentStatus.ACTIVE) {
            throw new ResourceConflictException(
                    "Only an ACTIVE environment may be the project default");
        }

        Project project = isDefault ? lockProject(projectId) : findProject(projectId);
        rejectDuplicateName(projectId, normalized.name(), null);
        if (isDefault) {
            clearCurrentDefault(projectId, null);
        }

        Environment environment = new Environment();
        environment.setProject(project);
        environment.setName(normalized.name());
        environment.setDescription(normalized.description());
        environment.setBaseUrl(normalized.baseUrl());
        environment.setType(normalized.type());
        environment.setConfiguration(normalized.configuration());
        environment.setSecretReferences(normalized.secretReferences());
        environment.setStatus(status);
        environment.setDefault(isDefault);
        return saveAndFlush(environment, "Environment creation conflicts with persisted state");
    }

    @Override
    @Transactional(readOnly = true)
    public Environment get(UUID projectId, UUID environmentId) {
        verifyProject(projectId);
        return findEnvironment(projectId, environmentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Environment> list(
            UUID projectId,
            EnvironmentStatus status,
            EnvironmentType type,
            Boolean isDefault,
            Pageable pageable) {
        verifyProject(projectId);
        if (pageable == null) {
            throw new InvalidRequestException("Pageable must not be null");
        }
        return environmentRepository.findAll(
                withFilters(projectId, status, type, isDefault), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Environment> getDefault(UUID projectId) {
        verifyProject(projectId);
        return environmentRepository.findByProjectIdAndIsDefaultTrue(projectId);
    }

    @Override
    public Environment update(
            UUID projectId,
            UUID environmentId,
            long expectedVersion,
            UpdateEnvironmentCommand command) {
        verifyExpectedVersion(expectedVersion);
        if (command == null) {
            throw new InvalidRequestException("Environment update command must not be null");
        }
        NormalizedEnvironment normalized = normalize(
                command.name(),
                command.description(),
                command.baseUrl(),
                command.type(),
                command.configuration(),
                command.secretReferences());
        verifyProject(projectId);
        Environment environment = findEnvironment(projectId, environmentId);
        verifyVersion(environment, expectedVersion);
        rejectDuplicateName(projectId, normalized.name(), environmentId);

        environment.setName(normalized.name());
        environment.setDescription(normalized.description());
        environment.setBaseUrl(normalized.baseUrl());
        environment.setType(normalized.type());
        environment.setConfiguration(normalized.configuration());
        environment.setSecretReferences(normalized.secretReferences());
        return saveAndFlush(environment, "Environment update conflicts with persisted state");
    }

    @Override
    public Environment changeStatus(
            UUID projectId,
            UUID environmentId,
            long expectedVersion,
            EnvironmentStatus status) {
        verifyExpectedVersion(expectedVersion);
        if (status == null) {
            throw new InvalidRequestException("Environment status must not be null");
        }
        lockProject(projectId);
        Environment environment = findEnvironment(projectId, environmentId);
        verifyVersion(environment, expectedVersion);
        if (environment.getStatus() == status) {
            return environment;
        }
        if (environment.isDefault() && status != EnvironmentStatus.ACTIVE) {
            environment.setDefault(false);
        }
        environment.setStatus(status);
        return saveAndFlush(
                environment, "Environment status change conflicts with persisted state");
    }

    @Override
    public Environment changeDefault(
            UUID projectId,
            UUID environmentId,
            long expectedVersion,
            boolean isDefault) {
        verifyExpectedVersion(expectedVersion);
        lockProject(projectId);
        Environment environment = findEnvironment(projectId, environmentId);
        verifyVersion(environment, expectedVersion);
        if (environment.isDefault() == isDefault) {
            return environment;
        }
        if (isDefault && environment.getStatus() != EnvironmentStatus.ACTIVE) {
            throw new ResourceConflictException(
                    "Only an ACTIVE environment may be the project default");
        }
        if (isDefault) {
            clearCurrentDefault(projectId, environmentId);
        }
        environment.setDefault(isDefault);
        return saveAndFlush(
                environment, "Environment default change conflicts with persisted state");
    }

    @Override
    public void delete(UUID projectId, UUID environmentId, long expectedVersion) {
        verifyExpectedVersion(expectedVersion);
        lockProject(projectId);
        Environment environment = findEnvironment(projectId, environmentId);
        verifyVersion(environment, expectedVersion);
        if (executionRepository.existsByProjectIdAndEnvironmentId(projectId, environmentId)) {
            throw referencedDeleteConflict(environmentId);
        }
        try {
            environmentRepository.delete(environment);
            environmentRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw referencedDeleteConflict(environmentId);
        } catch (OptimisticLockingFailureException exception) {
            throw optimisticConflict(environmentId);
        }
    }

    private NormalizedEnvironment normalize(
            String name,
            String description,
            String baseUrl,
            EnvironmentType type,
            Map<String, Object> configuration,
            Map<String, Object> secretReferences) {
        String normalizedName = normalizeRequired(name, "Environment name", MAX_NAME_LENGTH);
        String normalizedDescription = normalizeOptional(
                description, "Environment description", MAX_DESCRIPTION_LENGTH);
        String normalizedBaseUrl = validateBaseUrl(baseUrl);
        if (type == null) {
            throw new InvalidRequestException("Environment type must not be null");
        }
        Map<String, Object> normalizedConfiguration = copyOrEmpty(configuration);
        validateConfiguration(normalizedConfiguration);
        Map<String, Object> normalizedReferences = copyOrEmpty(secretReferences);
        validateSecretReferences(normalizedReferences);
        return new NormalizedEnvironment(
                normalizedName,
                normalizedDescription,
                normalizedBaseUrl,
                type,
                normalizedConfiguration,
                normalizedReferences);
    }

    private String normalizeRequired(String value, String label, int maximumLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidRequestException(label + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidRequestException(
                    label + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private String normalizeOptional(String value, String label, int maximumLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidRequestException(
                    label + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private String validateBaseUrl(String value) {
        String normalized = normalizeRequired(
                value, "Environment base URL", MAX_BASE_URL_LENGTH);
        final URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException exception) {
            throw new InvalidRequestException("Environment base URL must be a valid URI");
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new InvalidRequestException(
                    "Environment base URL must be absolute HTTP(S) with a host"
                            + " and no user-info or fragment");
        }
        return normalized;
    }

    private Map<String, Object> copyOrEmpty(Map<String, Object> input) {
        return input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
    }

    private void validateConfiguration(Map<String, Object> configuration) {
        validateJsonSize("configuration", configuration);
        screenConfigurationMap(configuration, "configuration");
    }

    private void screenConfigurationMap(Map<?, ?> map, String path) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String keyPath = path + "." + key;
            if (PROHIBITED_CONFIGURATION_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new InvalidRequestException(
                        "Environment configuration contains prohibited key: " + keyPath);
            }
            screenConfigurationValue(entry.getValue(), keyPath);
        }
    }

    private void screenConfigurationValue(Object value, String path) {
        if (value instanceof Map<?, ?> nestedMap) {
            screenConfigurationMap(nestedMap, path);
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                screenConfigurationValue(list.get(index), path + "[" + index + "]");
            }
        }
    }

    private void validateSecretReferences(Map<String, Object> secretReferences) {
        validateJsonSize("secretReferences", secretReferences);
        for (Map.Entry<String, Object> entry : secretReferences.entrySet()) {
            String keyPath = "secretReferences." + entry.getKey();
            if (!(entry.getValue() instanceof String reference)
                    || reference.isBlank()
                    || !REFERENCE_PATTERN.matcher(reference).matches()) {
                throw new InvalidRequestException(
                        "Environment secret reference must be a nonblank URI-style string at: "
                                + keyPath);
            }
        }
    }

    private void validateJsonSize(String field, Map<String, Object> value) {
        try {
            if (objectMapper.writeValueAsBytes(value).length > MAX_JSON_BYTES) {
                throw new InvalidRequestException(
                        "Environment " + field + " must not exceed 65536 UTF-8 bytes");
            }
        } catch (JacksonException exception) {
            throw new InvalidRequestException(
                    "Environment " + field + " must contain serializable JSON values");
        }
    }

    private Project findProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> missingProject(projectId));
    }

    private Project lockProject(UUID projectId) {
        return projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> missingProject(projectId));
    }

    private void verifyProject(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw missingProject(projectId);
        }
    }

    private Environment findEnvironment(UUID projectId, UUID environmentId) {
        return environmentRepository.findByProjectIdAndId(projectId, environmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Environment not found with id: " + environmentId
                                + " in project: " + projectId));
    }

    private ResourceNotFoundException missingProject(UUID projectId) {
        return new ResourceNotFoundException("Project not found with id: " + projectId);
    }

    private void rejectDuplicateName(UUID projectId, String name, UUID currentEnvironmentId) {
        Optional<Environment> matching =
                environmentRepository.findByProjectIdAndName(projectId, name);
        if (matching.isPresent()
                && !matching.orElseThrow().getId().equals(currentEnvironmentId)) {
            throw new DuplicateResourceException(
                    "Environment with name '" + name
                            + "' already exists in project: " + projectId);
        }
    }

    private void clearCurrentDefault(UUID projectId, UUID targetEnvironmentId) {
        environmentRepository.findByProjectIdAndIsDefaultTrue(projectId)
                .filter(current -> !current.getId().equals(targetEnvironmentId))
                .ifPresent(current -> {
                    current.setDefault(false);
                    saveAndFlush(
                            current,
                            "Environment default change conflicts with persisted state");
                });
    }

    private void verifyExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new InvalidRequestException("Expected Environment version must be nonnegative");
        }
    }

    private void verifyVersion(Environment environment, long expectedVersion) {
        if (environment.getVersion() != expectedVersion) {
            throw optimisticConflict(environment.getId());
        }
    }

    private Environment saveAndFlush(Environment environment, String databaseConflictMessage) {
        try {
            return environmentRepository.saveAndFlush(environment);
        } catch (OptimisticLockingFailureException exception) {
            throw optimisticConflict(environment.getId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException(databaseConflictMessage);
        }
    }

    private ResourceConflictException optimisticConflict(UUID environmentId) {
        return new ResourceConflictException(
                "Environment version conflict for id: " + environmentId);
    }

    private ResourceConflictException referencedDeleteConflict(UUID environmentId) {
        return new ResourceConflictException(
                "Environment cannot be deleted while executions reference it: " + environmentId);
    }

    private record NormalizedEnvironment(
            String name,
            String description,
            String baseUrl,
            EnvironmentType type,
            Map<String, Object> configuration,
            Map<String, Object> secretReferences) {
    }
}
