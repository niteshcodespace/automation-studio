package com.automationstudio.api.execution;

import com.automationstudio.api.security.SensitiveKeyDetector;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ExecutionContextBuilder {

    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    static final Duration MAX_TIMEOUT = Duration.ofHours(24);
    private final SensitiveKeyDetector sensitiveKeyDetector;

    public ExecutionContextBuilder(SensitiveKeyDetector sensitiveKeyDetector) {
        this.sensitiveKeyDetector = sensitiveKeyDetector;
    }

    public ExecutionContext build(ExecutionContextSource source) {
        if (source == null) {
            throw invalid("Execution context source must not be null");
        }
        requireIdentity(source.executionId(), "Execution");
        requireIdentity(source.projectId(), "Project");
        requireIdentity(source.workspaceId(), "Workspace");
        requireIdentity(source.environmentId(), "Environment");
        requireIdentity(source.suiteId(), "Suite");

        Map<String, Object> environment = requireObject(
                source.environmentSnapshot(), "Execution environment snapshot");
        Map<String, Object> suite =
                requireObject(source.suiteSnapshot(), "Execution suite snapshot");
        Map<String, Object> request =
                requireObject(source.requestSnapshot(), "Execution request snapshot");

        requireMatchingId(environment, "id", source.environmentId(), "Environment");
        requireMatchingId(suite, "id", source.suiteId(), "Suite");

        Map<String, Object> environmentConfiguration =
                requireNestedObject(environment, "configuration", "Environment");
        Map<String, Object> suiteConfiguration =
                requireNestedObject(suite, "configuration", "Suite");
        Map<String, Object> environmentVariables =
                optionalNestedObject(environmentConfiguration, "variables", "Environment");
        Map<String, Object> suiteVariables =
                optionalNestedObject(suiteConfiguration, "variables", "Suite");
        Map<String, Object> executionVariables =
                optionalNestedObject(request, "variables", "Execution");
        rejectSensitiveValues(environmentConfiguration, "Environment configuration");
        rejectSensitiveValues(suiteConfiguration, "Suite configuration");
        rejectSensitiveValues(executionVariables, "Execution variables");

        String engineId = requireText(suite, "engineId", "Suite");
        String engineVersion = engineVersion(source.runnerCapabilities(), engineId);
        ExecutionEnvironmentSnapshot environmentContext = new ExecutionEnvironmentSnapshot(
                source.environmentId(),
                requireText(environment, "name", "Environment"),
                requireText(environment, "type", "Environment"),
                requireText(environment, "baseUrl", "Environment"),
                environmentVariables,
                without(environmentConfiguration, "variables"));
        ExecutionSuiteSnapshot suiteContext = new ExecutionSuiteSnapshot(
                source.suiteId(),
                requireText(suite, "name", "Suite"),
                engineId,
                engineVersion,
                requireText(suite, "engineType", "Suite"),
                optionalText(suite, "suiteType", "Suite"),
                requireText(suite, "suiteReference", "Suite"),
                suiteVariables,
                without(suiteConfiguration, "variables"));

        Map<String, ExecutionVariable> variables = resolveVariables(
                source.systemDefaults(),
                source.projectDefaults(),
                environmentVariables,
                suiteVariables,
                executionVariables);
        List<ExecutionSecretReference> secretReferences = secretReferences(
                requireNestedObject(environment, "secretReferences", "Environment"));
        ExecutionRunnerContext runner = new ExecutionRunnerContext(
                source.runnerId(),
                source.runnerKey(),
                source.runnerVersion(),
                source.runnerOperatingSystem(),
                source.runnerArchitecture(),
                requireObject(source.runnerCapabilities(), "Runner capabilities"),
                requireObject(source.runnerLabels(), "Runner labels"));
        ExecutionMetadata metadata = new ExecutionMetadata(
                correlationId(request, source.executionId()),
                source.createdAt(),
                source.claimedAt(),
                timeout(request),
                retryPolicy(request));
        return new ExecutionContext(
                source.executionId(),
                source.projectId(),
                source.workspaceId(),
                suiteContext,
                environmentContext,
                secretReferences,
                variables,
                runner,
                metadata);
    }

    private void rejectSensitiveValues(Map<String, Object> values, String owner) {
        values.forEach((key, value) -> {
            if (sensitiveKeyDetector.isSensitive(key)) {
                throw invalid(owner + " contains a sensitive value key");
            }
            if (value instanceof Map<?, ?> nested) {
                rejectSensitiveValues(stringObject(nested, owner), owner);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> nested) {
                        rejectSensitiveValues(stringObject(nested, owner), owner);
                    }
                }
            }
        });
    }

    private static Map<String, ExecutionVariable> resolveVariables(
            Map<String, Object> system,
            Map<String, Object> project,
            Map<String, Object> environment,
            Map<String, Object> suite,
            Map<String, Object> execution) {
        Map<String, ExecutionVariable> resolved = new LinkedHashMap<>();
        Map<String, String> spelling = new LinkedHashMap<>();
        merge(resolved, spelling, system, ExecutionVariableSource.SYSTEM_DEFAULT);
        merge(resolved, spelling, project, ExecutionVariableSource.PROJECT_DEFAULT);
        merge(resolved, spelling, environment, ExecutionVariableSource.ENVIRONMENT);
        merge(resolved, spelling, suite, ExecutionVariableSource.SUITE);
        merge(resolved, spelling, execution, ExecutionVariableSource.EXECUTION);
        return Map.copyOf(resolved);
    }

    private static void merge(
            Map<String, ExecutionVariable> target,
            Map<String, String> spelling,
            Map<String, Object> layer,
            ExecutionVariableSource source) {
        for (Map.Entry<String, Object> entry : layer.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || entry.getValue() == null) {
                throw invalid(source + " variables must contain nonblank keys and non-null values");
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            String priorSpelling = spelling.putIfAbsent(normalized, key);
            if (priorSpelling != null && !priorSpelling.equals(key)) {
                throw invalid("Duplicate execution variable names differ only by case: "
                        + priorSpelling + " and " + key);
            }
            target.put(key, new ExecutionVariable(key, entry.getValue(), source));
        }
    }

    private static List<ExecutionSecretReference> secretReferences(Map<String, Object> values) {
        List<ExecutionSecretReference> references = new ArrayList<>();
        values.forEach((key, value) ->
                references.add(new ExecutionSecretReference(key, value)));
        return List.copyOf(references);
    }

    private static String engineVersion(Map<String, Object> capabilities, String engineId) {
        Map<String, Object> root = requireObject(capabilities, "Runner capabilities");
        Map<String, Object> engines = requireNestedObject(root, "engines", "Runner capabilities");
        Object version = engines.get(engineId);
        if (!(version instanceof String text) || text.isBlank()) {
            throw invalid("Runner does not advertise a valid version for engine " + engineId);
        }
        return text;
    }

    private static UUID correlationId(Map<String, Object> request, UUID defaultValue) {
        Object value = request.get("correlationId");
        if (value == null) {
            return defaultValue;
        }
        try {
            return UUID.fromString(requireText(request, "correlationId", "Request"));
        } catch (IllegalArgumentException exception) {
            throw invalid("Request correlation ID is invalid", exception);
        }
    }

    private static Duration timeout(Map<String, Object> request) {
        Object value = request.get("timeout");
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        try {
            Duration timeout = Duration.parse(requireText(request, "timeout", "Request"));
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
                throw invalid("Execution timeout must be positive and at most " + MAX_TIMEOUT);
            }
            return timeout;
        } catch (DateTimeParseException exception) {
            throw invalid("Execution timeout is invalid", exception);
        }
    }

    private static ExecutionRetryPolicy retryPolicy(Map<String, Object> request) {
        Object value = request.get("retryPolicy");
        if (value == null) {
            return ExecutionRetryPolicy.DISABLED;
        }
        String policy = requireText(request, "retryPolicy", "Request");
        if (!"DISABLED".equals(policy) && !"NONE".equals(policy)) {
            throw invalid("Execution retries are not supported");
        }
        return ExecutionRetryPolicy.DISABLED;
    }

    private static Map<String, Object> requireObject(
            Map<String, Object> value, String name) {
        if (value == null) {
            throw invalid(name + " is missing");
        }
        return value;
    }

    private static Map<String, Object> requireNestedObject(
            Map<String, Object> source, String field, String owner) {
        Object value = source.get(field);
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(owner + " " + field + " is missing or invalid");
        }
        return stringObject(map, owner + " " + field);
    }

    private static Map<String, Object> optionalNestedObject(
            Map<String, Object> source, String field, String owner) {
        if (!source.containsKey(field)) {
            return Map.of();
        }
        return requireNestedObject(source, field, owner);
    }

    private static Map<String, Object> stringObject(Map<?, ?> source, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text) || text.isBlank()) {
                throw invalid(name + " contains an invalid key");
            }
            result.put(text, value);
        });
        return result;
    }

    private static Map<String, Object> without(Map<String, Object> source, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove(key);
        return ImmutableExecutionValue.object(copy, "Execution configuration");
    }

    private static void requireMatchingId(
            Map<String, Object> snapshot, String field, UUID expected, String owner) {
        try {
            UUID actual = UUID.fromString(requireText(snapshot, field, owner));
            if (!actual.equals(expected)) {
                throw invalid(owner + " snapshot identity does not match execution");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(owner + " snapshot identity is invalid", exception);
        }
    }

    private static String requireText(
            Map<String, Object> source, String field, String owner) {
        Object value = source.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(owner + " " + field + " is missing or invalid");
        }
        return text;
    }

    private static String optionalText(
            Map<String, Object> source, String field, String owner) {
        Object value = source.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(owner + " " + field + " is invalid");
        }
        return text;
    }

    private static void requireIdentity(UUID value, String name) {
        if (value == null) {
            throw invalid(name + " ID must not be null");
        }
    }

    private static InvalidExecutionContextException invalid(String message) {
        return new InvalidExecutionContextException(message);
    }

    private static InvalidExecutionContextException invalid(String message, Throwable cause) {
        return new InvalidExecutionContextException(message, cause);
    }
}
