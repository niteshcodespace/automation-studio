package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import java.util.Comparator;
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
        Map<String, Map<String, ExecutionEngineSupport>> registrations = new LinkedHashMap<>();
        for (ExecutionEngine engine : availableEngines) {
            if (engine == null) {
                throw new ExecutionEngineRegistrationException(
                        "Execution engine must not be null");
            }
            ExecutionEngineDescriptor descriptor = engine.descriptor();
            if (descriptor == null) {
                throw new ExecutionEngineRegistrationException(
                        "Execution engine descriptor must not be null");
            }
            Map<String, ExecutionEngineSupport> versions = registrations.computeIfAbsent(
                    descriptor.engineName(), ignored -> new LinkedHashMap<>());
            ExecutionEngineSupport prior = versions.putIfAbsent(
                    descriptor.engineVersion(), new ExecutionEngineSupport(engine, descriptor));
            if (prior != null) {
                throw new ExecutionEngineRegistrationException(
                        "Duplicate execution engine registration");
            }
        }
        Map<String, Map<String, ExecutionEngineSupport>> immutable = new LinkedHashMap<>();
        registrations.forEach((name, versions) -> immutable.put(name, Map.copyOf(versions)));
        engines = Map.copyOf(immutable);
        descriptors = engines.values().stream()
                .flatMap(versions -> versions.values().stream())
                .map(ExecutionEngineSupport::descriptor)
                .sorted(Comparator.comparing(ExecutionEngineDescriptor::engineName)
                        .thenComparing(ExecutionEngineDescriptor::engineVersion))
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
            throw new ExecutionEngineCompatibilityException(
                    "Execution engine version must be specified for " + name);
        }
        return versions.values().iterator().next();
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
            throw new ExecutionEngineCompatibilityException(
                    "Execution engine version is not supported: " + name + ":" + version);
        }
        return support;
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
}
