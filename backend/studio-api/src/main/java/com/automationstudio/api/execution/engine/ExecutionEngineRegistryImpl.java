package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ExecutionEngineRegistryImpl implements ExecutionEngineRegistry {

    private final Map<String, Map<String, ExecutionEngineSupport>> engines;
    private final List<ExecutionEngineDescriptor> descriptors;

    public ExecutionEngineRegistryImpl(List<ExecutionEngine> availableEngines) {
        Objects.requireNonNull(availableEngines, "Available engines must not be null");
        List<ExecutionEngineSupport> availableSupports = availableEngines.stream()
                .map(ExecutionEngineRegistryImpl::validatedSupport)
                .sorted(Comparator.comparing(
                                (ExecutionEngineSupport support) ->
                                        support.descriptor().engineId())
                        .thenComparing(support ->
                                support.descriptor().implementationVersion()))
                .toList();
        Map<String, Map<String, ExecutionEngineSupport>> registrations = new LinkedHashMap<>();
        for (ExecutionEngineSupport support : availableSupports) {
            ExecutionEngineDescriptor descriptor = support.descriptor();
            Map<String, ExecutionEngineSupport> versions = registrations.computeIfAbsent(
                    descriptor.engineId(), ignored -> new LinkedHashMap<>());
            ExecutionEngineSupport prior = versions.putIfAbsent(
                    descriptor.implementationVersion(),
                    support);
            if (prior != null) {
                throw new ExecutionEngineRegistrationException(
                        "Duplicate execution engine registration");
            }
        }
        Map<String, Map<String, ExecutionEngineSupport>> immutable = new LinkedHashMap<>();
        registrations.forEach((engineId, versions) -> immutable.put(
                engineId,
                Collections.unmodifiableMap(new LinkedHashMap<>(versions))));
        engines = Collections.unmodifiableMap(immutable);
        descriptors = availableSupports.stream()
                .map(ExecutionEngineSupport::descriptor)
                .toList();
    }

    @Override
    public ExecutionEngineSupport resolve(String engineName) {
        String name = requireText(engineName, "Engine name");
        Map<String, ExecutionEngineSupport> versions = engines.get(name);
        if (versions == null) {
            throw new ExecutionEngineNotFoundException(
                    "Execution engine was not found");
        }
        if (versions.size() != 1) {
            throw new ExecutionEngineAmbiguousException(
                    "Execution engine registration is ambiguous");
        }
        return requireConsistentDescriptor(versions.values().iterator().next());
    }

    @Override
    public ExecutionEngineSupport resolve(String engineName, String engineVersion) {
        String name = requireText(engineName, "Engine name");
        String version = requireText(engineVersion, "Engine version");
        Map<String, ExecutionEngineSupport> versions = engines.get(name);
        if (versions == null) {
            throw new ExecutionEngineNotFoundException(
                    "Execution engine was not found");
        }
        ExecutionEngineSupport support = versions.get(version);
        if (support == null) {
            throw new ExecutionEngineVersionNotSupportedException(
                    "Execution engine implementation version is not supported");
        }
        return requireConsistentDescriptor(support);
    }

    @Override
    public ExecutionEngineSupport validateCompatibility(ExecutionContext context) {
        Objects.requireNonNull(context, "Execution context must not be null");
        String name = context.suite().engineId();
        String version = context.suite().engineVersion();
        ExecutionEngineSupport support = resolve(name, version);
        Object advertisedVersion = advertisedEngines(context).get(name);
        if (!(advertisedVersion instanceof String runnerVersion)
                || !version.equals(runnerVersion)) {
            throw new ExecutionEngineCompatibilityException(
                    "Runner does not support execution engine " + name + ":" + version);
        }
        support.engine().validate(context);
        return support;
    }

    @Override
    public List<ExecutionEngineDescriptor> supportedEngines() {
        return descriptors;
    }

    private static Map<?, ?> advertisedEngines(ExecutionContext context) {
        Object value = context.runner().runnerCapabilities().get("engines");
        if (!(value instanceof Map<?, ?> map)) {
            throw new ExecutionEngineCompatibilityException(
                    "Runner engine capabilities are missing or invalid");
        }
        return map;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static ExecutionEngineSupport validatedSupport(ExecutionEngine engine) {
        if (engine == null) {
            throw new ExecutionEngineInvalidDescriptorException(
                    "Execution engine registration is invalid");
        }
        ExecutionEngineDescriptor descriptor = descriptorOf(engine);
        if (!descriptor.equals(descriptorOf(engine))) {
            throw new ExecutionEngineInvalidDescriptorException(
                    "Execution engine descriptor is inconsistent");
        }
        return new ExecutionEngineSupport(engine, descriptor);
    }

    private static ExecutionEngineSupport requireConsistentDescriptor(
            ExecutionEngineSupport support) {
        if (!support.descriptor().equals(descriptorOf(support.engine()))) {
            throw new ExecutionEngineInvalidDescriptorException(
                    "Execution engine descriptor is inconsistent");
        }
        return support;
    }

    private static ExecutionEngineDescriptor descriptorOf(ExecutionEngine engine) {
        try {
            ExecutionEngineDescriptor descriptor = engine.descriptor();
            if (descriptor == null) {
                throw new ExecutionEngineInvalidDescriptorException(
                        "Execution engine descriptor is invalid");
            }
            return descriptor;
        } catch (ExecutionEngineInvalidDescriptorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExecutionEngineInvalidDescriptorException(
                    "Execution engine descriptor is invalid");
        }
    }
}
